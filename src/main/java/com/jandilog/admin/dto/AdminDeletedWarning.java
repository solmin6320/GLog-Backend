package com.jandilog.admin.dto;

import java.util.List;

import com.jandilog.member.dto.MemberBrief;
import com.jandilog.warning.domain.WarningDeleteReason;

// 삭제 표시된 경고 한 건 (AD-01 경고 복구 · 벌칙 ⑦). restoreTeams는 복구하면 되살아나는 카테고리,
// teamDeleted는 그런 팀이 하나도 없어 "삭제된 팀"으로 표시된다는 뜻이다 (E-43)
public record AdminDeletedWarning(
		Long warningId,
		MemberBrief member,
		String weekStart,
		WarningDeleteReason deleteReason,
		String deletedAt,
		List<AdminWarningTeam> restoreTeams,
		boolean teamDeleted) {
}
