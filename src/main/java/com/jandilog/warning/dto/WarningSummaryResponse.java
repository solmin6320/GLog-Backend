package com.jandilog.warning.dto;

import java.time.LocalDate;
import java.util.List;

import com.jandilog.warning.domain.WarningRecalcResult;

// 내 경고 요약 (홈 ④, AC-02 ①②). 판정 보류가 남아 있으면 재계산을 미뤄 숫자 없이 onHold만 참이다 (Q-03).
// streak: 지금 연속 통과 수(0~1). 차감하면 0이 되므로 "다음 차감까지 {2-streak}주"로 쓴다. 벌칙 대상이면 쌓지 않아 0이다 (Q-10)
public record WarningSummaryResponse(
		boolean onHold,
		List<String> holdWeeks,
		Integer warningCount,
		Integer streak,
		Boolean penaltyTarget,
		String penaltyReachedWeek) {

	public static WarningSummaryResponse from(WarningRecalcResult result) {
		if (result instanceof WarningRecalcResult.Calculated calculated) {
			LocalDate reached = calculated.penaltyReachedWeek();
			return new WarningSummaryResponse(false, List.of(), calculated.warningCount(), calculated.streak(),
					calculated.penaltyTarget(), reached == null ? null : reached.toString());
		}
		WarningRecalcResult.OnHold onHold = (WarningRecalcResult.OnHold) result;
		return new WarningSummaryResponse(true, onHold.holdWeeks().stream().map(LocalDate::toString).toList(), null,
				null, null, null);
	}

}
