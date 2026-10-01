package com.jandilog.post.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// V1 post_index 테이블 매핑. 판정이 MongoDB를 열지 않도록 기록글 여부·작성일만 복제해 둔다 (DB명세서 1-5)
@Entity
@Table(name = "post_index")
public class PostIndex {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "mongo_post_id", nullable = false, updatable = false, length = 24, columnDefinition = "CHAR(24)")
	private String mongoPostId;

	@Column(name = "author_id", nullable = false, updatable = false)
	private long authorId;

	@Column(name = "team_id")
	private Long teamId;

	@Enumerated(EnumType.STRING)
	@Column(name = "post_type", nullable = false, updatable = false)
	private PostType postType;

	@Column(name = "is_record", nullable = false)
	private boolean record;

	@Column(name = "written_date", nullable = false, updatable = false)
	private LocalDate writtenDate;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@Column(name = "deleted_at")
	private LocalDateTime deletedAt;

	protected PostIndex() {
	}

	public static PostIndex of(String mongoPostId, long authorId, Long teamId, PostType postType, boolean record,
			LocalDate writtenDate, LocalDateTime createdAt) {
		PostIndex index = new PostIndex();
		index.mongoPostId = mongoPostId;
		index.authorId = authorId;
		index.teamId = teamId;
		index.postType = postType;
		index.record = record;
		index.writtenDate = writtenDate;
		index.createdAt = createdAt;
		return index;
	}

	// 글을 수정할 때 기록글 여부와 팀 연결만 갱신한다. 작성일은 최초 저장일 그대로 (Q-11)
	public void update(boolean record, Long teamId) {
		this.record = record;
		this.teamId = teamId;
	}

	public void markDeleted(LocalDateTime now) {
		this.deletedAt = now;
	}

	public void clearDeleted() {
		this.deletedAt = null;
	}

	public Long getId() {
		return id;
	}

	public String getMongoPostId() {
		return mongoPostId;
	}

	public long getAuthorId() {
		return authorId;
	}

	public Long getTeamId() {
		return teamId;
	}

	public PostType getPostType() {
		return postType;
	}

	public boolean isRecord() {
		return record;
	}

	public LocalDate getWrittenDate() {
		return writtenDate;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public LocalDateTime getDeletedAt() {
		return deletedAt;
	}

}
