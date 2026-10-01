package com.jandilog.testsupport.auth;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;

import com.jandilog.member.domain.MemberRole;
import com.jandilog.member.domain.MemberStatus;

// 테스트가 만든 회원만 추적해서 끝에 지운다. 공유 DB의 기존 데이터는 건드리지 않는다
public class MemberFixture {

	public record MemberRow(long id, long githubId, String githubLogin, String nickname, String profileImageUrl,
			MemberStatus status, MemberRole role, LocalDateTime createdAt, LocalDateTime approvedAt) {
	}

	private static final String AUTH_CODE_PATTERN = "authcode:*";

	private final JdbcTemplate jdbc;
	private final StringRedisTemplate redis;
	// 실제 GitHub id와 겹치지 않는 큰 값에서 시작하고, 동시에 도는 다른 테스트와 겹치지 않게 무작위 구간을 쓴다
	private final AtomicLong nextGithubId = new AtomicLong(
			8_000_000_000_000L + ThreadLocalRandom.current().nextLong(0, 900_000_000L) * 1_000L);
	// 같은 실행 안에서 검색어로 내 회원만 가려내는 표식 (영숫자 6자)
	private final String tag = Long.toString(ThreadLocalRandom.current().nextLong(36L * 36 * 36 * 36 * 36, 36L * 36 * 36 * 36 * 36 * 36), 36);
	private final AtomicLong sequence = new AtomicLong();
	private final List<Long> githubIds = Collections.synchronizedList(new ArrayList<>());

	public MemberFixture(JdbcTemplate jdbc, StringRedisTemplate redis) {
		this.jdbc = jdbc;
		this.redis = redis;
	}

	public String tag() {
		return tag;
	}

	public long nextGithubId() {
		long id = nextGithubId.incrementAndGet();
		githubIds.add(id);
		return id;
	}

	// 직접 정한 GitHub id도 끝에 정리하도록 등록한다
	public void trackGithubId(long githubId) {
		githubIds.add(githubId);
	}

	public String uniqueLogin() {
		return "u" + tag + "-" + sequence.incrementAndGet();
	}

	public long pending() {
		return insert(MemberStatus.PENDING, MemberRole.MEMBER);
	}

	public long active() {
		return insert(MemberStatus.ACTIVE, MemberRole.MEMBER);
	}

	public long rejected() {
		return insert(MemberStatus.REJECTED, MemberRole.MEMBER);
	}

	public long admin() {
		return insert(MemberStatus.ACTIVE, MemberRole.ADMIN);
	}

	public long insert(MemberStatus status, MemberRole role) {
		String login = uniqueLogin();
		return insert(status, role, login, login);
	}

	public long insert(MemberStatus status, MemberRole role, String githubLogin, String nickname) {
		return insert(status, role, githubLogin, nickname, LocalDateTime.of(2026, 1, 2, 3, 4, 5), null);
	}

	public long insert(MemberStatus status, MemberRole role, String githubLogin, String nickname,
			LocalDateTime createdAt, LocalDateTime approvedAt) {
		long githubId = nextGithubId();
		var keys = new GeneratedKeyHolder();
		jdbc.update(connection -> {
			PreparedStatement ps = connection.prepareStatement(
					"insert into member (github_id, github_login, nickname, status, role, created_at, approved_at)"
							+ " values (?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
			ps.setLong(1, githubId);
			ps.setString(2, githubLogin);
			ps.setString(3, nickname);
			ps.setString(4, status.name());
			ps.setString(5, role.name());
			ps.setObject(6, createdAt);
			ps.setObject(7, approvedAt);
			return ps;
		}, keys);
		return keys.getKey().longValue();
	}

	public Optional<MemberRow> find(long id) {
		return jdbc.query("select * from member where id = ?", (rs, i) -> new MemberRow(
				rs.getLong("id"), rs.getLong("github_id"), rs.getString("github_login"), rs.getString("nickname"),
				rs.getString("profile_image_url"), MemberStatus.valueOf(rs.getString("status")),
				MemberRole.valueOf(rs.getString("role")), rs.getObject("created_at", LocalDateTime.class),
				rs.getObject("approved_at", LocalDateTime.class)), id).stream().findFirst();
	}

	public Optional<MemberRow> findByGithubId(long githubId) {
		return jdbc.query("select id from member where github_id = ?", (rs, i) -> rs.getLong("id"), githubId)
				.stream().findFirst().flatMap(this::find);
	}

	public int countByGithubId(long githubId) {
		Integer count = jdbc.queryForObject("select count(*) from member where github_id = ?", Integer.class, githubId);
		return count == null ? 0 : count;
	}

	public void setStatus(long id, MemberStatus status) {
		jdbc.update("update member set status = ? where id = ?", status.name(), id);
	}

	public void setRole(long id, MemberRole role) {
		jdbc.update("update member set role = ? where id = ?", role.name(), id);
	}

	public void delete(long id) {
		jdbc.update("delete from admin_action_log where admin_id = ?", id);
		jdbc.update("delete from member where id = ?", id);
	}

	public int countActionLogs(long targetMemberId) {
		Integer count = jdbc.queryForObject(
				"select count(*) from admin_action_log where target_type = 'MEMBER' and target_id = ?",
				Integer.class, Long.toString(targetMemberId));
		return count == null ? 0 : count;
	}

	// 내가 만든 회원과 그 회원에 걸린 로그·일회용 코드를 지운다
	public void cleanup() {
		List<Long> ids = new ArrayList<>();
		synchronized (githubIds) {
			for (Long githubId : githubIds) {
				ids.addAll(jdbc.query("select id from member where github_id = ?", (rs, i) -> rs.getLong("id"), githubId));
			}
			githubIds.clear();
		}
		if (ids.isEmpty()) {
			return;
		}
		var keys = redis.keys(AUTH_CODE_PATTERN);
		if (keys != null) {
			for (String key : keys) {
				String value = redis.opsForValue().get(key);
				if (value != null && ids.contains(parseLong(value))) {
					redis.delete(key);
				}
			}
		}
		for (Long id : ids) {
			jdbc.update("delete from admin_action_log where admin_id = ? or (target_type = 'MEMBER' and target_id = ?)",
					id, Long.toString(id));
			jdbc.update("delete from member where id = ?", id);
		}
	}

	private static long parseLong(String value) {
		try {
			return Long.parseLong(value);
		} catch (NumberFormatException e) {
			return -1;
		}
	}

}
