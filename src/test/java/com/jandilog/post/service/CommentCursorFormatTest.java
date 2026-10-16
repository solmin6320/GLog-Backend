package com.jandilog.post.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

// 댓글 목록 커서 형식: "작성 시각(epoch ms)|id"를 Base64url(패딩 없음)로 감싼 값. 앱이 들고 있던 커서가 계속 통하도록 형식을 고정한다
class CommentCursorFormatTest {

	private static final Instant CREATED_AT = Instant.parse("2026-10-01T00:00:00Z");
	private static final ObjectId ID = new ObjectId("6529f0a1b2c3d4e5f6a7b8c9");
	private static final String ENCODED = "MTc5MDgxMjgwMDAwMHw2NTI5ZjBhMWIyYzNkNGU1ZjZhN2I4Yzk";

	@Test
	void 인코딩_결과가_고정된_문자열과_같다() {
		assertThat(new CommentService.Cursor(CREATED_AT, ID).encode()).isEqualTo(ENCODED);
	}

	@Test
	void 고정된_문자열을_읽으면_같은_시각과_id다() {
		assertThat(CommentService.Cursor.decode(ENCODED)).isEqualTo(new CommentService.Cursor(CREATED_AT, ID));
	}

	@Test
	void 비었으면_첫_페이지다() {
		assertThat(CommentService.Cursor.decode(null)).isNull();
		assertThat(CommentService.Cursor.decode("")).isNull();
	}

}
