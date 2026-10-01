package com.jandilog.profile.dto;

import com.jandilog.member.domain.Member;

// 프로필 화면의 회원 기본 정보 (PR-01 ①~⑤). 게시글 수 · 댓글 수 · 잔디 · 경고 · 소속 팀은 요청한 필드만 따로 채운다.
// profileImageUrl이 null이면 기본 이미지다. isMine은 수정 · 관리자 메뉴 버튼 노출 판단에 쓴다
public record ProfileView(Long id, String nickname, String githubLogin, String profileImageUrl, boolean isMine) {

	public static ProfileView of(Member member, long viewerId) {
		return new ProfileView(member.getId(), member.getNickname(), member.getGithubLogin(),
				member.getProfileImageUrl(), member.getId() == viewerId);
	}

}
