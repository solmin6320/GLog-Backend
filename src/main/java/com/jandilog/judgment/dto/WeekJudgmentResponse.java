package com.jandilog.judgment.dto;

import java.time.format.DateTimeFormatter;

import com.jandilog.judgment.domain.HoldReason;
import com.jandilog.judgment.domain.JudgmentStatus;
import com.jandilog.judgment.domain.SkipReason;
import com.jandilog.judgment.domain.WeeklyJudgment;

// 한 주의 판정 결과. 제외·면제·보류 주는 인증일·기록글이 null이다 (화면설계서 AC-01 "—")
public record WeekJudgmentResponse(
		JudgmentStatus status,
		SkipReason skipReason,
		HoldReason holdReason,
		Integer verifiedDays,
		Integer recordCount,
		boolean corrected,
		String judgedAt) {

	public static WeekJudgmentResponse from(WeeklyJudgment judgment) {
		return new WeekJudgmentResponse(judgment.getStatus(), judgment.getSkipReason(), judgment.getHoldReason(),
				judgment.getVerifiedDays(), judgment.getRecordCount(), judgment.isCorrected(),
				judgment.getJudgedAt() == null ? null : DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(judgment.getJudgedAt()));
	}

}
