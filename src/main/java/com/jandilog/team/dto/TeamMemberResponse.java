package com.jandilog.team.dto;

import java.time.LocalDateTime;

import com.jandilog.member.domain.Member;

// 팀원 한 명. 이 팀에 참가한 시각은 현재 소속 기준이다
public record TeamMemberResponse(Long id, String nickname, String githubLogin, String profileImageUrl,
		boolean isLeader, String joinedAt) {

	public static TeamMemberResponse of(Member member, boolean leader, LocalDateTime joinedAt) {
		return new TeamMemberResponse(member.getId(), member.getNickname(), member.getGithubLogin(),
				member.getProfileImageUrl(), leader, TeamTimes.format(joinedAt));
	}

}
