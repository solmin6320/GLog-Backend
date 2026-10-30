package com.jandilog.judgment.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jandilog.judgment.domain.HoldReason;
import com.jandilog.judgment.dto.GrassDay;
import com.jandilog.judgment.dto.GrassSnapshot;

// 잔디 조회의 단일 창구. 홈·프로필·주간 활동·팀 현황판·판정이 모두 이 캐시를 본다.
// Redis grass:{memberId}:{yyyy-MM-dd}(조회한 날 KST), JSON, TTL 26시간 (DB명세서 2장).
// GitHub는 회원당 하루 1회만 부른다(기능명세서 5장). 새벽 6시 일괄 갱신이 채우고, 판정이 못 찾으면 그때 한 번 더 부른다.
// 화면은 findLatest·findLatestAll만 쓴다(GitHub를 부르지 않는다). getOrFetch·refresh는 일괄 갱신·판정·관리자 재시도용이다
@Service
public class GrassCacheService {

	private static final Logger log = LoggerFactory.getLogger(GrassCacheService.class);
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final String KEY_PREFIX = "grass:";
	// 24시간이면 갱신 직전에 비어 조회가 몰리므로 넉넉하게 둔다
	static final Duration TTL = Duration.ofHours(26);
	// 같은 회원의 동시 호출이 GitHub를 두 번 부르지 않게 회원 id로 나눈 잠금
	private static final int LOCK_STRIPES = 64;

	private final StringRedisTemplate redis;
	private final GrassClient grassClient;
	private final Clock clock;
	private final GrassSnapshotJson codec;
	private final ReentrantLock[] locks = new ReentrantLock[LOCK_STRIPES];

	public GrassCacheService(StringRedisTemplate redis, GrassClient grassClient, Clock clock,
			ObjectMapper objectMapper) {
		this.redis = redis;
		this.grassClient = grassClient;
		this.clock = clock;
		this.codec = new GrassSnapshotJson(objectMapper);
		for (int i = 0; i < LOCK_STRIPES; i++) {
			locks[i] = new ReentrantLock();
		}
	}

	public static String key(long memberId, LocalDate date) {
		return KEY_PREFIX + memberId + ":" + date;
	}

	// 기본 조회 구간의 시작일: 1년 전 다음 날. 프로필 잔디(GitHub처럼 1년치, 2026-10-30 사용자 지시)와
	// 월요일 판정·이번 주 화면이 같은 캐시를 쓴다. GitHub 조회 한도(1년) 안이다
	public static LocalDate defaultFrom(LocalDate today) {
		return today.minusYears(1).plusDays(1);
	}

	// 화면용. 캐시만 읽는다: 오늘 것, 없으면 어제 것(갱신 직전까지 비지 않게 TTL이 26시간이다)
	public Optional<GrassSnapshot> findLatest(long memberId) {
		LocalDate today = today();
		return find(memberId, today).or(() -> find(memberId, today.minusDays(1)));
	}

	// 화면용 여러 회원. 회원마다 findLatest를 부른 것과 같고, Redis는 회원 수와 관계없이 MGET 두 번까지만 부른다.
	// 캐시가 없는 회원은 결과에 없다
	public Map<Long, GrassSnapshot> findLatestAll(Collection<Long> memberIds) {
		LocalDate today = today();
		List<Long> ids = memberIds.stream().distinct().toList();
		Map<Long, GrassSnapshot> found = findAll(ids, today);
		found.putAll(findAll(ids.stream().filter(id -> !found.containsKey(id)).toList(), today.minusDays(1)));
		return found;
	}

	public Optional<GrassSnapshot> find(long memberId, LocalDate date) {
		String json;
		try {
			json = redis.opsForValue().get(key(memberId, date));
		}
		catch (DataAccessException e) {
			// Redis 장애는 캐시가 없는 것으로 본다. 화면은 "잔디를 아직 못 불러왔어요"로 보이고 500이 나지 않는다
			log.warn("잔디 캐시를 읽지 못했어요 memberId={} type={}", memberId, e.getClass().getSimpleName());
			return Optional.empty();
		}
		return parse(memberId, date, json);
	}

	private Map<Long, GrassSnapshot> findAll(List<Long> memberIds, LocalDate date) {
		Map<Long, GrassSnapshot> result = new HashMap<>();
		if (memberIds.isEmpty()) {
			return result;
		}
		List<String> values;
		try {
			values = redis.opsForValue().multiGet(memberIds.stream().map(id -> key(id, date)).toList());
		}
		catch (DataAccessException e) {
			// find와 같이 Redis 장애는 캐시가 없는 것으로 본다
			log.warn("잔디 캐시를 읽지 못했어요 members={} type={}", memberIds.size(), e.getClass().getSimpleName());
			return result;
		}
		if (values == null) {
			return result;
		}
		for (int i = 0; i < memberIds.size(); i++) {
			long memberId = memberIds.get(i);
			parse(memberId, date, values.get(i)).ifPresent(snapshot -> result.put(memberId, snapshot));
		}
		return result;
	}

	private Optional<GrassSnapshot> parse(long memberId, LocalDate date, String json) {
		if (json == null) {
			return Optional.empty();
		}
		try {
			return Optional.of(codec.read(json));
		}
		catch (JsonProcessingException | DateTimeParseException | IllegalArgumentException e) {
			// 깨진 캐시는 없는 것으로 보고 다음 조회가 덮어쓴다
			log.warn("잔디 캐시를 읽지 못했어요 key={}", key(memberId, date));
			return Optional.empty();
		}
	}

	// 오늘 캐시가 있으면 그것을, 없으면 GitHub를 한 번 불러 저장한다. 판정은 오늘 캐시만 쓴다(어제 것은 일요일 잔디가 빠질 수 있다)
	public GrassSnapshot getOrFetch(long memberId, String githubLogin) {
		return getOrFetchCovering(memberId, githubLogin, defaultFrom(today()));
	}

	// requiredFrom부터 오늘까지 덮는 오늘 캐시를 돌려준다. 못 덮으면(서버가 오래 꺼졌다 뒤늦게 판정하는 경우) 더 넓게 다시 부른다
	public GrassSnapshot getOrFetchCovering(long memberId, String githubLogin, LocalDate requiredFrom) {
		LocalDate today = today();
		Optional<GrassSnapshot> cached = findCovering(memberId, today, requiredFrom);
		if (cached.isPresent()) {
			return cached.get();
		}
		ReentrantLock lock = lockFor(memberId);
		lock.lock();
		try {
			cached = findCovering(memberId, today, requiredFrom);
			if (cached.isPresent()) {
				return cached.get();
			}
			LocalDate from = requiredFrom.isBefore(defaultFrom(today)) ? requiredFrom : defaultFrom(today);
			return fetchAndStore(memberId, githubLogin, from, today);
		}
		finally {
			lock.unlock();
		}
	}

	// 캐시를 무시하고 다시 불러 덮어쓴다. 판정 보류 재시도·새벽 일괄 갱신이 쓴다
	public GrassSnapshot refresh(long memberId, String githubLogin) {
		LocalDate today = today();
		ReentrantLock lock = lockFor(memberId);
		lock.lock();
		try {
			return fetchAndStore(memberId, githubLogin, defaultFrom(today), today);
		}
		finally {
			lock.unlock();
		}
	}

	private Optional<GrassSnapshot> findCovering(long memberId, LocalDate today, LocalDate requiredFrom) {
		return find(memberId, today).filter(snapshot -> snapshot.covers(requiredFrom, today));
	}

	private GrassSnapshot fetchAndStore(long memberId, String githubLogin, LocalDate from, LocalDate today) {
		List<GrassDay> days = grassClient.fetchDailyContributions(githubLogin, from, today);
		GrassSnapshot snapshot;
		try {
			snapshot = new GrassSnapshot(memberId, from, today, LocalDateTime.now(clock.withZone(KST)), days);
		}
		catch (IllegalArgumentException e) {
			throw new GrassFetchException(HoldReason.API_ERROR, "잔디 조회 결과가 요청 구간과 맞지 않아요", e);
		}
		store(snapshot, today);
		return snapshot;
	}

	// 저장이 실패해도 방금 받은 값은 돌려준다(다음 호출이 다시 부른다)
	private void store(GrassSnapshot snapshot, LocalDate today) {
		try {
			redis.opsForValue().set(key(snapshot.memberId(), today), codec.write(snapshot), TTL);
		}
		catch (JsonProcessingException | DataAccessException e) {
			log.warn("잔디 캐시를 저장하지 못했어요 memberId={} type={}", snapshot.memberId(), e.getClass().getSimpleName());
		}
	}

	private ReentrantLock lockFor(long memberId) {
		return locks[(int) Math.floorMod(memberId, (long) LOCK_STRIPES)];
	}

	private LocalDate today() {
		return LocalDate.now(clock.withZone(KST));
	}

}
