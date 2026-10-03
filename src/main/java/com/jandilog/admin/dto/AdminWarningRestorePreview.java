package com.jandilog.admin.dto;

import java.util.List;

import com.jandilog.member.dto.MemberBrief;
import com.jandilog.warning.dto.RecalcImpact;

// 경고 복구 미리보기 (E-42 · E-43 · E-59). 계산만 하고 아무것도 바꾸지 않는다.
// impact.entersPenalty면 "다시 벌칙 대상이 됩니다", impact.recordOnly면 "기록만 되살립니다", teamDeleted면 "삭제된 팀으로 표시됩니다"
public record AdminWarningRestorePreview(
		Long warningId,
		MemberBrief member,
		String weekStart,
		List<AdminWarningTeam> restoreTeams,
		boolean teamDeleted,
		RecalcImpact impact) {
}
