package com.jandilog.exemption.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// V1·V2 exemption_period 매핑. 주 단위(월요일)로만 등록하고 전체 회원에게 적용된다 (기능명세서 7장, DB명세서 1-12)
@Entity
@Table(name = "exemption_period")
public class ExemptionPeriod {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "week_start", nullable = false)
	private LocalDate weekStart;

	@Column(nullable = false, length = 200)
	private String reason;

	@Column(name = "created_by", nullable = false, updatable = false)
	private long createdBy;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@Column(name = "updated_at")
	private LocalDateTime updatedAt;

	protected ExemptionPeriod() {
	}

	public static ExemptionPeriod create(LocalDate weekStart, String reason, long adminId, LocalDateTime now) {
		ExemptionPeriod period = new ExemptionPeriod();
		period.weekStart = weekStart;
		period.reason = reason;
		period.createdBy = adminId;
		period.createdAt = now;
		return period;
	}

	// 면제 기간은 수정할 수 있다 (기능명세서 7장)
	public void change(LocalDate weekStart, String reason, LocalDateTime now) {
		this.weekStart = weekStart;
		this.reason = reason;
		this.updatedAt = now;
	}

	public Long getId() {
		return id;
	}

	public LocalDate getWeekStart() {
		return weekStart;
	}

	public String getReason() {
		return reason;
	}

	public long getCreatedBy() {
		return createdBy;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public LocalDateTime getUpdatedAt() {
		return updatedAt;
	}

}
