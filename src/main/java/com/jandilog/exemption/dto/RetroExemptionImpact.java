package com.jandilog.exemption.dto;

import com.jandilog.judgment.domain.JudgmentStatus;
import com.jandilog.member.dto.MemberBrief;
import com.jandilog.warning.dto.RecalcImpact;

// 소급 면제가 회원 한 명에게 미치는 영향. currentStatus는 면제로 바뀌기 전 판정(통과·미달)
public record RetroExemptionImpact(MemberBrief member, JudgmentStatus currentStatus, RecalcImpact impact) {
}
