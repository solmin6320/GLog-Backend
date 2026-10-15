package com.jandilog.judgment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.jandilog.judgment.repository.JudgmentHistoryRepository;
import com.jandilog.judgment.service.JudgmentCatchUpService.Result;
import com.jandilog.judgment.service.WeeklyJudgmentBatchService.JudgmentSummary;

// 기동 시 미판정 주차 따라잡기의 경계·예외 (E-34). 정상 경로는 JudgmentCatchUpServiceTest가 본다.
// "한 번에 최대 N주"는 구현 기본값이라 값을 적지 않고 JudgmentCatchUpService.MAX_CATCH_UP_WEEKS를 참조한다.
// 한도 단언은 이름이 "한도_"로 시작하는 메서드에 모아 두었다
class JudgmentCatchUpBoundaryTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final int MAX = JudgmentCatchUpService.MAX_CATCH_UP_WEEKS;
	// 2026-10-05는 월요일
	private static final LocalDate MON_1005 = LocalDate.of(2026, 10, 5);
	private static final LocalDate MON_0928 = MON_1005.minusWeeks(1);
	private static final JudgmentSummary SUMMARY = new JudgmentSummary(1, 1, 0, 0, 0, 0);

	private final WeeklyJudgmentBatchService batchService = mock(WeeklyJudgmentBatchService.class);
	private final JudgmentHistoryRepository historyRepository = mock(JudgmentHistoryRepository.class);

	// ---- 판정 시각 경계 (월요일 07:00 KST) ----

	@Test
	void 판정_시각_1나노초_전은_직전_주가_아직이고_정각은_직전_주가_판정_대상이다() {
		assertThat(JudgmentCatchUpService.lastDueWeek(LocalDateTime.of(2026, 10, 12, 6, 59, 59, 999_999_999)))
				.isEqualTo(MON_0928);
		assertThat(JudgmentCatchUpService.lastDueWeek(LocalDateTime.of(2026, 10, 12, 7, 0, 0, 0))).isEqualTo(MON_1005);
	}

	@Test
	void 주가_끝난_월요일_00시에도_판정_시각_전이라_그_주는_아직_판정하지_않는다() {
		// 10/5 주는 10/12 00:00에 끝났지만 판정 시각은 07:00이다
		assertThat(JudgmentCatchUpService.lastDueWeek(LocalDateTime.of(2026, 10, 12, 0, 0, 0))).isEqualTo(MON_0928);
		assertThat(JudgmentCatchUpService.lastDueWeek(LocalDateTime.of(2026, 10, 11, 23, 59, 59, 999_999_999)))
				.isEqualTo(MON_0928);
	}

	@Test
	void 해가_바뀌는_주도_월요일_기준으로_주차를_계산한다() {
		// 2026-12-28 주는 2027-01-03 일요일에 끝나고 2027-01-04 07:00에 판정할 때가 된다
		LocalDate dec28 = LocalDate.of(2026, 12, 28);

		assertThat(JudgmentCatchUpService.lastDueWeek(LocalDateTime.of(2027, 1, 4, 6, 59, 59))).isEqualTo(dec28.minusWeeks(1));
		assertThat(JudgmentCatchUpService.lastDueWeek(LocalDateTime.of(2027, 1, 4, 7, 0))).isEqualTo(dec28);
		assertThat(JudgmentCatchUpService.missingWeeks(dec28.minusWeeks(1), LocalDateTime.of(2027, 1, 5, 9, 0)))
				.containsExactly(dec28);
	}

	@Test
	void 시계가_UTC여도_한국시간_07시_기준으로_판정할_주를_가른다() {
		// 2026-10-11T21:59:59Z = 10/12 06:59:59 KST, 22:00:00Z = 10/12 07:00:00 KST
		when(historyRepository.findLatestWeekStart()).thenReturn(Optional.of(MON_0928.minusWeeks(1)));
		Clock before = Clock.fixed(Instant.parse("2026-10-11T21:59:59Z"), ZoneOffset.UTC);
		Clock at = Clock.fixed(Instant.parse("2026-10-11T22:00:00Z"), ZoneOffset.UTC);

		Result beforeSeven = new JudgmentCatchUpService(batchService, historyRepository, before).catchUp();
		Result atSeven = new JudgmentCatchUpService(batchService, historyRepository, at).catchUp();

		// 06:59:59 KST에는 9/28 주까지, 07:00 KST부터는 10/5 주까지 판정한다
		assertThat(beforeSeven.judgedWeeks()).containsExactly(MON_0928);
		assertThat(atSeven.judgedWeeks()).containsExactly(MON_0928, MON_1005);
	}

	@Test
	void 이력이_현재보다_미래_주차면_아무것도_판정하지_않는다() {
		when(historyRepository.findLatestWeekStart()).thenReturn(Optional.of(MON_1005.plusWeeks(10)));

		Result result = service(LocalDateTime.of(2026, 10, 12, 9, 0)).catchUp();

		assertThat(result.judgedWeeks()).isEmpty();
		assertThat(result.leftWeeks()).isEmpty();
		verify(batchService, never()).judgeWeek(any());
	}

	// ---- 중간 실패와 재시도 ----

	@Test
	void 중간_주차_판정이_예외로_실패하면_뒤_주는_시도하지_않고_예외를_올린다() {
		LocalDate latest = MON_1005.minusWeeks(3);
		LocalDate w1 = latest.plusWeeks(1);
		LocalDate w2 = latest.plusWeeks(2);
		LocalDate w3 = latest.plusWeeks(3);
		when(historyRepository.findLatestWeekStart()).thenReturn(Optional.of(latest));
		when(batchService.judgeWeek(w1)).thenReturn(SUMMARY);
		when(batchService.judgeWeek(w2)).thenThrow(new IllegalStateException("DB 연결 끊김"));

		assertThatThrownBy(() -> service(LocalDateTime.of(2026, 10, 12, 9, 0)).catchUp())
				.isInstanceOf(IllegalStateException.class);

		verify(batchService).judgeWeek(w1);
		verify(batchService).judgeWeek(w2);
		verify(batchService, never()).judgeWeek(w3);
	}

	@Test
	void 중간에_실패한_다음_기동은_판정된_주_뒤부터_이어서_판정한다() {
		LocalDate latest = MON_1005.minusWeeks(3);
		LocalDate w1 = latest.plusWeeks(1);
		LocalDate w2 = latest.plusWeeks(2);
		LocalDate w3 = latest.plusWeeks(3);
		// 첫 기동에서 w1만 저장되고 w2에서 실패 → 다음 기동의 가장 최근 판정 주차는 w1
		when(historyRepository.findLatestWeekStart()).thenReturn(Optional.of(latest), Optional.of(w1));
		when(batchService.judgeWeek(w1)).thenReturn(SUMMARY);
		when(batchService.judgeWeek(w2)).thenThrow(new IllegalStateException("DB 연결 끊김")).thenReturn(SUMMARY);
		when(batchService.judgeWeek(w3)).thenReturn(SUMMARY);
		JudgmentCatchUpService service = service(LocalDateTime.of(2026, 10, 12, 9, 0));

		assertThatThrownBy(service::catchUp).isInstanceOf(IllegalStateException.class);
		Result second = service.catchUp();

		// 두 번째 기동은 w1을 다시 판정하지 않고 실패했던 w2부터 w3까지 이어서 판정한다
		assertThat(second.judgedWeeks()).containsExactly(w2, w3);
		verify(batchService, times(1)).judgeWeek(w1);
		verify(batchService, times(2)).judgeWeek(w2);
		verify(batchService, times(1)).judgeWeek(w3);
	}

	@Test
	void 한_주의_일부_회원이_실패해도_그_주_요약만_남기고_다음_주를_계속_판정한다() {
		LocalDate latest = MON_1005.minusWeeks(2);
		LocalDate w1 = latest.plusWeeks(1);
		LocalDate w2 = latest.plusWeeks(2);
		when(historyRepository.findLatestWeekStart()).thenReturn(Optional.of(latest));
		// judgeWeek는 회원별 예외를 안에서 세고 요약으로 돌려준다: failed 1건이 있어도 예외가 아니다
		when(batchService.judgeWeek(w1)).thenReturn(new JudgmentSummary(3, 2, 0, 0, 0, 1));
		when(batchService.judgeWeek(w2)).thenReturn(SUMMARY);

		Result result = service(LocalDateTime.of(2026, 10, 12, 9, 0)).catchUp();

		assertThat(result.judgedWeeks()).containsExactly(w1, w2);
		InOrder order = inOrder(batchService);
		order.verify(batchService).judgeWeek(w1);
		order.verify(batchService).judgeWeek(w2);
	}

	// ---- 결과 값 ----

	@Test
	void 결과의_주차_목록은_바꿀_수_없다() {
		List<LocalDate> judged = new ArrayList<>(List.of(MON_0928));
		List<LocalDate> left = new ArrayList<>(List.of(MON_1005));

		Result result = new Result(judged, left);
		judged.add(MON_1005);
		left.clear();

		assertThat(result.judgedWeeks()).containsExactly(MON_0928);
		assertThat(result.leftWeeks()).containsExactly(MON_1005);
		assertThatThrownBy(() -> result.judgedWeeks().add(MON_1005)).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> result.leftWeeks().add(MON_1005)).isInstanceOf(UnsupportedOperationException.class);
	}

	// ---- 한 번에 최대 N주 한도 (구현 기본값, 사용자 확인 대기. 값은 상수를 참조한다) ----

	@Test
	void 한도_빠진_주가_한도보다_하나_적으면_전부_판정하고_남기지_않는다() {
		LocalDate latest = MON_1005.minusWeeks(MAX - 1);
		when(historyRepository.findLatestWeekStart()).thenReturn(Optional.of(latest));

		Result result = service(LocalDateTime.of(2026, 10, 12, 9, 0)).catchUp();

		assertThat(result.judgedWeeks()).hasSize(MAX - 1);
		assertThat(result.leftWeeks()).isEmpty();
	}

	@Test
	void 한도_빠진_주가_한도와_같으면_전부_판정하고_남기지_않는다() {
		LocalDate latest = MON_1005.minusWeeks(MAX);
		when(historyRepository.findLatestWeekStart()).thenReturn(Optional.of(latest));

		Result result = service(LocalDateTime.of(2026, 10, 12, 9, 0)).catchUp();

		assertThat(result.judgedWeeks()).hasSize(MAX).first().isEqualTo(latest.plusWeeks(1));
		assertThat(result.judgedWeeks()).last().isEqualTo(MON_1005);
		assertThat(result.leftWeeks()).isEmpty();
	}

	@Test
	void 한도_빠진_주가_한도보다_하나_많으면_오래된_주부터_한도만큼_판정하고_가장_최근_주를_남긴다() {
		LocalDate latest = MON_1005.minusWeeks(MAX + 1);
		when(historyRepository.findLatestWeekStart()).thenReturn(Optional.of(latest));

		Result result = service(LocalDateTime.of(2026, 10, 12, 9, 0)).catchUp();

		assertThat(result.judgedWeeks()).hasSize(MAX).first().isEqualTo(latest.plusWeeks(1));
		assertThat(result.judgedWeeks()).last().isEqualTo(latest.plusWeeks(MAX));
		assertThat(result.leftWeeks()).containsExactly(MON_1005);
		verify(batchService, never()).judgeWeek(MON_1005);
	}

	@Test
	void 한도_오래_멈춘_서버는_한도만_판정하고_나머지_주를_오래된_순으로_남긴다() {
		int stoppedWeeks = 52;
		LocalDate latest = MON_1005.minusWeeks(stoppedWeeks);
		when(historyRepository.findLatestWeekStart()).thenReturn(Optional.of(latest));

		Result result = service(LocalDateTime.of(2026, 10, 12, 9, 0)).catchUp();

		assertThat(result.judgedWeeks()).hasSize(MAX);
		List<LocalDate> expectedLeft = IntStream.rangeClosed(MAX + 1, stoppedWeeks).mapToObj(latest::plusWeeks).toList();
		assertThat(result.leftWeeks()).containsExactlyElementsOf(expectedLeft);
		assertThat(result.leftWeeks()).last().isEqualTo(MON_1005);
		verify(batchService, times(MAX)).judgeWeek(any());
	}

	@Test
	void 한도_남긴_주는_다음_기동에서_이어서_판정되어_결국_모두_판정된다() {
		LocalDate latest = MON_1005.minusWeeks(MAX + 2);
		// 첫 기동은 한도만큼 저장하고, 다음 기동의 가장 최근 판정 주차는 그 마지막 주다
		LocalDate afterFirst = latest.plusWeeks(MAX);
		when(historyRepository.findLatestWeekStart()).thenReturn(Optional.of(latest), Optional.of(afterFirst));
		JudgmentCatchUpService service = service(LocalDateTime.of(2026, 10, 12, 9, 0));

		Result first = service.catchUp();
		Result second = service.catchUp();

		assertThat(first.leftWeeks()).containsExactly(MON_1005.minusWeeks(1), MON_1005);
		assertThat(second.judgedWeeks()).containsExactly(MON_1005.minusWeeks(1), MON_1005);
		assertThat(second.leftWeeks()).isEmpty();
	}

	private JudgmentCatchUpService service(LocalDateTime now) {
		Clock clock = Clock.fixed(now.atZone(KST).toInstant(), KST);
		return new JudgmentCatchUpService(batchService, historyRepository, clock);
	}

}
