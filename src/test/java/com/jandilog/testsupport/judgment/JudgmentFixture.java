package com.jandilog.testsupport.judgment;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;

// 판정 테스트가 만든 회원·팀·Redis 키만 추적해서 끝에 지운다. 공유 DB의 다른 데이터는 건드리지 않는다
public class JudgmentFixture {

	public record JudgmentRow(long id, String status, String skipReason, String holdReason, int retryCount,
			Integer verifiedDays, Integer recordCount, boolean corrected, LocalDateTime judgedAt) {
	}

	public record DayRow(LocalDate day, boolean hasGrass, boolean hasRecord) {
	}

	public record WarningRow(long id, LocalDate weekStart, LocalDateTime deletedAt, String deleteReason,
			LocalDateTime createdAt) {
	}

	private final JdbcTemplate jdbc;
	private final StringRedisTemplate redis;
	// MemberFixture(8e12 대)와 겹치지 않는 구간에서 시작하고 실행마다 무작위로 옮긴다
	private final AtomicLong nextGithubId = new AtomicLong(
			6_000_000_000_000L + ThreadLocalRandom.current().nextLong(0, 900_000_000L) * 1_000L);
	private final AtomicLong nextRedisMemberId = new AtomicLong(
			7_000_000_000_000L + ThreadLocalRandom.current().nextLong(0, 900_000_000L) * 1_000L);
	private final String tag = Long.toString(ThreadLocalRandom.current().nextLong(36L * 36 * 36 * 36 * 36,
			36L * 36 * 36 * 36 * 36 * 36), 36);
	private final AtomicLong sequence = new AtomicLong();
	private final List<Long> memberIds = Collections.synchronizedList(new ArrayList<>());
	private final List<Long> teamIds = Collections.synchronizedList(new ArrayList<>());
	private final List<Long> redisMemberIds = Collections.synchronizedList(new ArrayList<>());

	public JudgmentFixture(JdbcTemplate jdbc, StringRedisTemplate redis) {
		this.jdbc = jdbc;
		this.redis = redis;
	}

	// ---------- 만들기 ----------

	public long member() {
		return member(null);
	}

	public long member(LocalDateTime firstTeamJoinedAt) {
		long githubId = nextGithubId.incrementAndGet();
		String login = "j" + tag + "-" + sequence.incrementAndGet();
		var keys = new GeneratedKeyHolder();
		jdbc.update(connection -> {
			PreparedStatement ps = connection.prepareStatement(
					"insert into member (github_id, github_login, nickname, status, role, first_team_joined_at,"
							+ " created_at) values (?, ?, ?, 'ACTIVE', 'MEMBER', ?, ?)",
					Statement.RETURN_GENERATED_KEYS);
			ps.setLong(1, githubId);
			ps.setString(2, login);
			ps.setString(3, login);
			ps.setObject(4, firstTeamJoinedAt);
			ps.setObject(5, LocalDateTime.of(2026, 1, 2, 3, 4, 5));
			return ps;
		}, keys);
		long id = keys.getKey().longValue();
		memberIds.add(id);
		return id;
	}

	public String loginOf(long memberId) {
		return jdbc.queryForObject("select github_login from member where id = ?", String.class, memberId);
	}

	// 팀장이 leaderId인 팀 하나
	public long team(long leaderId) {
		String name = "jt-" + tag + "-" + sequence.incrementAndGet();
		var keys = new GeneratedKeyHolder();
		jdbc.update(connection -> {
			PreparedStatement ps = connection.prepareStatement(
					"insert into team (name, leader_id, invite_code, is_public, created_at) values (?, ?, ?, true, ?)",
					Statement.RETURN_GENERATED_KEYS);
			ps.setString(1, name);
			ps.setLong(2, leaderId);
			ps.setString(3, "code-" + tag + "-" + sequence.incrementAndGet());
			ps.setObject(4, LocalDateTime.of(2026, 1, 2, 3, 4, 5));
			return ps;
		}, keys);
		long id = keys.getKey().longValue();
		teamIds.add(id);
		return id;
	}

	public Set<Long> teams(long leaderId, int count) {
		Set<Long> ids = new TreeSet<>();
		for (int i = 0; i < count; i++) {
			ids.add(team(leaderId));
		}
		return ids;
	}

	// DB에 없는 회원 id. Redis 잔디 캐시 테스트용이며 끝에 grass:{id}:* 키를 지운다
	public long redisMemberId() {
		long id = nextRedisMemberId.incrementAndGet();
		redisMemberIds.add(id);
		return id;
	}

	// ---------- 읽기 ----------

	public Optional<JudgmentRow> findJudgment(long memberId, LocalDate weekStart) {
		return jdbc.query("select * from weekly_judgment where member_id = ? and week_start = ?", (rs, i) -> {
			int verified = rs.getInt("verified_days");
			Integer verifiedDays = rs.wasNull() ? null : verified;
			int record = rs.getInt("record_count");
			Integer recordCount = rs.wasNull() ? null : record;
			return new JudgmentRow(rs.getLong("id"), rs.getString("status"), rs.getString("skip_reason"),
					rs.getString("hold_reason"), rs.getInt("retry_count"), verifiedDays, recordCount,
					rs.getBoolean("corrected"), rs.getObject("judged_at", LocalDateTime.class));
		}, memberId, weekStart).stream().findFirst();
	}

	public int countJudgments(long memberId) {
		return count("select count(*) from weekly_judgment where member_id = ?", memberId);
	}

	public List<DayRow> days(long judgmentId) {
		return jdbc.query("select day, has_grass, has_record from judgment_day where judgment_id = ? order by day",
				(rs, i) -> new DayRow(rs.getObject("day", LocalDate.class), rs.getBoolean("has_grass"),
						rs.getBoolean("has_record")),
				judgmentId);
	}

	public Set<Long> judgmentTeamIds(long judgmentId) {
		return new TreeSet<>(jdbc.query("select team_id from judgment_team where judgment_id = ?",
				(rs, i) -> rs.getLong("team_id"), judgmentId));
	}

	public List<WarningRow> warnings(long memberId) {
		return jdbc.query("select * from warning where member_id = ? order by week_start", (rs, i) -> new WarningRow(
				rs.getLong("id"), rs.getObject("week_start", LocalDate.class),
				rs.getObject("deleted_at", LocalDateTime.class), rs.getString("delete_reason"),
				rs.getObject("created_at", LocalDateTime.class)), memberId);
	}

	public Set<Long> warningTeamIds(long warningId) {
		return new TreeSet<>(jdbc.query("select team_id from warning_team where warning_id = ?",
				(rs, i) -> rs.getLong("team_id"), warningId));
	}

	public int countRemovedWarningTeams(long warningId) {
		return count("select count(*) from warning_team where warning_id = ? and removed_at is not null", warningId);
	}

	private int count(String sql, Object arg) {
		Integer value = jdbc.queryForObject(sql, Integer.class, arg);
		return value == null ? 0 : value;
	}

	// ---------- 정리 ----------

	// 자식 테이블부터 내가 만든 회원·팀 것만 지운다
	public void cleanup() {
		List<Long> members;
		List<Long> teams;
		synchronized (memberIds) {
			members = new ArrayList<>(memberIds);
			memberIds.clear();
		}
		synchronized (teamIds) {
			teams = new ArrayList<>(teamIds);
			teamIds.clear();
		}
		for (Long memberId : members) {
			jdbc.update("delete from judgment_day where judgment_id in (select id from weekly_judgment where member_id = ?)",
					memberId);
			jdbc.update("delete from judgment_team where judgment_id in (select id from weekly_judgment where member_id = ?)",
					memberId);
			jdbc.update("delete from weekly_judgment where member_id = ?", memberId);
			jdbc.update("delete from warning_team where warning_id in (select id from warning where member_id = ?)",
					memberId);
			jdbc.update("delete from warning where member_id = ?", memberId);
			jdbc.update("delete from penalty_fulfillment where member_id = ?", memberId);
		}
		for (Long teamId : teams) {
			jdbc.update("delete from judgment_team where team_id = ?", teamId);
			jdbc.update("delete from warning_team where team_id = ?", teamId);
			jdbc.update("delete from team where id = ?", teamId);
		}
		for (Long memberId : members) {
			jdbc.update("delete from member where id = ?", memberId);
		}
		List<Long> redisIds;
		synchronized (redisMemberIds) {
			redisIds = new ArrayList<>(redisMemberIds);
			redisMemberIds.clear();
		}
		for (Long id : redisIds) {
			var keys = redis.keys("grass:" + id + ":*");
			if (keys != null && !keys.isEmpty()) {
				redis.delete(keys);
			}
		}
		// DB 회원 id로 쓴 잔디 캐시도 함께 지운다
		for (Long id : members) {
			var keys = redis.keys("grass:" + id + ":*");
			if (keys != null && !keys.isEmpty()) {
				redis.delete(keys);
			}
		}
	}

}
