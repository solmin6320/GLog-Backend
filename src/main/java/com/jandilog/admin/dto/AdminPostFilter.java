package com.jandilog.admin.dto;

// 게시물 관리 목록의 종류 필터 (AD-01 게시물 관리 ⑤: 글 + 댓글 / 글만 / 댓글만)
public enum AdminPostFilter {
	ALL,
	POSTS,
	COMMENTS;

	public boolean includesPosts() {
		return this != COMMENTS;
	}

	public boolean includesComments() {
		return this != POSTS;
	}
}
