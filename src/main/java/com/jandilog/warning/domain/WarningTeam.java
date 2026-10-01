package com.jandilog.warning.domain;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

// V1 warning_team 매핑. 경고 1개에 팀 여러 개가 붙는 N:M. removed_at이 찍히면 그 팀 카테고리는 빠진 것이다 (DB명세서 1-10)
@Entity
@Table(name = "warning_team")
@IdClass(WarningTeam.Key.class)
public class WarningTeam {

	@Id
	@Column(name = "warning_id", nullable = false, updatable = false)
	private long warningId;

	@Id
	@Column(name = "team_id", nullable = false, updatable = false)
	private long teamId;

	@Column(name = "removed_at")
	private LocalDateTime removedAt;

	protected WarningTeam() {
	}

	public static WarningTeam of(long warningId, long teamId) {
		WarningTeam warningTeam = new WarningTeam();
		warningTeam.warningId = warningId;
		warningTeam.teamId = teamId;
		return warningTeam;
	}

	public boolean isRemoved() {
		return removedAt != null;
	}

	public void remove(LocalDateTime now) {
		this.removedAt = now;
	}

	// 추방 후 아이디 재초대로 복귀했을 때 카테고리를 되살린다 (E-14, Q-02)
	public void restore() {
		this.removedAt = null;
	}

	public long getWarningId() {
		return warningId;
	}

	public long getTeamId() {
		return teamId;
	}

	public LocalDateTime getRemovedAt() {
		return removedAt;
	}

	public static class Key implements Serializable {

		private long warningId;
		private long teamId;

		public Key() {
		}

		public Key(long warningId, long teamId) {
			this.warningId = warningId;
			this.teamId = teamId;
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof Key other && warningId == other.warningId && teamId == other.teamId;
		}

		@Override
		public int hashCode() {
			return Objects.hash(warningId, teamId);
		}

	}

}
