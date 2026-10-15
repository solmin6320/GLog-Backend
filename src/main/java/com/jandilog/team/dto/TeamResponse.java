package com.jandilog.team.dto;

import java.time.LocalDateTime;

import com.jandilog.team.domain.Team;

// GraphQL Team 타입. 소속 팀원이 보는 화면이라 비공개 팀도 실제 이름이다. 초대코드는 담지 않는다(팀장 전용 조회로만)
public record TeamResponse(
		Long id,
		String name,
		String description,
		boolean isPublic,
		TeamPersonResponse leader,
		boolean isLeader,
		int memberCount,
		String joinedAt,
		String createdAt) {

	public static TeamResponse of(Team team, TeamPersonResponse leader, long viewerId, int memberCount,
			LocalDateTime joinedAt) {
		return new TeamResponse(team.getId(), team.getName(), team.getDescription(), team.isPublic(), leader,
				team.isLeader(viewerId), memberCount, TeamTimes.format(joinedAt), TeamTimes.format(team.getCreatedAt()));
	}

}
