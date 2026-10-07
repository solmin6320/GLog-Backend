package com.jandilog.exemption.dto;

import com.jandilog.judgment.domain.JudgmentStatus;
import com.jandilog.member.dto.MemberBrief;
import com.jandilog.warning.domain.WarningRecalcResult;

// 정정을 반영한 뒤의 판정과 그 회원의 경고 상태. 목록을 다시 부르지 않고 그 행만 갱신하는 데 쓴다.
// 보류 주가 남은 회원은 경고를 계산하지 않으므로(Q-03) warningCount가 null이다
public record CorrectedJudgment(
		Long judgmentId,
		MemberBrief member,
		String weekStart,
		JudgmentStatus status,
		boolean onHold,
		Integer warningCount,
		boolean penaltyTarget) {

	public static CorrectedJudgment of(Long judgmentId, MemberBrief member, String weekStart, JudgmentStatus status,
			WarningRecalcResult recalculated) {
		if (recalculated instanceof WarningRecalcResult.Calculated calculated) {
			return new CorrectedJudgment(judgmentId, member, weekStart, status, false, calculated.warningCount(),
					calculated.penaltyTarget());
		}
		return new CorrectedJudgment(judgmentId, member, weekStart, status, true, null, false);
	}

}
