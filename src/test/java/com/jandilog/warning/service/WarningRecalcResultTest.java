package com.jandilog.warning.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.jandilog.warning.domain.WarningRecalcResult;

// 벌칙 도달 주 = 살아 있는 세 번째 경고의 주 (penalty_fulfillment.reached_week)
class WarningRecalcResultTest {

	@Test
	@DisplayName("벌칙 대상이면 세 번째 경고의 주가 도달 주이고 4개째 경고가 쌓여도 바뀌지 않는다")
	void reachedWeekIsThirdWarning() {
		List<LocalDate> weeks = List.of(LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 17));
		var three = new WarningRecalcResult.Calculated(3, 0, true, weeks, List.of(), List.of());
		assertThat(three.penaltyReachedWeek()).isEqualTo(LocalDate.of(2026, 8, 17));

		List<LocalDate> four = List.of(weeks.get(0), weeks.get(1), weeks.get(2), LocalDate.of(2026, 8, 24));
		var withFourth = new WarningRecalcResult.Calculated(4, 0, true, four, List.of(), List.of());
		assertThat(withFourth.penaltyReachedWeek()).isEqualTo(LocalDate.of(2026, 8, 17));
	}

	@Test
	@DisplayName("벌칙 대상이 아니면 도달 주가 없다")
	void noReachedWeekWhenNotTarget() {
		var two = new WarningRecalcResult.Calculated(2, 0, false,
				List.of(LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 10)), List.of(), List.of());
		assertThat(two.penaltyReachedWeek()).isNull();
	}

}
