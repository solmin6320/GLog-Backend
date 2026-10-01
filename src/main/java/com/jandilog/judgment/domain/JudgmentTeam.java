package com.jandilog.judgment.domain;

import java.io.Serializable;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

// V1 judgment_team 매핑. 판정 시점 소속 스냅샷이라 한 번 넣고 바꾸지 않는다 (DB명세서 1-7)
@Entity
@Table(name = "judgment_team")
@IdClass(JudgmentTeam.Key.class)
public class JudgmentTeam {

	@Id
	@Column(name = "judgment_id", nullable = false, updatable = false)
	private long judgmentId;

	@Id
	@Column(name = "team_id", nullable = false, updatable = false)
	private long teamId;

	protected JudgmentTeam() {
	}

	public static JudgmentTeam of(long judgmentId, long teamId) {
		JudgmentTeam team = new JudgmentTeam();
		team.judgmentId = judgmentId;
		team.teamId = teamId;
		return team;
	}

	public long getJudgmentId() {
		return judgmentId;
	}

	public long getTeamId() {
		return teamId;
	}

	public static class Key implements Serializable {

		private long judgmentId;
		private long teamId;

		public Key() {
		}

		public Key(long judgmentId, long teamId) {
			this.judgmentId = judgmentId;
			this.teamId = teamId;
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof Key other && judgmentId == other.judgmentId && teamId == other.teamId;
		}

		@Override
		public int hashCode() {
			return Objects.hash(judgmentId, teamId);
		}

	}

}
