package com.jandilog.team.dto;

// TM-06 보낸 초대 한 건(대기 중)
public record SentInvitationResponse(Long id, TeamPersonResponse invitee, String createdAt) {
}
