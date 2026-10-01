package com.jandilog.exemption.dto;

import com.jandilog.judgment.domain.JudgmentStatus;
import com.jandilog.member.dto.MemberBrief;
import com.jandilog.warning.dto.RecalcImpact;

// 판정 정정 재계산 미리보기 (AD-01 ⑩). 계산만 하고 아무것도 바꾸지 않는다
public record CorrectionPreview(
		Long judgmentId,
		MemberBrief member,
		String weekStart,
		JudgmentStatus currentStatus,
		JudgmentStatus targetStatus,
		RecalcImpact impact) {
}
