package com.jandilog.judgment.domain;

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

// V1 weekly_judgment 매핑. (member_id, week_start) UNIQUE가 재실행 멱등의 근거다 (E-35)
@Entity
@Table(name = "weekly_judgment")
public class WeeklyJudgment {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "member_id", nullable = false, updatable = false)
	private long memberId;

	@Column(name = "week_start", nullable = false, updatable = false)
	private LocalDate weekStart;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private JudgmentStatus status;

	@Enumerated(EnumType.STRING)
	@Column(name = "skip_reason")
	private SkipReason skipReason;

	@Enumerated(EnumType.STRING)
	@Column(name = "hold_reason")
	private HoldReason holdReason;

	@Column(name = "retry_count", nullable = false)
	private int retryCount;

	@Column(name = "verified_days")
	private Integer verifiedDays;

	@Column(name = "record_count")
	private Integer recordCount;

	// 관리자 정정을 거쳤는가
	@Column(nullable = false)
	private boolean corrected;

	@Column(name = "judged_at")
	private LocalDateTime judgedAt;

	protected WeeklyJudgment() {
	}

	public static WeeklyJudgment of(long memberId, LocalDate weekStart, JudgmentResult result) {
		WeeklyJudgment judgment = new WeeklyJudgment();
		judgment.memberId = memberId;
		judgment.weekStart = weekStart;
		judgment.apply(result);
		return judgment;
	}

	// 계산 결과로 상태를 덮어쓴다. 보류 주를 재실행해 확정할 때도 쓴다
	public void apply(JudgmentResult result) {
		this.status = result.status();
		this.skipReason = result.skipReason();
		this.holdReason = result.holdReason();
		this.verifiedDays = result.verifiedDays();
		this.recordCount = result.recordCount();
		this.judgedAt = result.judgedAt();
	}

	// 자동 재시도 횟수. 상한(Q-07: 3회)과 간격은 호출한 쪽이 정한다
	public void increaseRetryCount() {
		this.retryCount++;
	}

	public boolean isConfirmed() {
		return status.isConfirmed();
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

	public JudgmentStatus getStatus() {
		return status;
	}

	public SkipReason getSkipReason() {
		return skipReason;
	}

	public HoldReason getHoldReason() {
		return holdReason;
	}

	public int getRetryCount() {
		return retryCount;
	}

	public Integer getVerifiedDays() {
		return verifiedDays;
	}

	public Integer getRecordCount() {
		return recordCount;
	}

	public boolean isCorrected() {
		return corrected;
	}

	public LocalDateTime getJudgedAt() {
		return judgedAt;
	}

}
