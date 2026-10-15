package com.jandilog.profile.dto;

import com.jandilog.warning.domain.WarningRecalcResult;

// 프로필의 경고 합계 카드 (PR-01 ⑧). 저장된 카운트가 아니라 판정 이력을 처음부터 다시 훑어 계산한 값이다.
// 보류 주가 남은 회원은 계산하지 않아(Q-03) onHold만 true이고 숫자는 null이다.
// 팀별 경고(⑨)는 ProfileTeamWarnings가 같은 기준(현재 경고 수에 들어가는 경고)으로 센다
public record ProfileWarning(boolean onHold, Integer warningCount, Boolean penaltyTarget) {

	public static ProfileWarning of(WarningRecalcResult result) {
		if (result instanceof WarningRecalcResult.Calculated calculated) {
			return new ProfileWarning(false, calculated.warningCount(), calculated.penaltyTarget());
		}
		return new ProfileWarning(true, null, null);
	}

}
