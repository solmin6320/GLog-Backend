package com.jandilog.judgment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.jandilog.judgment.repository.JudgmentHistoryRepository;

// 기동 시 미판정 주차 결정 (E-34). 판정 시각은 그 주 다음 월요일 07:00 KST
class JudgmentCatchUpServiceTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	// 2026-10-05는 월요일
	private static final LocalDate MON_1005 = LocalDate.of(2026, 10, 5);
	private static final LocalDate MON_0928 = MON_1005.minusWeeks(1);
	private static final LocalDate MON_0921 = MON_1005.minusWeeks(2);

	private final WeeklyJudgmentBatchService batchService = mock(WeeklyJudgmentBatchService.class);
	private final JudgmentHistoryRepository historyRepository = mock(JudgmentHistoryRepository.class);

	@Test
	void 월요일_07시_전에는_직전_주가_아직_판정할_때가_아니다() {
		LocalDateTime sundayNight = LocalDateTime.of(2026, 10, 11, 23, 59);
		LocalDateTime mondayBefore = LocalDateTime.of(2026, 10, 12, 6, 59, 59);

		// 10/5 주는 10/12 07:00에 판정하므로 그 전에는 9/28 주가 마지막이다
		assertThat(JudgmentCatchUpService.lastDueWeek(sundayNight)).isEqualTo(MON_0928);
		assertThat(JudgmentCatchUpService.lastDueWeek(mondayBefore)).isEqualTo(MON_0928);
	}

	@Test
	void 월요일_07시부터는_직전_주가_판정할_때다() {
		LocalDateTime mondaySeven = LocalDateTime.of(2026, 10, 12, 7, 0);
		LocalDateTime tuesday = LocalDateTime.of(2026, 10, 13, 12, 0);
		LocalDateTime sunday = LocalDateTime.of(2026, 10, 18, 23, 59);

		assertThat(JudgmentCatchUpService.lastDueWeek(mondaySeven)).isEqualTo(MON_1005);
		assertThat(JudgmentCatchUpService.lastDueWeek(tuesday)).isEqualTo(MON_1005);
		// 일요일 밤까지는 10/12 주가 진행 중이라 다음 월요일 07:00(10/19)에야 판정할 때가 된다
		assertThat(JudgmentCatchUpService.lastDueWeek(sunday)).isEqualTo(MON_1005);
		assertThat(JudgmentCatchUpService.lastDueWeek(LocalDateTime.of(2026, 10, 19, 7, 0)))
				.isEqualTo(MON_1005.plusWeeks(1));
	}

	@Test
	void 가장_최근_판정_주차_다음부터_판정할_때가_지난_주차까지가_빠진_주차다() {
		LocalDateTime now = LocalDateTime.of(2026, 10, 12, 9, 0);

		assertThat(JudgmentCatchUpService.missingWeeks(MON_0928, now)).containsExactly(MON_1005);
		assertThat(JudgmentCatchUpService.missingWeeks(MON_0921, now)).containsExactly(MON_0928, MON_1005);
	}

	@Test
	void 이미_마지막_판정_주차까지_판정했으면_빠진_주차가_없다() {
		LocalDateTime now = LocalDateTime.of(2026, 10, 12, 9, 0);

		assertThat(JudgmentCatchUpService.missingWeeks(MON_1005, now)).isEmpty();
		// 이력이 미래 주차여도 아무것도 하지 않는다
		assertThat(JudgmentCatchUpService.missingWeeks(MON_1005.plusWeeks(3), now)).isEmpty();
	}

	@Test
	void 판정_이력이_하나도_없으면_아무것도_하지_않는다() {
		when(historyRepository.findLatestWeekStart()).thenReturn(Optional.empty());

		JudgmentCatchUpService.Result result = service(LocalDateTime.of(2026, 10, 12, 9, 0)).catchUp();

		assertThat(result.judgedWeeks()).isEmpty();
		assertThat(result.leftWeeks()).isEmpty();
		verifyNoInteractions(batchService);
	}

	@Test
	void 한_주가_빠졌으면_그_주만_판정한다() {
		when(historyRepository.findLatestWeekStart()).thenReturn(Optional.of(MON_0928));

		JudgmentCatchUpService.Result result = service(LocalDateTime.of(2026, 10, 12, 9, 0)).catchUp();

		assertThat(result.judgedWeeks()).containsExactly(MON_1005);
		assertThat(result.leftWeeks()).isEmpty();
		verify(batchService).judgeWeek(MON_1005);
		verify(batchService, never()).judgeWeek(MON_0928);
	}

	@Test
	void 여러_주가_빠졌으면_오래된_주부터_차례로_판정한다() {
		when(historyRepository.findLatestWeekStart()).thenReturn(Optional.of(MON_0921.minusWeeks(1)));

		JudgmentCatchUpService.Result result = service(LocalDateTime.of(2026, 10, 12, 9, 0)).catchUp();

		assertThat(result.judgedWeeks()).containsExactly(MON_0921, MON_0928, MON_1005);
		InOrder order = inOrder(batchService);
		order.verify(batchService).judgeWeek(MON_0921);
		order.verify(batchService).judgeWeek(MON_0928);
		order.verify(batchService).judgeWeek(MON_1005);
	}

	@Test
	void 아직_판정할_때가_안_된_주는_판정하지_않는다() {
		// 월요일 06:30: 10/5 주는 07:00에 스케줄러가 판정할 몫이다
		when(historyRepository.findLatestWeekStart()).thenReturn(Optional.of(MON_0921));

		JudgmentCatchUpService.Result result = service(LocalDateTime.of(2026, 10, 12, 6, 30)).catchUp();

		assertThat(result.judgedWeeks()).containsExactly(MON_0928);
		verify(batchService, never()).judgeWeek(MON_1005);
	}

	@Test
	void 이미_판정된_주만_있으면_판정을_부르지_않는다() {
		when(historyRepository.findLatestWeekStart()).thenReturn(Optional.of(MON_1005));

		JudgmentCatchUpService.Result result = service(LocalDateTime.of(2026, 10, 12, 9, 0)).catchUp();

		assertThat(result.judgedWeeks()).isEmpty();
		verify(batchService, never()).judgeWeek(any());
	}

	@Test
	void 빠진_주가_한도를_넘으면_오래된_주부터_한도만큼만_판정하고_나머지는_남긴다() {
		LocalDate latest = MON_1005.minusWeeks(7);
		when(historyRepository.findLatestWeekStart()).thenReturn(Optional.of(latest));

		JudgmentCatchUpService.Result result = service(LocalDateTime.of(2026, 10, 12, 9, 0)).catchUp();

		// 빠진 주는 latest+1 ~ 10/5 의 7주. 앞의 4주만 판정한다
		assertThat(JudgmentCatchUpService.MAX_CATCH_UP_WEEKS).isEqualTo(4);
		assertThat(result.judgedWeeks()).containsExactly(latest.plusWeeks(1), latest.plusWeeks(2), latest.plusWeeks(3),
				latest.plusWeeks(4));
		assertThat(result.leftWeeks()).containsExactly(latest.plusWeeks(5), latest.plusWeeks(6), latest.plusWeeks(7));
		verify(batchService, never()).judgeWeek(latest.plusWeeks(5));
	}

	private JudgmentCatchUpService service(LocalDateTime now) {
		Clock clock = Clock.fixed(now.atZone(KST).toInstant(), KST);
		return new JudgmentCatchUpService(batchService, historyRepository, clock);
	}

}
