package com.jandilog.admin.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// 되돌릴 수 없는 관리자 행동 기록. 조회 화면은 만들지 않는다 (기능명세서 9장, DB명세서 1-16)
@Entity
@Table(name = "admin_action_log")
public class AdminActionLog {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "admin_id", nullable = false, updatable = false)
	private long adminId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false)
	private AdminActionType action;

	@Column(name = "target_type", nullable = false, updatable = false, length = 30)
	private String targetType;

	@Column(name = "target_id", nullable = false, updatable = false, length = 40)
	private String targetId;

	@Column(updatable = false, length = 500)
	private String reason;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	protected AdminActionLog() {
	}

	public static AdminActionLog of(long adminId, AdminActionType action, String targetType, String targetId,
			String reason, LocalDateTime createdAt) {
		AdminActionLog log = new AdminActionLog();
		log.adminId = adminId;
		log.action = action;
		log.targetType = targetType;
		log.targetId = targetId;
		log.reason = reason;
		log.createdAt = createdAt;
		return log;
	}

	public Long getId() {
		return id;
	}

	public long getAdminId() {
		return adminId;
	}

	public AdminActionType getAction() {
		return action;
	}

	public String getTargetType() {
		return targetType;
	}

	public String getTargetId() {
		return targetId;
	}

	public String getReason() {
		return reason;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

}
