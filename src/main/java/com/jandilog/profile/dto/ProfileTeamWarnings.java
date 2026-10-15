package com.jandilog.profile.dto;

import java.util.List;

// 프로필 팀별 경고 (PR-01 ⑨). 경고 1개에 팀이 여러 개 붙어 중복 집계라 합계가 누적 경고 수와 다른 것이 정상이다.
// 판정 보류가 남은 회원은 계산하지 않아(Q-03) onHold만 true이고 목록은 비어 있다
public record ProfileTeamWarnings(boolean onHold, List<ProfileTeamWarning> teams) {

	public static ProfileTeamWarnings held() {
		return new ProfileTeamWarnings(true, List.of());
	}

}
