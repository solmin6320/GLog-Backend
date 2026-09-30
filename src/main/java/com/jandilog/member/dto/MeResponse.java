package com.jandilog.member.dto;

import com.jandilog.member.domain.Member;
import com.jandilog.member.domain.MemberRole;
import com.jandilog.member.domain.MemberStatus;

// GraphQL Me 타입. AU-03 승인 대기 화면이 status와 githubLogin을 쓴다
public record MeResponse(
		Long id,
		String githubLogin,
		String nickname,
		String profileImageUrl,
		MemberStatus status,
		MemberRole role) {

	public static MeResponse from(Member member) {
		return new MeResponse(member.getId(), member.getGithubLogin(), member.getNickname(),
				member.getProfileImageUrl(), member.getStatus(), member.getRole());
	}

}
