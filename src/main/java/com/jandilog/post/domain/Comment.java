package com.jandilog.post.domain;

import java.time.Instant;

import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

// MongoDB comments 문서 (DB명세서 3-2). 1단계 댓글이라 parentId·teamId가 없다. 삭제는 deletedAt만 채운다
@Document("comments")
public class Comment {

	@Id
	private ObjectId id;

	private ObjectId postId;

	private long authorId;

	private String content;

	private Instant createdAt;

	private Instant updatedAt;

	private Instant deletedAt;

	protected Comment() {
	}

	public static Comment create(ObjectId id, ObjectId postId, long authorId, String content, Instant now) {
		Comment comment = new Comment();
		comment.id = id;
		comment.postId = postId;
		comment.authorId = authorId;
		comment.content = content;
		comment.createdAt = now;
		comment.updatedAt = now;
		return comment;
	}

	public ObjectId getId() {
		return id;
	}

	public ObjectId getPostId() {
		return postId;
	}

	public long getAuthorId() {
		return authorId;
	}

	public String getContent() {
		return content;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public Instant getDeletedAt() {
		return deletedAt;
	}

}
