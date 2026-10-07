package com.jandilog.admin.dto;

import com.jandilog.member.dto.MemberBrief;
import com.jandilog.warning.domain.WarningRecalcResult;

// 경고 복구를 반영한 뒤 그 회원의 경고 상태. 보류 주가 남아 있으면 경고를 계산하지 않아(Q-03) warningCount가 null이다
public record AdminRestoredWarning(
		Long warningId,
		MemberBrief member,
		String weekStart,
		boolean onHold,
		Integer warningCount,
		boolean penaltyTarget) {

	public static AdminRestoredWarning of(Long warningId, MemberBrief member, String weekStart,
			WarningRecalcResult recalculated) {
		if (recalculated instanceof WarningRecalcResult.Calculated calculated) {
			return new AdminRestoredWarning(warningId, member, weekStart, false, calculated.warningCount(),
					calculated.penaltyTarget());
		}
		return new AdminRestoredWarning(warningId, member, weekStart, true, null, false);
	}

}
