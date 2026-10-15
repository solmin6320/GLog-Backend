package com.jandilog.admin.dto;

import java.util.List;

import com.jandilog.member.dto.MemberBrief;

// 벌칙 대상 한 명 (AD-01 경고 복구 · 벌칙 ⑤). reachedWeek는 경고가 3개에 도달한 주, teams는 지금 세는 경고에 붙은 팀 전부
public record AdminPenaltyTarget(MemberBrief member, int warningCount, String reachedWeek,
		List<AdminWarningTeam> teams) {
}
