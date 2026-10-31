package com.jandilog.judgment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jandilog.judgment.domain.HoldReason;
import com.jandilog.judgment.dto.GrassDay;
import com.jandilog.judgment.dto.GrassSnapshot;
import com.jandilog.testsupport.judgment.JudgmentIntegrationTest;

// 프로필 잔디 전체 이력 캐시(Redis grasshistory:{memberId}, TTL 26시간). GitHub는 가짜, 시계는 2026-10-31(토) 06:00 KST
class GrassHistoryCacheServiceTest extends JudgmentIntegrationTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final LocalDate TODAY = LocalDate.of(2026, 10, 31);
	private static final LocalDate JOINED = LocalDate.of(2024, 11, 20);
	private static final String LOGIN = "octocat";

	@Autowired
	private ObjectMapper objectMapper;

	private long memberId;
	private final AtomicInteger calls = new AtomicInteger();
	private volatile List<GrassDay> response;
	private volatile RuntimeException failure;
	private GrassHistoryCacheService history;

	@BeforeEach
	void setUp() {
		clock.fixAt(LocalDateTime.of(2026, 10, 31, 6, 0).atZone(KST).toInstant());
		memberId = fixture.redisMemberId();
		response = daysBetween(JOINED, TODAY);
		history = new GrassHistoryCacheService(redis, (githubId, login, today) -> {
			calls.incrementAndGet();
			if (failure != null) {
				throw failure;
			}
			return response;
		}, clock, objectMapper);
	}

	@AfterEach
	void removeKey() {
		redis.delete(GrassHistoryCacheService.key(memberId));
	}

	private static List<GrassDay> daysBetween(LocalDate from, LocalDate to) {
		List<GrassDay> days = new ArrayList<>();
		for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
			days.add(new GrassDay(day, day.getDayOfMonth() % 3));
		}
		return days;
	}

	private GrassFetchException fetchFailure(Runnable call) {
		Throwable thrown = catchThrowable(call::run);
		assertThat(thrown).isInstanceOf(GrassFetchException.class);
		return (GrassFetchException) thrown;
	}

	@Test
	void 갱신하면_가입일부터_오늘까지_저장하고_find가_같은_값을_돌려준다() {
		GrassSnapshot stored = history.refresh(memberId, 7L, LOGIN);

		assertThat(stored.memberId()).isEqualTo(memberId);
		assertThat(stored.from()).isEqualTo(JOINED);
		assertThat(stored.to()).isEqualTo(TODAY);
		assertThat(stored.fetchedAt()).isEqualTo(LocalDateTime.of(2026, 10, 31, 6, 0));
		assertThat(stored.days()).hasSize(response.size());
		assertThat(history.find(memberId)).contains(stored);
		assertThat(calls).hasValue(1);
	}

	@Test
	void 키는_grasshistory_회원id이고_TTL은_26시간이다() {
		history.refresh(memberId, 7L, LOGIN);

		assertThat(GrassHistoryCacheService.key(memberId)).isEqualTo("grasshistory:" + memberId);
		Long ttl = redis.getExpire(GrassHistoryCacheService.key(memberId), TimeUnit.SECONDS);
		assertThat(ttl).isBetween(Duration.ofHours(26).toSeconds() - 60, Duration.ofHours(26).toSeconds());
	}

	@Test
	void find는_GitHub를_부르지_않고_캐시가_없으면_빈_값이다() {
		assertThat(history.find(memberId)).isEmpty();
		assertThat(calls).hasValue(0);
	}

	@Test
	void 깨진_캐시는_없는_것으로_본다() {
		redis.opsForValue().set(GrassHistoryCacheService.key(memberId), "{깨짐");

		assertThat(history.find(memberId)).isEmpty();
	}

	@Test
	void 판정용_잔디_캐시와_키가_겹치지_않는다() {
		history.refresh(memberId, 7L, LOGIN);

		assertThat(redis.keys("grass:" + memberId + ":*")).isEmpty();
	}

	@Test
	void GitHub_실패는_그대로_올리고_예전_캐시를_지우지_않는다() {
		GrassSnapshot before = history.refresh(memberId, 7L, LOGIN);
		failure = new GrassFetchException(HoldReason.API_ERROR, "시험 실패");

		assertThat(fetchFailure(() -> history.refresh(memberId, 7L, LOGIN)).getReason())
				.isEqualTo(HoldReason.API_ERROR);
		assertThat(history.find(memberId)).contains(before);
	}

	@Test
	void 빈_응답이나_빠진_날이_있는_응답은_API_ERROR이고_저장하지_않는다() {
		response = List.of();
		assertThat(fetchFailure(() -> history.refresh(memberId, 7L, LOGIN)).getReason())
				.isEqualTo(HoldReason.API_ERROR);

		List<GrassDay> gap = new ArrayList<>(daysBetween(JOINED, TODAY));
		gap.remove(10);
		response = gap;
		assertThat(fetchFailure(() -> history.refresh(memberId, 7L, LOGIN)).getReason())
				.isEqualTo(HoldReason.API_ERROR);

		response = daysBetween(JOINED, TODAY.minusDays(1));
		assertThat(fetchFailure(() -> history.refresh(memberId, 7L, LOGIN)).getReason())
				.isEqualTo(HoldReason.API_ERROR);
		assertThat(history.find(memberId)).isEmpty();
	}

	@Test
	@SuppressWarnings("unchecked")
	void Redis_장애는_읽기는_빈_값_저장은_받은_값을_돌려준다() {
		StringRedisTemplate failing = mock(StringRedisTemplate.class);
		ValueOperations<String, String> operations = mock(ValueOperations.class);
		when(failing.opsForValue()).thenReturn(operations);
		when(operations.get(anyString())).thenThrow(new RedisConnectionFailureException("읽기 실패 시험"));
		doThrow(new RedisConnectionFailureException("저장 실패 시험")).when(operations)
				.set(anyString(), anyString(), any(Duration.class));
		GrassHistoryCacheService broken = new GrassHistoryCacheService(failing, (githubId, login, today) -> response,
				clock, objectMapper);

		assertThat(broken.find(memberId)).isEmpty();
		assertThatCode(() -> broken.refresh(memberId, 7L, LOGIN)).doesNotThrowAnyException();
		assertThat(broken.refresh(memberId, 7L, LOGIN).from()).isEqualTo(JOINED);
	}

}
