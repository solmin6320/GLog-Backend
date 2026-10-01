package com.jandilog.notice.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// V2 notice 테이블 매핑 (DB명세서 1-15). 공지는 관리자만 쓰고 댓글이 없다
@Entity
@Table(name = "notice")
public class Notice {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 200)
	private String title;

	@Column(nullable = false, columnDefinition = "TEXT")
	private String content;

	@Column(name = "is_pinned", nullable = false)
	private boolean pinned;

	// 잔디 인정 규칙 공지. 서버가 삭제를 거부한다
	@Column(name = "is_system", nullable = false, updatable = false)
	private boolean system;

	@Column(name = "author_id", nullable = false, updatable = false)
	private long authorId;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@Column(name = "updated_at")
	private LocalDateTime updatedAt;

	protected Notice() {
	}

	public static Notice create(String title, String content, boolean pinned, long authorId, LocalDateTime now) {
		Notice notice = new Notice();
		notice.title = title;
		notice.content = content;
		notice.pinned = pinned;
		notice.authorId = authorId;
		notice.createdAt = now;
		return notice;
	}

	// 잔디 인정 규칙 공지: 고정 + 삭제 불가
	public static Notice createSystem(String title, String content, long authorId, LocalDateTime now) {
		Notice notice = create(title, content, true, authorId, now);
		notice.system = true;
		return notice;
	}

	public void edit(String title, String content, boolean pinned, LocalDateTime now) {
		this.title = title;
		this.content = content;
		this.pinned = pinned;
		this.updatedAt = now;
	}

	public void changePinned(boolean pinned) {
		this.pinned = pinned;
	}

	public Long getId() {
		return id;
	}

	public String getTitle() {
		return title;
	}

	public String getContent() {
		return content;
	}

	public boolean isPinned() {
		return pinned;
	}

	public boolean isSystem() {
		return system;
	}

	public long getAuthorId() {
		return authorId;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public LocalDateTime getUpdatedAt() {
		return updatedAt;
	}

}
