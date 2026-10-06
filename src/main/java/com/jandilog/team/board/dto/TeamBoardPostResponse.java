package com.jandilog.team.board.dto;

import com.jandilog.post.domain.PostType;
import com.jandilog.post.dto.BoardAuthor;

// GraphQL TeamBoardPost 타입. 이 팀에 연결된 글 한 줄 (말머리·제목·작성자·날짜). isRecord는 필수 항목을 모두 채운 기록글인지
public record TeamBoardPostResponse(String id, PostType type, String title, BoardAuthor author, String createdAt,
		boolean isRecord) {
}
