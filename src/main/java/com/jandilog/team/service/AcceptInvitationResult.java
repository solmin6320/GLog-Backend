package com.jandilog.team.service;

import com.jandilog.common.exception.ErrorCode;
import com.jandilog.team.dto.TeamResponse;

// 초대 수락 결과. 이미 참가했거나(E-17) 팀이 없어진(E-18) 경우는 초대를 정리한 변경을 커밋해야 해서
// 서비스는 예외 대신 이 값으로 돌려주고, 커밋이 끝난 뒤 컨트롤러가 오류로 바꾼다
public record AcceptInvitationResult(TeamResponse team, ErrorCode error) {

	static AcceptInvitationResult joined(TeamResponse team) {
		return new AcceptInvitationResult(team, null);
	}

	static AcceptInvitationResult failed(ErrorCode error) {
		return new AcceptInvitationResult(null, error);
	}

}
