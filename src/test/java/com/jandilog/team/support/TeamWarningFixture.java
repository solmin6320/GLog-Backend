package com.jandilog.team.support;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;

// 팀 변동이 경고에 주는 영향을 보기 위한 경고·팀 카테고리 직접 삽입과 조회. 정리는 TeamIntegrationTest가 맡는다
public final class TeamWarningFixture {

	public record WarningRow(long id, LocalDateTime deletedAt, String deleteReason) {
		public boolean alive() {
			return deletedAt == null;
		}
	}

	public record CategoryRow(long teamId, LocalDateTime removedAt) {
		public boolean removed() {
			return removedAt != null;
		}
	}

	private final JdbcTemplate jdbc;

	public TeamWarningFixture(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	// 한 주 미달로 받은 경고 1개와 그 팀 카테고리들
	public long warning(long memberId, LocalDate weekStart, long... teamIds) {
		var keys = new GeneratedKeyHolder();
		jdbc.update(connection -> {
			PreparedStatement ps = connection.prepareStatement(
					"insert into warning (member_id, week_start, created_at) values (?, ?, ?)",
					Statement.RETURN_GENERATED_KEYS);
			ps.setLong(1, memberId);
			ps.setObject(2, weekStart);
			ps.setObject(3, weekStart.plusDays(7).atTime(6, 0));
			return ps;
		}, keys);
		long warningId = keys.getKey().longValue();
		for (long teamId : teamIds) {
			jdbc.update("insert into warning_team (warning_id, team_id) values (?, ?)", warningId, teamId);
		}
		return warningId;
	}

	public WarningRow warning(long warningId) {
		return jdbc.queryForObject("select id, deleted_at, delete_reason from warning where id = ?",
				(rs, i) -> new WarningRow(rs.getLong("id"), rs.getObject("deleted_at", LocalDateTime.class),
						rs.getString("delete_reason")), warningId);
	}

	public List<CategoryRow> categories(long warningId) {
		return jdbc.query("select team_id, removed_at from warning_team where warning_id = ? order by team_id",
				(rs, i) -> new CategoryRow(rs.getLong("team_id"), rs.getObject("removed_at", LocalDateTime.class)),
				warningId);
	}

	public CategoryRow category(long warningId, long teamId) {
		return categories(warningId).stream().filter(c -> c.teamId() == teamId).findFirst().orElseThrow();
	}

	// 살아 있는 경고 수 (프로필·판정에 보이는 것)
	public long aliveCount(long memberId) {
		Long count = jdbc.queryForObject("select count(*) from warning where member_id = ? and deleted_at is null",
				Long.class, memberId);
		return count == null ? 0 : count;
	}

}
