package com.jandilog.warning.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// V1 penalty_fulfillment 매핑. 되돌릴 수 없는 고정점이라 수정·삭제 메서드를 두지 않는다.
// 가장 최근 fulfilled_at이 경고 재계산의 기준선이다 (DB명세서 1-11, E-58)
@Entity
@Table(name = "penalty_fulfillment")
public class PenaltyFulfillment {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "member_id", nullable = false, updatable = false)
	private long memberId;

	@Column(name = "fulfilled_at", nullable = false, updatable = false)
	private LocalDateTime fulfilledAt;

	@Column(name = "admin_id", nullable = false, updatable = false)
	private long adminId;

	// 경고 3회에 도달했던 주
	@Column(name = "reached_week", nullable = false, updatable = false)
	private LocalDate reachedWeek;

	protected PenaltyFulfillment() {
	}

	public static PenaltyFulfillment of(long memberId, LocalDateTime fulfilledAt, long adminId,
			LocalDate reachedWeek) {
		PenaltyFulfillment fulfillment = new PenaltyFulfillment();
		fulfillment.memberId = memberId;
		fulfillment.fulfilledAt = fulfilledAt;
		fulfillment.adminId = adminId;
		fulfillment.reachedWeek = reachedWeek;
		return fulfillment;
	}

	public Long getId() {
		return id;
	}

	public long getMemberId() {
		return memberId;
	}

	public LocalDateTime getFulfilledAt() {
		return fulfilledAt;
	}

	public long getAdminId() {
		return adminId;
	}

	public LocalDate getReachedWeek() {
		return reachedWeek;
	}

}
