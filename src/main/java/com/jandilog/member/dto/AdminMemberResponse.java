package com.jandilog.member.dto;

import java.time.format.DateTimeFormatter;

import com.jandilog.member.domain.Member;
import com.jandilog.member.domain.MemberRole;
import com.jandilog.member.domain.MemberStatus;

// 가입 승인·관리자 지정 행. 판단에 필요한 것만 담는다 (팀·활동 이력 없음). createdAt은 KST ISO-8601
public record AdminMemberResponse(
		Long id,
		String githubLogin,
		String nickname,
		MemberStatus status,
		MemberRole role,
		String createdAt) {

	public static AdminMemberResponse from(Member member) {
		return new AdminMemberResponse(member.getId(), member.getGithubLogin(), member.getNickname(),
				member.getStatus(), member.getRole(), DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(member.getCreatedAt()));
	}

}
