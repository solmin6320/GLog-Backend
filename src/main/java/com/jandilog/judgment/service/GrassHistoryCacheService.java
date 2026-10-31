package com.jandilog.judgment.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

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

// 프로필 잔디 전체 이력 캐시 (2026-10-31 사용자 지시). Redis grasshistory:{memberId}, 값은 판정 캐시와 같은 JSON, TTL 26시간.
// 판정·이번 주 화면은 기존 grass: 캐시(지난 주 월요일~오늘)를 그대로 쓰고 이 캐시는 프로필만 읽는다.
// 화면은 find만 쓰고 GitHub를 부르지 않는다. refresh는 새벽 일괄 갱신(GrassHistoryScheduler)이 쓴다
@Service
public class GrassHistoryCacheService {

	private static final Logger log = LoggerFactory.getLogger(GrassHistoryCacheService.class);
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final String KEY_PREFIX = "grasshistory:";
	static final Duration TTL = GrassCacheService.TTL;

	private final StringRedisTemplate redis;
	private final GrassHistoryClient historyClient;
	private final Clock clock;
	private final GrassSnapshotJson codec;

	public GrassHistoryCacheService(StringRedisTemplate redis, GrassHistoryClient historyClient, Clock clock,
			ObjectMapper objectMapper) {
		this.redis = redis;
		this.historyClient = historyClient;
		this.clock = clock;
		this.codec = new GrassSnapshotJson(objectMapper);
	}

	public static String key(long memberId) {
		return KEY_PREFIX + memberId;
	}

	// 캐시가 없거나 Redis를 읽지 못하거나 값이 깨졌으면 빈 값
	public Optional<GrassSnapshot> find(long memberId) {
		String json;
		try {
			json = redis.opsForValue().get(key(memberId));
		}
		catch (DataAccessException e) {
			log.warn("잔디 전체 이력 캐시를 읽지 못했어요 memberId={} type={}", memberId, e.getClass().getSimpleName());
			return Optional.empty();
		}
		if (json == null) {
			return Optional.empty();
		}
		try {
			return Optional.of(codec.read(json));
		}
		catch (JsonProcessingException | DateTimeParseException | IllegalArgumentException e) {
			log.warn("잔디 전체 이력 캐시를 읽지 못했어요 key={}", key(memberId));
			return Optional.empty();
		}
	}

	// GitHub에서 가입일~오늘을 다시 불러 덮어쓴다. 저장이 실패해도 받은 값은 돌려준다
	public GrassSnapshot refresh(long memberId, Long githubId, String githubLogin) {
		LocalDate today = LocalDate.now(clock.withZone(KST));
		List<GrassDay> days = historyClient.fetchAllDailyContributions(githubId, githubLogin, today);
		if (days.isEmpty()) {
			throw new GrassFetchException(HoldReason.API_ERROR, "잔디 전체 이력이 비었어요");
		}
		GrassSnapshot snapshot;
		try {
			snapshot = new GrassSnapshot(memberId, days.get(0).date(), today, LocalDateTime.now(clock.withZone(KST)),
					days);
		}
		catch (IllegalArgumentException e) {
			throw new GrassFetchException(HoldReason.API_ERROR, "잔디 전체 이력이 요청 구간과 맞지 않아요", e);
		}
		try {
			redis.opsForValue().set(key(memberId), codec.write(snapshot), TTL);
		}
		catch (JsonProcessingException | DataAccessException e) {
			log.warn("잔디 전체 이력 캐시를 저장하지 못했어요 memberId={} type={}", memberId, e.getClass().getSimpleName());
		}
		return snapshot;
	}

}
