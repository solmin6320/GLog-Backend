package com.jandilog.judgment.dto;

import com.jandilog.judgment.domain.HoldReason;
import com.jandilog.judgment.domain.JudgmentStatus;
import com.jandilog.judgment.domain.SkipReason;

// 지난 판정 표 한 행 (AC-01 ⑤, PR-01 ⑩과 같은 수치)
public record JudgmentHistoryItem(
		String weekStart,
		String weekEnd,
		JudgmentStatus status,
		SkipReason skipReason,
		HoldReason holdReason,
		Integer verifiedDays,
		Integer recordCount,
		boolean corrected) {
}
