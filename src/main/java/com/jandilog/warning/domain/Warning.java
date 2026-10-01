package com.jandilog.warning.domain;

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

// V1 warning 매핑. 사람당 한 주에 하나(UNIQUE)이고 실제로 지우지 않고 deleted_at으로 소프트 삭제한다 (기능명세서 6장)
@Entity
@Table(name = "warning")
public class Warning {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "member_id", nullable = false, updatable = false)
	private long memberId;

	// 어느 주 미달로 받았는가
	@Column(name = "week_start", nullable = false, updatable = false)
	private LocalDate weekStart;

	@Column(name = "deleted_at")
	private LocalDateTime deletedAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "delete_reason")
	private WarningDeleteReason deleteReason;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	protected Warning() {
	}

	public static Warning create(long memberId, LocalDate weekStart, LocalDateTime createdAt) {
		Warning warning = new Warning();
		warning.memberId = memberId;
		warning.weekStart = weekStart;
		warning.createdAt = createdAt;
		return warning;
	}

	public boolean isAlive() {
		return deletedAt == null;
	}

	// 추방·팀 삭제로 카테고리가 모두 빠졌을 때
	public void softDelete(WarningDeleteReason reason, LocalDateTime now) {
		this.deletedAt = now;
		this.deleteReason = reason;
	}

	// 관리자 복구·재참가 복원
	public void restore() {
		this.deletedAt = null;
		this.deleteReason = null;
	}

	public Long getId() {
		return id;
	}

	public long getMemberId() {
		return memberId;
	}

	public LocalDate getWeekStart() {
		return weekStart;
	}

	public LocalDateTime getDeletedAt() {
		return deletedAt;
	}

	public WarningDeleteReason getDeleteReason() {
		return deleteReason;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

}
