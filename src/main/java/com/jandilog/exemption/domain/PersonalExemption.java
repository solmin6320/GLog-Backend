package com.jandilog.exemption.domain;

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

// V2 personal_exemption 매핑. 팀장이 요청하고 관리자가 승인·거절한다. 승인되면 회원 전체의 그 주 판정이 빠진다 (기능명세서 7장, DB명세서 1-13)
@Entity
@Table(name = "personal_exemption")
public class PersonalExemption {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	// 면제 대상
	@Column(name = "member_id", nullable = false, updatable = false)
	private long memberId;

	// 요청한 팀장
	@Column(name = "requested_by", nullable = false, updatable = false)
	private long requestedBy;

	@Column(name = "week_start", nullable = false, updatable = false)
	private LocalDate weekStart;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private PersonalExemptionStatus status;

	@Column(nullable = false, updatable = false, length = 500)
	private String reason;

	@Column(name = "reject_reason", length = 500)
	private String rejectReason;

	@Column(name = "responded_at")
	private LocalDateTime respondedAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	protected PersonalExemption() {
	}

	public static PersonalExemption request(long memberId, long requestedBy, LocalDate weekStart, String reason,
			LocalDateTime now) {
		PersonalExemption exemption = new PersonalExemption();
		exemption.memberId = memberId;
		exemption.requestedBy = requestedBy;
		exemption.weekStart = weekStart;
		exemption.status = PersonalExemptionStatus.PENDING;
		exemption.reason = reason;
		exemption.createdAt = now;
		return exemption;
	}

	public boolean isPending() {
		return status == PersonalExemptionStatus.PENDING;
	}

	public void approve(LocalDateTime now) {
		this.status = PersonalExemptionStatus.APPROVED;
		this.respondedAt = now;
	}

	// 거절 사유 입력 (E-48, 제안)
	public void reject(String rejectReason, LocalDateTime now) {
		this.status = PersonalExemptionStatus.REJECTED;
		this.rejectReason = rejectReason;
		this.respondedAt = now;
	}

	public Long getId() {
		return id;
	}

	public long getMemberId() {
		return memberId;
	}

	public long getRequestedBy() {
		return requestedBy;
	}

	public LocalDate getWeekStart() {
		return weekStart;
	}

	public PersonalExemptionStatus getStatus() {
		return status;
	}

	public String getReason() {
		return reason;
	}

	public String getRejectReason() {
		return rejectReason;
	}

	public LocalDateTime getRespondedAt() {
		return respondedAt;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

}
