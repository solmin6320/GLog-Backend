package com.jandilog.admin.dto;

import com.jandilog.member.dto.MemberBrief;

// 게시물 관리 목록 한 행 (`[종류] 제목 · 작성자 · 날짜`). 팀 정보는 싣지 않는다 (비공개 팀 이름이 새는 경로를 만들지 않는다).
// 글이면 id = postId이고 content는 null, 댓글이면 title에 글 제목, content에 댓글 내용(앞 100자)을 담는다
public record AdminPostItem(
		AdminPostKind kind,
		String id,
		String postId,
		String title,
		String content,
		MemberBrief author,
		String createdAt) {
}
