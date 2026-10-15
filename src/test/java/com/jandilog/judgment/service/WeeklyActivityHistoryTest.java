package com.jandilog.judgment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.judgment.domain.JudgmentResult;
import com.jandilog.judgment.domain.WeeklyJudgment;
import com.jandilog.judgment.dto.JudgmentHistoryItem;
import com.jandilog.judgment.dto.JudgmentHistoryPage;
import com.jandilog.judgment.repository.JudgmentDayRepository;
import com.jandilog.judgment.repository.JudgmentSourceRepository;
import com.jandilog.judgment.repository.WeeklyJudgmentRepository;
import com.jandilog.member.repository.MemberRepository;

// 지난 판정 목록 (AC-01): 최근 주부터 커서 20개(Q-06). 커서는 마지막 행 주차를 Base64url로 감싼 문자열이다
class WeeklyActivityHistoryTest {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-14T03:00:00Z"), ZoneId.of("Asia/Seoul"));
	private static final LocalDate NEWEST = LocalDate.of(2026, 10, 5);
	private static final long MEMBER_ID = 7L;

	private WeeklyJudgmentRepository judgmentRepository;
	private WeeklyActivityService service;

	@BeforeEach
	void setUp() {
		judgmentRepository = mock(WeeklyJudgmentRepository.class);
		service = new WeeklyActivityService(mock(MemberRepository.class), judgmentRepository,
				mock(JudgmentDayRepository.class), mock(JudgmentSourceRepository.class), mock(GrassCacheService.class),
				CLOCK);
	}

	// 최근 주부터 count주의 통과 판정
	private void judgments(int count) {
		List<WeeklyJudgment> rows = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			LocalDate week = NEWEST.minusWeeks(i);
			rows.add(WeeklyJudgment.of(MEMBER_ID, week,
					JudgmentResult.judged(true, 3, 1, List.of(), week.plusDays(7).atTime(7, 0))));
		}
		when(judgmentRepository.findByMemberIdAndWeekStartLessThanOrderByWeekStartDesc(anyLong(), any(), any()))
				.thenReturn(rows);
	}

	@Test
	void 한_건_더_읽혀_21건이면_20건과_20번째_주차_커서를_낸다() {
		judgments(21);

		JudgmentHistoryPage page = service.history(MEMBER_ID, null);

		assertThat(page.items()).hasSize(20);
		assertThat(page.items()).extracting(JudgmentHistoryItem::weekStart).first().isEqualTo(NEWEST.toString());
		assertThat(page.nextCursor()).isEqualTo(WeeklyActivityService.encodeCursor(NEWEST.minusWeeks(19)));
		// 첫 페이지는 먼 미래 주차보다 이전을 21건 읽는다
		verify(judgmentRepository).findByMemberIdAndWeekStartLessThanOrderByWeekStartDesc(MEMBER_ID,
				LocalDate.of(9999, 12, 31), PageRequest.of(0, 21));
	}

	@Test
	void 정확히_20건이면_다음_커서가_없다() {
		judgments(20);

		JudgmentHistoryPage page = service.history(MEMBER_ID, "");

		assertThat(page.items()).hasSize(20);
		assertThat(page.nextCursor()).isNull();
	}

	@Test
	void 커서는_받은_주차보다_이전을_읽는다() {
		judgments(3);

		service.history(MEMBER_ID, WeeklyActivityService.encodeCursor(NEWEST));

		verify(judgmentRepository).findByMemberIdAndWeekStartLessThanOrderByWeekStartDesc(eq(MEMBER_ID), eq(NEWEST),
				any());
	}

	@Test
	void 커서_형식은_주차를_Base64url로_감싼_값이고_되돌리면_같은_주차다() {
		assertThat(WeeklyActivityService.encodeCursor(LocalDate.of(2026, 10, 12))).isEqualTo("MjAyNi0xMC0xMg");
		assertThat(WeeklyActivityService.decodeCursor("MjAyNi0xMC0xMg")).isEqualTo(LocalDate.of(2026, 10, 12));
		assertThat(WeeklyActivityService.decodeCursor(null)).isEqualTo(LocalDate.of(9999, 12, 31));
	}

	@Test
	void 읽을_수_없는_커서는_잘못된_입력이다() {
		// Base64가 아닌 값, 날짜가 아닌 값(Base64 "abc")
		for (String cursor : List.of("%%%", "YWJj")) {
			assertThatThrownBy(() -> service.history(MEMBER_ID, cursor)).isInstanceOfSatisfying(ApiException.class,
					e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
		}
	}

}
