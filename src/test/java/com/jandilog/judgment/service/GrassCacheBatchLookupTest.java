package com.jandilog.judgment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jandilog.judgment.dto.GrassSnapshot;
import com.jandilog.testsupport.judgment.JudgmentIntegrationTest;

// 화면용 잔디 일괄 조회(findLatestAll): 회원마다 findLatest를 부른 것과 같고, Redis는 회원 수와 관계없이 MGET 두 번까지만 부른다.
// 시계는 2026-10-05(월) 07:00 KST
class GrassCacheBatchLookupTest extends JudgmentIntegrationTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final LocalDate TODAY = LocalDate.of(2026, 10, 5);
	private static final String LOGIN = "octocat";

	@Autowired
	private GrassCacheService cache;
	@Autowired
	private ObjectMapper objectMapper;

	@BeforeEach
	void fixClock() {
		at(LocalDateTime.of(2026, 10, 5, 7, 0));
	}

	private void at(LocalDateTime kstTime) {
		clock.fixAt(kstTime.atZone(KST).toInstant());
	}

	@Test
	void 오늘만_어제만_둘_다_깨짐_그저께_없음이_섞여도_회원별_findLatest와_같다() {
		List<Long> ids = mixedMembers();
		List<Long> input = new ArrayList<>(ids);
		input.add(ids.get(0));

		Map<Long, GrassSnapshot> all = cache.findLatestAll(input);

		for (long id : ids) {
			Optional<GrassSnapshot> one = cache.findLatest(id);
			assertThat(Optional.ofNullable(all.get(id))).as("회원 %d", id).isEqualTo(one);
		}
		// 캐시가 있는 회원만 결과에 있다: 오늘만·어제만·둘 다·오늘 깨짐+어제
		assertThat(all).hasSize(4);
	}

	@Test
	void 오늘_캐시가_모두_있으면_MGET_한_번이고_없는_회원이_있으면_두_번이다() {
		List<Long> ids = mixedMembers();
		ValueOperations<String, String> operations = delegatingOperations();
		GrassCacheService counted = new GrassCacheService(template(operations), fakeGrass, clock, objectMapper);

		counted.findLatestAll(ids.subList(0, 1));
		verify(operations, times(1)).multiGet(anyCollection());

		counted.findLatestAll(ids);
		verify(operations, times(3)).multiGet(anyCollection());
		verify(operations, never()).get(anyString());
	}

	@Test
	void 빈_목록은_Redis를_부르지_않는다() {
		ValueOperations<String, String> operations = delegatingOperations();
		GrassCacheService counted = new GrassCacheService(template(operations), fakeGrass, clock, objectMapper);

		assertThat(counted.findLatestAll(List.of())).isEmpty();
		verify(operations, never()).multiGet(anyCollection());
	}

	@Test
	@SuppressWarnings("unchecked")
	void Redis가_실패하면_캐시가_없는_것으로_보고_예외를_내지_않는다() {
		ValueOperations<String, String> operations = mock(ValueOperations.class);
		when(operations.multiGet(anyCollection())).thenThrow(new RedisConnectionFailureException("조회 실패 시험"));
		GrassCacheService failing = new GrassCacheService(template(operations), fakeGrass, clock, objectMapper);

		assertThat(failing.findLatestAll(List.of(1L, 2L))).isEmpty();
		assertThat(fakeGrass.callCount()).isZero();
	}

	// 오늘만, 어제만, 둘 다, 오늘 깨짐+어제, 그저께만, 없음, 깨진 것만
	private List<Long> mixedMembers() {
		long todayOnly = fixture.redisMemberId();
		long yesterdayOnly = fixture.redisMemberId();
		long both = fixture.redisMemberId();
		long brokenToday = fixture.redisMemberId();
		long twoDaysAgo = fixture.redisMemberId();
		long none = fixture.redisMemberId();
		long brokenOnly = fixture.redisMemberId();

		at(LocalDateTime.of(2026, 10, 3, 6, 0));
		cache.getOrFetch(twoDaysAgo, LOGIN);
		at(LocalDateTime.of(2026, 10, 4, 6, 0));
		cache.getOrFetch(yesterdayOnly, LOGIN);
		cache.getOrFetch(both, LOGIN);
		cache.getOrFetch(brokenToday, LOGIN);
		at(LocalDateTime.of(2026, 10, 5, 6, 0));
		cache.getOrFetch(todayOnly, LOGIN);
		cache.getOrFetch(both, LOGIN);
		redis.opsForValue().set(GrassCacheService.key(brokenToday, TODAY), "{broken");
		redis.opsForValue().set(GrassCacheService.key(brokenOnly, TODAY), "not json");
		at(LocalDateTime.of(2026, 10, 5, 7, 0));
		return List.of(todayOnly, yesterdayOnly, both, brokenToday, twoDaysAgo, none, brokenOnly);
	}

	// 실제 Redis로 넘기면서 호출 횟수를 셀 수 있는 ValueOperations
	@SuppressWarnings("unchecked")
	private ValueOperations<String, String> delegatingOperations() {
		ValueOperations<String, String> operations = mock(ValueOperations.class);
		when(operations.multiGet(anyCollection()))
				.thenAnswer(call -> redis.opsForValue().multiGet(call.<Collection<String>>getArgument(0)));
		when(operations.get(anyString())).thenAnswer(call -> redis.opsForValue().get(call.<String>getArgument(0)));
		return operations;
	}

	private static StringRedisTemplate template(ValueOperations<String, String> operations) {
		StringRedisTemplate template = mock(StringRedisTemplate.class);
		when(template.opsForValue()).thenReturn(operations);
		return template;
	}

}
