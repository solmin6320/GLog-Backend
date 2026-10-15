package com.jandilog.team.dto;

// TM-04 받은 초대 한 건. 비공개 팀이어도 초대받은 당사자에게는 실제 팀 이름을 보여준다
public record ReceivedInvitationResponse(Long id, Long teamId, String teamName, TeamPersonResponse inviter,
		String createdAt) {
}
