package com.jandilog.member.dto;

import com.jandilog.member.domain.Member;

// 관리자 화면 목록에 붙는 회원 표시 정보 (이름 · GitHub 아이디)
public record MemberBrief(Long id, String nickname, String githubLogin) {

	public static MemberBrief from(Member member) {
		return new MemberBrief(member.getId(), member.getNickname(), member.getGithubLogin());
	}

}
