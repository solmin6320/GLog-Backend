package com.jandilog.judgment.domain;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

// V1 judgment_day 매핑. 판정 시점의 일자별 근거 7행. 잔디 캐시는 하루치만 살아 있어서 여기에 남긴다 (DB명세서 1-8)
@Entity
@Table(name = "judgment_day")
@IdClass(JudgmentDay.Key.class)
public class JudgmentDay {

	@Id
	@Column(name = "judgment_id", nullable = false, updatable = false)
	private long judgmentId;

	@Id
	@Column(name = "day", nullable = false, updatable = false)
	private LocalDate day;

	@Column(name = "has_grass", nullable = false)
	private boolean hasGrass;

	@Column(name = "has_record", nullable = false)
	private boolean hasRecord;

	protected JudgmentDay() {
	}

	public static JudgmentDay of(long judgmentId, DayResult result) {
		JudgmentDay day = new JudgmentDay();
		day.judgmentId = judgmentId;
		day.day = result.day();
		day.hasGrass = result.hasGrass();
		day.hasRecord = result.hasRecord();
		return day;
	}

	// 인증일은 잔디 또는 기록글, 둘 다여도 1일
	public boolean isVerified() {
		return hasGrass || hasRecord;
	}

	public long getJudgmentId() {
		return judgmentId;
	}

	public LocalDate getDay() {
		return day;
	}

	public boolean isHasGrass() {
		return hasGrass;
	}

	public boolean isHasRecord() {
		return hasRecord;
	}

	public static class Key implements Serializable {

		private long judgmentId;
		private LocalDate day;

		public Key() {
		}

		public Key(long judgmentId, LocalDate day) {
			this.judgmentId = judgmentId;
			this.day = day;
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof Key other && judgmentId == other.judgmentId && Objects.equals(day, other.day);
		}

		@Override
		public int hashCode() {
			return Objects.hash(judgmentId, day);
		}

	}

}
