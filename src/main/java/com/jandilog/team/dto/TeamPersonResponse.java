package com.jandilog.team.dto;

import com.jandilog.member.domain.Member;

// 팀장·초대한 사람·초대받은 사람처럼 이름만 보여주는 회원 정보
public record TeamPersonResponse(Long id, String nickname, String githubLogin, String profileImageUrl) {

	public static TeamPersonResponse from(Member member) {
		return new TeamPersonResponse(member.getId(), member.getNickname(), member.getGithubLogin(),
				member.getProfileImageUrl());
	}

}
