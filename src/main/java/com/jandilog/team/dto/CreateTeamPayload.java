package com.jandilog.team.dto;

// 팀 생성 결과. 초대코드는 이때 한 번 보여주고, 이후에는 팀장이 teamInviteCode로 다시 확인한다
public record CreateTeamPayload(TeamResponse team, String inviteCode) {

	@Override
	public String toString() {
		return "CreateTeamPayload[team=" + team + ", inviteCode=****]";
	}

}
