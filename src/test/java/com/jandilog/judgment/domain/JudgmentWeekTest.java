package com.jandilog.judgment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

// 주차 식별: 월요일 LocalDate, 한 주는 월 00:00 ~ 일 23:59:59 (기능명세서 5장)
class JudgmentWeekTest {

	@ParameterizedTest
	@ValueSource(strings = {"2026-09-28", "2026-09-29", "2026-09-30", "2026-10-01", "2026-10-02", "2026-10-03",
			"2026-10-04"})
	void 같은_주의_어느_날이든_그_주_월요일로_돌아간다(String date) {
		assertThat(JudgmentWeek.mondayOf(LocalDate.parse(date))).isEqualTo(LocalDate.of(2026, 9, 28));
	}

	@Test
	void 일요일_다음날은_다음_주다() {
		assertThat(JudgmentWeek.mondayOf(LocalDate.of(2026, 10, 5))).isEqualTo(LocalDate.of(2026, 10, 5));
	}

	@Test
	void 연도가_바뀌는_주도_월요일을_찾는다() {
		// 2027-01-01(금) → 2026-12-28(월)
		assertThat(JudgmentWeek.mondayOf(LocalDate.of(2027, 1, 1))).isEqualTo(LocalDate.of(2026, 12, 28));
	}

	@Test
	void 월요일만_월요일이다() {
		assertThat(JudgmentWeek.isMonday(LocalDate.of(2026, 9, 28))).isTrue();
		assertThat(JudgmentWeek.isMonday(LocalDate.of(2026, 9, 27))).isFalse();
		assertThat(JudgmentWeek.isMonday(LocalDate.of(2026, 9, 29))).isFalse();
	}

	@Test
	void 주_종료_시점은_다음_주_월요일_0시이고_직전이_일요일_23시_59분_59초다() {
		LocalDateTime end = JudgmentWeek.endExclusive(LocalDate.of(2026, 9, 28));

		assertThat(end).isEqualTo(LocalDateTime.of(2026, 10, 5, 0, 0));
		assertThat(end.minusSeconds(1)).isEqualTo(LocalDateTime.of(2026, 10, 4, 23, 59, 59));
	}

}
