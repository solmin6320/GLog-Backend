package com.jandilog.post.dto;

import com.jandilog.post.domain.Comment;

// GraphQL Comment 타입. 작성자 정보는 @BatchMapping으로 채운다. 시각은 KST ISO-8601
public record CommentResponse(
		String id,
		String postId,
		long authorId,
		String content,
		String createdAt,
		String updatedAt) {

	public static CommentResponse from(Comment comment) {
		return new CommentResponse(comment.getId().toHexString(), comment.getPostId().toHexString(),
				comment.getAuthorId(), comment.getContent(), KstFormat.of(comment.getCreatedAt()),
				KstFormat.of(comment.getUpdatedAt()));
	}

}
