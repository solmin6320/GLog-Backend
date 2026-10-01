package com.jandilog.testsupport.warning;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;

import com.jandilog.judgment.domain.JudgmentStatus;

// 경고 재계산 통합 테스트가 만든 행만 추적해서 끝에 지운다. 공유 DB의 다른 데이터는 건드리지 않는다.
// 자식 테이블부터 지운다: judgment_day, judgment_team, weekly_judgment, warning_team, warning, penalty_fulfillment, team, member
public class WarningFixture {

	private final JdbcTemplate jdbc;
	// 다른 테스트가 쓰는 GitHub id 구간과 겹치지 않도록 큰 값에서 시작하는 무작위 구간
	private final AtomicLong nextGithubId = new AtomicLong(
			5_000_000_000_000_000L + ThreadLocalRandom.current().nextLong(0, 900_000_000L) * 1_000L);
	private final String tag = Long.toString(ThreadLocalRandom.current().nextLong(36L * 36 * 36 * 36 * 36,
			36L * 36 * 36 * 36 * 36 * 36), 36);
	private final AtomicLong sequence = new AtomicLong();
	private final List<Long> memberIds = Collections.synchronizedList(new ArrayList<>());
	private final List<Long> teamIds = Collections.synchronizedList(new ArrayList<>());

	public WarningFixture(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	// ---- 회원·팀 ----

	public long member() {
		return insertMember("MEMBER");
	}

	public long admin() {
		return insertMember("ADMIN");
	}

	private long insertMember(String role) {
		String login = "wr" + tag + "-" + sequence.incrementAndGet();
		long githubId = nextGithubId.incrementAndGet();
		long id = insertReturningId(
				"insert into member (github_id, github_login, nickname, status, role, created_at, approved_at)"
						+ " values (?, ?, ?, 'ACTIVE', ?, ?, ?)",
				githubId, login, login, role, LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 1, 1, 0, 0));
		memberIds.add(id);
		return id;
	}

	public long team(long leaderId) {
		long id = insertReturningId(
				"insert into team (name, leader_id, invite_code, is_public, created_at) values (?, ?, ?, true, ?)",
				"wr-team-" + sequence.incrementAndGet(), leaderId, UUID.randomUUID().toString(),
				LocalDateTime.of(2026, 1, 1, 0, 0));
		teamIds.add(id);
		return id;
	}

	// ---- 판정·경고 ----

	// 표기(WeekScript)대로 판정과 경고 행을 만든다. F는 살아 있는 경고, D는 삭제 표시된 경고(추방)가 붙는다
	public void history(long memberId, String script) {
		for (int i = 0; i < script.length(); i++) {
			char symbol = script.charAt(i);
			LocalDate week = WeekScript.week(i);
			switch (symbol) {
				case '.' -> {
				}
				case 'P' -> judgment(memberId, week, JudgmentStatus.PASS);
				case 'F' -> {
					judgment(memberId, week, JudgmentStatus.FAIL);
					warning(memberId, week, null);
				}
				case 'D' -> {
					judgment(memberId, week, JudgmentStatus.FAIL);
					warning(memberId, week, WeekScript.endOf(i).plusDays(1));
				}
				case 'E' -> judgment(memberId, week, JudgmentStatus.EXEMPT);
				case 'X' -> judgment(memberId, week, JudgmentStatus.EXCLUDED);
				case 'H' -> judgment(memberId, week, JudgmentStatus.HOLD);
				default -> throw new IllegalArgumentException("모르는 표기: " + symbol);
			}
		}
	}

	public long judgment(long memberId, LocalDate week, JudgmentStatus status) {
		boolean confirmed = status != JudgmentStatus.HOLD;
		String skipReason = switch (status) {
			case EXEMPT -> "EXEMPTION_PERIOD";
			case EXCLUDED -> "NO_TEAM";
			default -> null;
		};
		String holdReason = status == JudgmentStatus.HOLD ? "API_ERROR" : null;
		Integer verifiedDays = status == JudgmentStatus.PASS ? Integer.valueOf(3)
				: status == JudgmentStatus.FAIL ? Integer.valueOf(1) : null;
		Integer recordCount = status == JudgmentStatus.PASS ? Integer.valueOf(1)
				: status == JudgmentStatus.FAIL ? Integer.valueOf(0) : null;
		LocalDateTime judgedAt = confirmed ? week.plusDays(7).atTime(7, 0) : null;
		return insertReturningId(
				"insert into weekly_judgment (member_id, week_start, status, skip_reason, hold_reason, retry_count,"
						+ " verified_days, record_count, corrected, judged_at) values (?, ?, ?, ?, ?, 0, ?, ?, false, ?)",
				memberId, week, status.name(), skipReason, holdReason, verifiedDays, recordCount, judgedAt);
	}

	// deletedAt이 null이면 살아 있는 경고. 있으면 추방(KICKED)으로 삭제 표시한 경고
	public long warning(long memberId, LocalDate week, LocalDateTime deletedAt) {
		return insertReturningId(
				"insert into warning (member_id, week_start, deleted_at, delete_reason, created_at) values (?, ?, ?, ?, ?)",
				memberId, week, deletedAt, deletedAt == null ? null : "KICKED", week.plusDays(7).atTime(7, 0));
	}

	public void warningTeam(long warningId, long teamId, LocalDateTime removedAt) {
		jdbc.update("insert into warning_team (warning_id, team_id, removed_at) values (?, ?, ?)", warningId, teamId,
				removedAt);
	}

	public void judgmentTeam(long judgmentId, long teamId) {
		jdbc.update("insert into judgment_team (judgment_id, team_id) values (?, ?)", judgmentId, teamId);
	}

	public long warningId(long memberId, int weekIndex) {
		return jdbc.queryForObject("select id from warning where member_id = ? and week_start = ?", Long.class,
				memberId, WeekScript.week(weekIndex));
	}

	public long judgmentId(long memberId, int weekIndex) {
		return jdbc.queryForObject("select id from weekly_judgment where member_id = ? and week_start = ?", Long.class,
				memberId, WeekScript.week(weekIndex));
	}

	public long fulfill(long memberId, long adminId, LocalDateTime fulfilledAt, LocalDate reachedWeek) {
		return insertReturningId(
				"insert into penalty_fulfillment (member_id, fulfilled_at, admin_id, reached_week) values (?, ?, ?, ?)",
				memberId, fulfilledAt, adminId, reachedWeek);
	}

	// ---- 정정·복구 흉내 (서비스가 아직 없어 DB를 직접 고친다) ----

	public void setStatus(long memberId, int weekIndex, JudgmentStatus status) {
		jdbc.update("update weekly_judgment set status = ? where member_id = ? and week_start = ?", status.name(),
				memberId, WeekScript.week(weekIndex));
	}

	public void softDeleteWarning(long memberId, int weekIndex, LocalDateTime deletedAt) {
		jdbc.update("update warning set deleted_at = ?, delete_reason = 'KICKED' where member_id = ? and week_start = ?",
				deletedAt, memberId, WeekScript.week(weekIndex));
	}

	public void restoreWarning(long memberId, int weekIndex) {
		jdbc.update("update warning set deleted_at = null, delete_reason = null where member_id = ? and week_start = ?",
				memberId, WeekScript.week(weekIndex));
	}

	// ---- 조회 ----

	// 회원에 걸린 행을 테이블별로 전부 읽는다. 전후 비교용
	public Map<String, List<Map<String, Object>>> snapshot(long memberId) {
		Map<String, List<Map<String, Object>>> snapshot = new LinkedHashMap<>();
		snapshot.put("member", jdbc.queryForList("select * from member where id = ?", memberId));
		snapshot.put("weekly_judgment",
				jdbc.queryForList("select * from weekly_judgment where member_id = ? order by week_start", memberId));
		snapshot.put("judgment_team", jdbc.queryForList("select jt.* from judgment_team jt join weekly_judgment wj"
				+ " on wj.id = jt.judgment_id where wj.member_id = ? order by jt.judgment_id, jt.team_id", memberId));
		snapshot.put("judgment_day", jdbc.queryForList("select jd.* from judgment_day jd join weekly_judgment wj"
				+ " on wj.id = jd.judgment_id where wj.member_id = ? order by jd.judgment_id, jd.day", memberId));
		snapshot.put("warning",
				jdbc.queryForList("select * from warning where member_id = ? order by week_start", memberId));
		snapshot.put("warning_team", jdbc.queryForList("select wt.* from warning_team wt join warning w"
				+ " on w.id = wt.warning_id where w.member_id = ? order by wt.warning_id, wt.team_id", memberId));
		snapshot.put("penalty_fulfillment",
				jdbc.queryForList("select * from penalty_fulfillment where member_id = ? order by id", memberId));
		return snapshot;
	}

	public int count(String sql, Object... args) {
		Integer value = jdbc.queryForObject(sql, Integer.class, args);
		return value == null ? 0 : value;
	}

	// ---- 정리 ----

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
		for (long id : members) {
			jdbc.update("delete from judgment_day where judgment_id in (select id from weekly_judgment where member_id = ?)", id);
			jdbc.update("delete from judgment_team where judgment_id in (select id from weekly_judgment where member_id = ?)", id);
			jdbc.update("delete from weekly_judgment where member_id = ?", id);
			jdbc.update("delete from warning_team where warning_id in (select id from warning where member_id = ?)", id);
			jdbc.update("delete from warning where member_id = ?", id);
			jdbc.update("delete from penalty_fulfillment where member_id = ? or admin_id = ?", id, id);
		}
		for (long id : teams) {
			jdbc.update("delete from judgment_team where team_id = ?", id);
			jdbc.update("delete from warning_team where team_id = ?", id);
			jdbc.update("delete from team where id = ?", id);
		}
		for (long id : members) {
			jdbc.update("delete from admin_action_log where admin_id = ?", id);
			jdbc.update("delete from member where id = ?", id);
		}
	}

	private long insertReturningId(String sql, Object... args) {
		var keys = new GeneratedKeyHolder();
		jdbc.update(connection -> {
			PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
			for (int i = 0; i < args.length; i++) {
				ps.setObject(i + 1, args[i]);
			}
			return ps;
		}, keys);
		return keys.getKey().longValue();
	}

}
