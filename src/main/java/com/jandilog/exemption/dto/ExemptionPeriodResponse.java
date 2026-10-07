package com.jandilog.exemption.dto;

import com.jandilog.common.time.KstFormats;
import com.jandilog.exemption.domain.ExemptionPeriod;

// 면제 기간 한 건. 주 단위라 월요일과 일요일을 함께 내려 "M/D ~ M/D"를 그릴 수 있게 한다
public record ExemptionPeriodResponse(
		Long id,
		String weekStart,
		String weekEnd,
		String reason,
		String createdAt,
		String updatedAt) {

	public static ExemptionPeriodResponse from(ExemptionPeriod period) {
		return new ExemptionPeriodResponse(period.getId(), period.getWeekStart().toString(),
				period.getWeekStart().plusDays(6).toString(), period.getReason(), KstFormats.of(period.getCreatedAt()),
				KstFormats.of(period.getUpdatedAt()));
	}

}
