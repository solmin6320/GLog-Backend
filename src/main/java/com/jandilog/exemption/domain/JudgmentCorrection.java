package com.jandilog.exemption.domain;

import java.time.LocalDateTime;

import com.jandilog.judgment.domain.JudgmentStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// V2 judgment_correction 매핑. 정정 이력은 쌓기만 하고 고치지 않는다. 결과는 통과·미달·면제 셋뿐 (기능명세서 7장, DB명세서 1-14)
@Entity
@Table(name = "judgment_correction")
public class JudgmentCorrection {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "judgment_id", nullable = false, updatable = false)
	private long judgmentId;

	@Enumerated(EnumType.STRING)
	@Column(name = "before_status", nullable = false, updatable = false)
	private JudgmentStatus beforeStatus;

	@Enumerated(EnumType.STRING)
	@Column(name = "after_status", nullable = false, updatable = false)
	private JudgmentStatus afterStatus;

	@Column(nullable = false, updatable = false, length = 500)
	private String reason;

	@Column(name = "admin_id", nullable = false, updatable = false)
	private long adminId;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	protected JudgmentCorrection() {
	}

	public static JudgmentCorrection of(long judgmentId, JudgmentStatus beforeStatus, JudgmentStatus afterStatus,
			String reason, long adminId, LocalDateTime createdAt) {
		JudgmentCorrection correction = new JudgmentCorrection();
		correction.judgmentId = judgmentId;
		correction.beforeStatus = beforeStatus;
		correction.afterStatus = afterStatus;
		correction.reason = reason;
		correction.adminId = adminId;
		correction.createdAt = createdAt;
		return correction;
	}

	public Long getId() {
		return id;
	}

	public long getJudgmentId() {
		return judgmentId;
	}

	public JudgmentStatus getBeforeStatus() {
		return beforeStatus;
	}

	public JudgmentStatus getAfterStatus() {
		return afterStatus;
	}

	public String getReason() {
		return reason;
	}

	public long getAdminId() {
		return adminId;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

}
