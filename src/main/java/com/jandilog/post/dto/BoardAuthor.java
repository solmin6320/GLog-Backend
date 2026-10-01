package com.jandilog.post.dto;

import com.jandilog.member.domain.Member;

// 글·댓글에 붙는 작성자 표시 정보
public record BoardAuthor(Long id, String nickname, String profileImageUrl) {

	public static BoardAuthor from(Member member) {
		return new BoardAuthor(member.getId(), member.getNickname(), member.getProfileImageUrl());
	}

}
