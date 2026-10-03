package com.jandilog.testsupport.admin;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;

import com.jandilog.post.domain.Comment;
import com.jandilog.post.domain.Post;

// 관리자·프로필 통합 테스트가 넣은 판정·경고·팀 행을 추적해서 끝에 지운다. 공유 DB의 기존 데이터는 건드리지 않는다.
// 회원은 MemberFixture가 만들고, 여기서는 그 id를 track으로 알려 받아 회원에 걸린 행을 먼저 지운다
public class AdminFixture {

	private final JdbcTemplate jdbc;
	private final StringRedisTemplate redis;
	private final MongoTemplate mongo;
	private final List<Long> memberIds = Collections.synchronizedList(new ArrayList<>());
	private final List<Long> teamIds = Collections.synchronizedList(new ArrayList<>());
	private final AtomicLong sequence = new AtomicLong();
	private final String tag = Long.toString(
			ThreadLocalRandom.current().nextLong(36L * 36 * 36 * 36 * 36, 36L * 36 * 36 * 36 * 36 * 36), 36);

	public AdminFixture(JdbcTemplate jdbc, StringRedisTemplate redis, MongoTemplate mongo) {
		this.jdbc = jdbc;
		this.redis = redis;
		this.mongo = mongo;
	}

	public long track(long memberId) {
		memberIds.add(memberId);
		return memberId;
	}

	public String uniqueName(String prefix) {
		return prefix + tag + sequence.incrementAndGet();
	}

	public long team(String name, boolean isPublic, long leaderId) {
		return team(name, isPublic, leaderId, null);
	}

	public long team(String name, boolean isPublic, long leaderId, LocalDateTime deletedAt) {
		var keys = new GeneratedKeyHolder();
		jdbc.update(connection -> {
			PreparedStatement ps = connection.prepareStatement(
					"insert into team (name, leader_id, invite_code, is_public, created_at, deleted_at) values (?, ?, ?, ?, ?, ?)",
					Statement.RETURN_GENERATED_KEYS);
			ps.setString(1, name);
			ps.setLong(2, leaderId);
			ps.setString(3, "code-" + tag + "-" + sequence.incrementAndGet());
			ps.setBoolean(4, isPublic);
			ps.setObject(5, LocalDateTime.of(2026, 1, 1, 0, 0));
			ps.setObject(6, deletedAt);
			return ps;
		}, keys);
		long id = keys.getKey().longValue();
		teamIds.add(id);
		return id;
	}

	public void teamMember(long teamId, long memberId, LocalDateTime joinedAt, LocalDateTime leftAt) {
		jdbc.update(
				"insert into team_member (team_id, member_id, joined_at, left_at, leave_type, joined_by) values (?, ?, ?, ?, ?, 'INVITE_CODE')",
				teamId, memberId, joinedAt, leftAt, leftAt == null ? null : "SELF");
	}

	public long judgment(long memberId, LocalDate week, String status, String skipReason, String holdReason,
			int retryCount, Integer verifiedDays, Integer recordCount, boolean corrected, LocalDateTime judgedAt) {
		var keys = new GeneratedKeyHolder();
		jdbc.update(connection -> {
			PreparedStatement ps = connection.prepareStatement(
					"insert into weekly_judgment (member_id, week_start, status, skip_reason, hold_reason, retry_count,"
							+ " verified_days, record_count, corrected, judged_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
					Statement.RETURN_GENERATED_KEYS);
			ps.setLong(1, memberId);
			ps.setObject(2, week);
			ps.setString(3, status);
			ps.setString(4, skipReason);
			ps.setString(5, holdReason);
			ps.setInt(6, retryCount);
			ps.setObject(7, verifiedDays);
			ps.setObject(8, recordCount);
			ps.setBoolean(9, corrected);
			ps.setObject(10, judgedAt);
			return ps;
		}, keys);
		return keys.getKey().longValue();
	}

	public long pass(long memberId, LocalDate week) {
		return judgment(memberId, week, "PASS", null, null, 0, 4, 1, false, week.plusDays(7).atTime(7, 0));
	}

	public long fail(long memberId, LocalDate week) {
		return judgment(memberId, week, "FAIL", null, null, 0, 1, 0, false, week.plusDays(7).atTime(7, 0));
	}

	public long exempt(long memberId, LocalDate week) {
		return judgment(memberId, week, "EXEMPT", "EXEMPTION_PERIOD", null, 0, null, null, false,
				week.plusDays(7).atTime(7, 0));
	}

	public long firstWeek(long memberId, LocalDate week) {
		return judgment(memberId, week, "EXCLUDED", "FIRST_WEEK", null, 0, null, null, false,
				week.plusDays(7).atTime(7, 0));
	}

	public long hold(long memberId, LocalDate week, String holdReason, int retryCount) {
		return judgment(memberId, week, "HOLD", null, holdReason, retryCount, null, null, false, null);
	}

	public void judgmentTeam(long judgmentId, long teamId) {
		jdbc.update("insert into judgment_team (judgment_id, team_id) values (?, ?)", judgmentId, teamId);
	}

	public long warning(long memberId, LocalDate week, LocalDateTime deletedAt, String deleteReason) {
		var keys = new GeneratedKeyHolder();
		jdbc.update(connection -> {
			PreparedStatement ps = connection.prepareStatement(
					"insert into warning (member_id, week_start, deleted_at, delete_reason, created_at) values (?, ?, ?, ?, ?)",
					Statement.RETURN_GENERATED_KEYS);
			ps.setLong(1, memberId);
			ps.setObject(2, week);
			ps.setObject(3, deletedAt);
			ps.setString(4, deleteReason);
			ps.setObject(5, week.plusDays(7).atTime(7, 0));
			return ps;
		}, keys);
		return keys.getKey().longValue();
	}

	public void warningTeam(long warningId, long teamId, LocalDateTime removedAt) {
		jdbc.update("insert into warning_team (warning_id, team_id, removed_at) values (?, ?, ?)", warningId, teamId,
				removedAt);
	}

	// FAIL 판정 + 경고 + 팀 카테고리를 한 번에 만든다. 경고 id를 돌려준다
	public long failWithWarning(long memberId, LocalDate week, long... teams) {
		long judgmentId = fail(memberId, week);
		long warningId = warning(memberId, week, null, null);
		for (long teamId : teams) {
			judgmentTeam(judgmentId, teamId);
			warningTeam(warningId, teamId, null);
		}
		return warningId;
	}

	public void fulfillment(long memberId, long adminId, LocalDateTime fulfilledAt, LocalDate reachedWeek) {
		jdbc.update(
				"insert into penalty_fulfillment (member_id, fulfilled_at, admin_id, reached_week) values (?, ?, ?, ?)",
				memberId, fulfilledAt, adminId, reachedWeek);
	}

	public void personalExemptionPending(long memberId, long requestedBy, LocalDate week) {
		jdbc.update(
				"insert into personal_exemption (member_id, requested_by, week_start, status, reason, created_at) values (?, ?, ?, 'PENDING', '시험 기간', ?)",
				memberId, requestedBy, week, LocalDateTime.of(2026, 1, 1, 0, 0));
	}

	public void postIndexRecord(long authorId, LocalDate writtenDate) {
		jdbc.update(
				"insert into post_index (mongo_post_id, author_id, post_type, is_record, written_date, created_at) values (?, ?, 'DEVLOG', true, ?, ?)",
				new ObjectId().toHexString(), authorId, writtenDate, writtenDate.atTime(12, 0));
	}

	public int countLogs(String action, String targetType, String targetId) {
		Integer count = jdbc.queryForObject(
				"select count(*) from admin_action_log where action = ? and target_type = ? and target_id = ?",
				Integer.class, action, targetType, targetId);
		return count == null ? 0 : count;
	}

	public boolean warningAlive(long warningId) {
		Integer count = jdbc.queryForObject("select count(*) from warning where id = ? and deleted_at is null",
				Integer.class, warningId);
		return count != null && count > 0;
	}

	public boolean categoryRemoved(long warningId, long teamId) {
		Integer count = jdbc.queryForObject(
				"select count(*) from warning_team where warning_id = ? and team_id = ? and removed_at is not null",
				Integer.class, warningId, teamId);
		return count != null && count > 0;
	}

	public int countFulfillments(long memberId) {
		Integer count = jdbc.queryForObject("select count(*) from penalty_fulfillment where member_id = ?",
				Integer.class, memberId);
		return count == null ? 0 : count;
	}

	public String judgmentStatus(long judgmentId) {
		return jdbc.queryForObject("select status from weekly_judgment where id = ?", String.class, judgmentId);
	}

	public void cleanup() {
		List<Long> members;
		List<Long> teams;
		synchronized (memberIds) {
			members = List.copyOf(memberIds);
			memberIds.clear();
		}
		synchronized (teamIds) {
			teams = List.copyOf(teamIds);
			teamIds.clear();
		}
		if (!members.isEmpty()) {
			String in = placeholders(members.size());
			Object[] args = members.toArray();
			List<Long> judgments = jdbc.queryForList("select id from weekly_judgment where member_id in (" + in + ")",
					Long.class, args);
			List<Long> warnings = jdbc.queryForList("select id from warning where member_id in (" + in + ")",
					Long.class, args);
			if (!judgments.isEmpty()) {
				String j = placeholders(judgments.size());
				jdbc.update("delete from judgment_day where judgment_id in (" + j + ")", judgments.toArray());
				jdbc.update("delete from judgment_team where judgment_id in (" + j + ")", judgments.toArray());
				jdbc.update("delete from judgment_correction where judgment_id in (" + j + ")", judgments.toArray());
			}
			if (!warnings.isEmpty()) {
				jdbc.update("delete from warning_team where warning_id in (" + placeholders(warnings.size()) + ")",
						warnings.toArray());
			}
			jdbc.update("delete from judgment_correction where admin_id in (" + in + ")", args);
			jdbc.update("delete from weekly_judgment where member_id in (" + in + ")", args);
			jdbc.update("delete from warning where member_id in (" + in + ")", args);
			jdbc.update("delete from penalty_fulfillment where member_id in (" + in + ") or admin_id in (" + in + ")",
					concat(args, args));
			jdbc.update("delete from personal_exemption where member_id in (" + in + ") or requested_by in (" + in + ")",
					concat(args, args));
			jdbc.update("delete from post_index where author_id in (" + in + ")", args);
			jdbc.update("delete from team_member where member_id in (" + in + ")", args);
			jdbc.update("delete from admin_action_log where admin_id in (" + in + ")", args);
			for (Long id : members) {
				var keys = redis.keys("grass:" + id + ":*");
				if (keys != null && !keys.isEmpty()) {
					redis.delete(keys);
				}
			}
			mongo.remove(Query.query(Criteria.where("authorId").in(members)), Post.class);
			mongo.remove(Query.query(Criteria.where("authorId").in(members)), Comment.class);
		}
		if (!teams.isEmpty()) {
			String in = placeholders(teams.size());
			Object[] args = teams.toArray();
			jdbc.update("delete from warning_team where team_id in (" + in + ")", args);
			jdbc.update("delete from judgment_team where team_id in (" + in + ")", args);
			jdbc.update("delete from team_member where team_id in (" + in + ")", args);
			jdbc.update("delete from team where id in (" + in + ")", args);
		}
	}

	private static String placeholders(int count) {
		return String.join(", ", Collections.nCopies(count, "?"));
	}

	private static Object[] concat(Object[] a, Object[] b) {
		Object[] all = new Object[a.length + b.length];
		System.arraycopy(a, 0, all, 0, a.length);
		System.arraycopy(b, 0, all, a.length, b.length);
		return all;
	}

}
