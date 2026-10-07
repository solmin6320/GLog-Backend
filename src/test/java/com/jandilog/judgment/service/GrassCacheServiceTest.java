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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jandilog.judgment.domain.HoldReason;
import com.jandilog.judgment.dto.GrassDay;
import com.jandilog.judgment.dto.GrassSnapshot;
import com.jandilog.testsupport.judgment.FakeGrassClient;
import com.jandilog.testsupport.judgment.JudgmentIntegrationTest;

// 잔디 캐시(Redis grass:{memberId}:{yyyy-MM-dd}, TTL 26시간) + 가짜 GitHub 클라이언트. 회원당 하루 1회 호출이 핵심이다 (기능명세서 5장, DB명세서 2장)
// 시계는 2026-10-05(월) 07:00 KST. 기본 조회 구간은 지난 주 월요일(09-28)부터 오늘까지
class GrassCacheServiceTest extends JudgmentIntegrationTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final LocalDate TODAY = LocalDate.of(2026, 10, 5);
	private static final LocalDate DEFAULT_FROM = LocalDate.of(2026, 9, 28);
	private static final String LOGIN = "octocat";

	@Autowired
	private GrassCacheService cache;
	@Autowired
	private ObjectMapper objectMapper;

	private long memberId;

	@BeforeEach
	void fixClockAndMember() {
		fixClock(LocalDateTime.of(2026, 10, 5, 7, 0));
		memberId = fixture.redisMemberId();
	}

	private void fixClock(LocalDateTime kstTime) {
		clock.fixAt(kstTime.atZone(KST).toInstant());
	}

	private String key(LocalDate date) {
		return "grass:" + memberId + ":" + date;
	}

	private String keyOf(long otherMemberId, LocalDate date) {
		return "grass:" + otherMemberId + ":" + date;
	}

	// ---------- 하루 1회 호출 ----------

	@Test
	void 같은_날_두_번_조회해도_GitHub는_한_번만_부른다() {
		GrassSnapshot first = cache.getOrFetch(memberId, LOGIN);
		GrassSnapshot second = cache.getOrFetch(memberId, LOGIN);

		assertThat(fakeGrass.callCount()).isEqualTo(1);
		assertThat(second).isEqualTo(first);
		assertThat(fakeGrass.calls()).containsExactly(new FakeGrassClient.Call(LOGIN, DEFAULT_FROM, TODAY));
	}

	@Test
	void 조회_구간은_지난_주_월요일부터_오늘까지_8일이고_갱신_기준_시각이_붙는다() {
		GrassSnapshot snapshot = cache.getOrFetch(memberId, LOGIN);

		assertThat(snapshot.memberId()).isEqualTo(memberId);
		assertThat(snapshot.from()).isEqualTo(DEFAULT_FROM);
		assertThat(snapshot.to()).isEqualTo(TODAY);
		assertThat(snapshot.days()).hasSize(8);
		assertThat(snapshot.fetchedAt()).isEqualTo(LocalDateTime.of(2026, 10, 5, 7, 0));
	}

	@Test
	void 같은_회원에_동시에_여러_번_와도_GitHub는_한_번만_부른다() throws Exception {
		fakeGrass.delayMillis(300);
		int threads = 12;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch startGate = new CountDownLatch(1);
		try {
			List<Future<GrassSnapshot>> futures = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				futures.add(pool.submit(() -> {
					startGate.await();
					return cache.getOrFetch(memberId, LOGIN);
				}));
			}
			startGate.countDown();
			List<GrassSnapshot> snapshots = new ArrayList<>();
			for (Future<GrassSnapshot> future : futures) {
				snapshots.add(future.get(20, TimeUnit.SECONDS));
			}

			assertThat(fakeGrass.callCount()).isEqualTo(1);
			assertThat(snapshots).hasSize(threads).containsOnly(snapshots.get(0));
		}
		finally {
			pool.shutdownNow();
		}
	}

	@Test
	void 회원이_다르면_동시에_와도_각자_한_번씩_부른다() throws Exception {
		fakeGrass.delayMillis(200);
		long second = fixture.redisMemberId();
		long third = fixture.redisMemberId();
		long[] members = {memberId, second, third};
		ExecutorService pool = Executors.newFixedThreadPool(9);
		CountDownLatch startGate = new CountDownLatch(1);
		try {
			List<Future<GrassSnapshot>> futures = new ArrayList<>();
			for (int i = 0; i < 9; i++) {
				long target = members[i % 3];
				futures.add(pool.submit(() -> {
					startGate.await();
					return cache.getOrFetch(target, LOGIN);
				}));
			}
			startGate.countDown();
			for (Future<GrassSnapshot> future : futures) {
				future.get(20, TimeUnit.SECONDS);
			}

			assertThat(fakeGrass.callCount()).isEqualTo(3);
			assertThat(redis.hasKey(key(TODAY))).isTrue();
			assertThat(redis.hasKey(keyOf(second, TODAY))).isTrue();
			assertThat(redis.hasKey(keyOf(third, TODAY))).isTrue();
		}
		finally {
			pool.shutdownNow();
		}
	}

	@Test
	void 어제_캐시는_판정에_쓰지_않아서_오늘_첫_조회는_GitHub를_부른다() {
		fixClock(LocalDateTime.of(2026, 10, 4, 22, 0));
		cache.getOrFetch(memberId, LOGIN);
		fixClock(LocalDateTime.of(2026, 10, 5, 7, 0));

		cache.getOrFetch(memberId, LOGIN);

		assertThat(fakeGrass.callCount()).isEqualTo(2);
		assertThat(redis.hasKey(key(TODAY))).isTrue();
		assertThat(redis.hasKey(key(TODAY.minusDays(1)))).isTrue();
	}

	// ---------- 키·TTL·저장 형식 ----------

	@Test
	void 캐시_키는_grass_회원id_조회한_날_KST_형식이다() {
		cache.getOrFetch(memberId, LOGIN);

		assertThat(GrassCacheService.key(memberId, TODAY)).isEqualTo("grass:" + memberId + ":2026-10-05");
		assertThat(redis.keys("grass:" + memberId + ":*")).containsExactly(key(TODAY));
	}

	@Test
	void UTC로는_전날이어도_키의_날짜는_KST_오늘이다() {
		// 2026-10-05 08:30 KST = 2026-10-04T23:30Z
		clock.fixAt(Instant.parse("2026-10-04T23:30:00Z"));

		cache.getOrFetch(memberId, LOGIN);

		assertThat(redis.hasKey(key(LocalDate.of(2026, 10, 5)))).isTrue();
		assertThat(redis.hasKey(key(LocalDate.of(2026, 10, 4)))).isFalse();
	}

	@Test
	void TTL은_26시간이다() {
		cache.getOrFetch(memberId, LOGIN);

		assertThat(GrassCacheService.TTL).isEqualTo(Duration.ofHours(26));
		Long seconds = redis.getExpire(key(TODAY), TimeUnit.SECONDS);
		assertThat(seconds).isNotNull().isBetween(26L * 3600 - 120, 26L * 3600);
	}

	@Test
	void 저장된_JSON은_구간과_일자별_기여만_담고_로그인은_담지_않는다() throws Exception {
		fixClock(LocalDateTime.of(2026, 10, 5, 8, 30));
		cache.getOrFetch(memberId, LOGIN);

		String json = redis.opsForValue().get(key(TODAY));
		JsonNode node = objectMapper.readTree(json);

		assertThat(node.path("memberId").asLong()).isEqualTo(memberId);
		assertThat(node.path("from").asText()).isEqualTo("2026-09-28");
		assertThat(node.path("to").asText()).isEqualTo("2026-10-05");
		assertThat(node.path("fetchedAt").asText()).isEqualTo("2026-10-05T08:30");
		assertThat(node.path("days")).hasSize(8);
		assertThat(node.path("days").get(0).path("date").asText()).isEqualTo("2026-09-28");
		assertThat(node.path("days").get(0).path("count").asInt()).isEqualTo(1);
		assertThat(json).doesNotContain(LOGIN);
	}

	@Test
	void 회원마다_따로_저장한다() {
		long other = fixture.redisMemberId();

		cache.getOrFetch(memberId, LOGIN);
		cache.getOrFetch(other, "someone-else");

		assertThat(fakeGrass.callCount()).isEqualTo(2);
		assertThat(cache.find(memberId, TODAY)).isPresent();
		assertThat(cache.find(other, TODAY)).isPresent();
		assertThat(cache.find(memberId, TODAY).get().memberId()).isEqualTo(memberId);
		assertThat(cache.find(other, TODAY).get().memberId()).isEqualTo(other);
	}

	// ---------- 화면용 findLatest: GitHub를 부르지 않는다 ----------

	@Test
	void 캐시가_없으면_findLatest는_빈_값이고_GitHub를_부르지_않는다() {
		Optional<GrassSnapshot> latest = cache.findLatest(memberId);

		assertThat(latest).isEmpty();
		assertThat(fakeGrass.callCount()).isZero();
	}

	@Test
	void 오늘_캐시가_있으면_findLatest는_오늘_것을_돌려준다() {
		GrassSnapshot stored = cache.getOrFetch(memberId, LOGIN);
		fakeGrass.reset();

		Optional<GrassSnapshot> latest = cache.findLatest(memberId);

		assertThat(latest).contains(stored);
		assertThat(fakeGrass.callCount()).isZero();
	}

	@Test
	void 오늘_캐시가_없으면_findLatest는_어제_캐시를_돌려주고_GitHub를_부르지_않는다() {
		fixClock(LocalDateTime.of(2026, 10, 4, 6, 0));
		GrassSnapshot yesterday = cache.getOrFetch(memberId, LOGIN);
		fixClock(LocalDateTime.of(2026, 10, 5, 7, 0));
		int callsBefore = fakeGrass.callCount();

		Optional<GrassSnapshot> latest = cache.findLatest(memberId);

		assertThat(latest).contains(yesterday);
		assertThat(latest.get().to()).isEqualTo(TODAY.minusDays(1));
		assertThat(fakeGrass.callCount()).isEqualTo(callsBefore);
	}

	@Test
	void 오늘과_어제가_둘_다_있으면_findLatest는_오늘_것이_먼저다() {
		fixClock(LocalDateTime.of(2026, 10, 4, 6, 0));
		cache.getOrFetch(memberId, LOGIN);
		fixClock(LocalDateTime.of(2026, 10, 5, 6, 0));
		GrassSnapshot today = cache.getOrFetch(memberId, LOGIN);
		int callsBefore = fakeGrass.callCount();

		Optional<GrassSnapshot> latest = cache.findLatest(memberId);

		assertThat(latest).contains(today);
		assertThat(latest.get().days()).extracting(GrassDay::count).containsOnly(2);
		assertThat(fakeGrass.callCount()).isEqualTo(callsBefore);
	}

	@Test
	void 그저께_캐시는_findLatest가_쓰지_않는다() {
		fixClock(LocalDateTime.of(2026, 10, 3, 6, 0));
		cache.getOrFetch(memberId, LOGIN);
		fixClock(LocalDateTime.of(2026, 10, 5, 7, 0));
		int callsBefore = fakeGrass.callCount();

		assertThat(cache.findLatest(memberId)).isEmpty();
		assertThat(fakeGrass.callCount()).isEqualTo(callsBefore);
	}

	// ---------- 깨진 캐시 ----------

	@ParameterizedTest
	@ValueSource(strings = {
			"", "not json", "{", "[]", "null", "{}", "{\"memberId\":1}",
			"{\"memberId\":1,\"from\":\"2026-09-28\",\"to\":\"2026-10-05\",\"fetchedAt\":\"2026-10-05T07:00\"}",
			"{\"memberId\":1,\"from\":\"x\",\"to\":\"y\",\"fetchedAt\":\"z\",\"days\":[]}",
			"{\"memberId\":1,\"from\":\"2026-09-28\",\"to\":\"2026-09-28\",\"fetchedAt\":\"2026-10-05T07:00\","
					+ "\"days\":[{\"date\":null,\"count\":1}]}",
			"{\"memberId\":1,\"from\":\"2026-09-28\",\"to\":\"2026-09-28\",\"fetchedAt\":\"2026-10-05T07:00\","
					+ "\"days\":[null]}",
			"{\"memberId\":1,\"from\":\"2026-09-28\",\"to\":\"2026-09-28\",\"fetchedAt\":\"2026-10-05T07:00\","
					+ "\"days\":[{\"date\":\"2026-09-28\",\"count\":-3}]}",
			"{\"memberId\":1,\"from\":\"2026-09-28\",\"to\":\"2026-10-05\",\"fetchedAt\":\"2026-10-05T07:00\","
					+ "\"days\":[{\"date\":\"2026-09-28\",\"count\":1}]}"})
	void 깨진_캐시는_없는_것으로_취급한다(String broken) {
		redis.opsForValue().set(key(TODAY), broken);

		assertThat(cache.find(memberId, TODAY)).isEmpty();
		assertThat(cache.findLatest(memberId)).isEmpty();
		assertThat(fakeGrass.callCount()).isZero();
	}

	@Test
	void 오늘_캐시가_깨졌으면_findLatest는_어제_캐시로_넘어간다() {
		fixClock(LocalDateTime.of(2026, 10, 4, 6, 0));
		GrassSnapshot yesterday = cache.getOrFetch(memberId, LOGIN);
		fixClock(LocalDateTime.of(2026, 10, 5, 7, 0));
		redis.opsForValue().set(key(TODAY), "{broken");

		assertThat(cache.findLatest(memberId)).contains(yesterday);
	}

	@Test
	void 깨진_캐시는_다음_조회가_GitHub를_한_번_불러_정상_값으로_덮어쓴다() {
		redis.opsForValue().set(key(TODAY), "{broken");

		GrassSnapshot snapshot = cache.getOrFetch(memberId, LOGIN);
		cache.getOrFetch(memberId, LOGIN);

		assertThat(fakeGrass.callCount()).isEqualTo(1);
		assertThat(cache.find(memberId, TODAY)).contains(snapshot);
	}

	// ---------- Redis 저장 실패 ----------

	@Test
	@SuppressWarnings("unchecked")
	void Redis_저장이_실패해도_받은_값은_돌려주고_다음_조회가_다시_부른다() {
		StringRedisTemplate failing = mock(StringRedisTemplate.class);
		ValueOperations<String, String> operations = mock(ValueOperations.class);
		when(failing.opsForValue()).thenReturn(operations);
		when(operations.get(anyString())).thenReturn(null);
		doThrow(new RedisConnectionFailureException("저장 실패 시험")).when(operations)
				.set(anyString(), anyString(), any(Duration.class));
		GrassCacheService service = new GrassCacheService(failing, fakeGrass, clock, objectMapper);

		GrassSnapshot first = service.getOrFetch(memberId, LOGIN);

		assertThat(first.days()).hasSize(8);
		assertThat(first.from()).isEqualTo(DEFAULT_FROM);
		assertThatCode(() -> service.refresh(memberId, LOGIN)).doesNotThrowAnyException();
		assertThat(fakeGrass.callCount()).isEqualTo(2);
		service.getOrFetch(memberId, LOGIN);
		assertThat(fakeGrass.callCount()).isEqualTo(3);
	}

	// ---------- 구간 부족 ----------

	@Test
	void 필요한_구간을_덮지_못하면_getOrFetchCovering이_더_넓게_다시_부른다() {
		cache.getOrFetch(memberId, LOGIN);
		LocalDate requiredFrom = LocalDate.of(2026, 9, 14);

		GrassSnapshot wider = cache.getOrFetchCovering(memberId, LOGIN, requiredFrom);

		assertThat(fakeGrass.callCount()).isEqualTo(2);
		assertThat(wider.from()).isEqualTo(requiredFrom);
		assertThat(wider.to()).isEqualTo(TODAY);
		assertThat(wider.days()).hasSize(22);
		assertThat(fakeGrass.calls().get(1)).isEqualTo(new FakeGrassClient.Call(LOGIN, requiredFrom, TODAY));
		assertThat(cache.find(memberId, TODAY)).contains(wider);
	}

	@Test
	void 넓힌_캐시는_그_구간과_더_좁은_구간_요청에_다시_부르지_않는다() {
		cache.getOrFetch(memberId, LOGIN);
		cache.getOrFetchCovering(memberId, LOGIN, LocalDate.of(2026, 9, 14));

		cache.getOrFetchCovering(memberId, LOGIN, LocalDate.of(2026, 9, 14));
		cache.getOrFetchCovering(memberId, LOGIN, LocalDate.of(2026, 9, 28));
		cache.getOrFetch(memberId, LOGIN);

		assertThat(fakeGrass.callCount()).isEqualTo(2);
	}

	@Test
	void 필요한_구간이_이미_덮이면_다시_부르지_않는다() {
		GrassSnapshot first = cache.getOrFetch(memberId, LOGIN);

		GrassSnapshot again = cache.getOrFetchCovering(memberId, LOGIN, LocalDate.of(2026, 10, 1));

		assertThat(again).isEqualTo(first);
		assertThat(fakeGrass.callCount()).isEqualTo(1);
	}

	@Test
	void 캐시가_없을_때_필요한_시작일이_기본_시작일보다_늦으면_기본_구간으로_부른다() {
		cache.getOrFetchCovering(memberId, LOGIN, LocalDate.of(2026, 10, 1));

		assertThat(fakeGrass.calls()).containsExactly(new FakeGrassClient.Call(LOGIN, DEFAULT_FROM, TODAY));
	}

	@Test
	void 캐시가_없을_때_필요한_시작일이_더_이르면_그_시작일부터_부른다() {
		LocalDate requiredFrom = LocalDate.of(2026, 9, 1);

		GrassSnapshot snapshot = cache.getOrFetchCovering(memberId, LOGIN, requiredFrom);

		assertThat(snapshot.from()).isEqualTo(requiredFrom);
		assertThat(fakeGrass.calls()).containsExactly(new FakeGrassClient.Call(LOGIN, requiredFrom, TODAY));
	}

	@ParameterizedTest
	@CsvSource({"2026-10-05, 2026-09-28", "2026-10-04, 2026-09-21", "2026-10-07, 2026-09-28", "2026-10-11, 2026-09-28",
			"2026-10-12, 2026-10-05"})
	void 기본_조회_시작일은_지난_주_월요일이다(LocalDate today, LocalDate expected) {
		assertThat(GrassCacheService.defaultFrom(today)).isEqualTo(expected);
	}

	// ---------- refresh ----------

	@Test
	void refresh는_캐시를_무시하고_다시_불러_덮어쓴다() {
		cache.getOrFetch(memberId, LOGIN);
		fixClock(LocalDateTime.of(2026, 10, 5, 9, 15));

		GrassSnapshot refreshed = cache.refresh(memberId, LOGIN);

		assertThat(fakeGrass.callCount()).isEqualTo(2);
		assertThat(refreshed.days()).extracting(GrassDay::count).containsOnly(2);
		assertThat(refreshed.fetchedAt()).isEqualTo(LocalDateTime.of(2026, 10, 5, 9, 15));
		assertThat(cache.find(memberId, TODAY)).contains(refreshed);
		assertThat(cache.getOrFetch(memberId, LOGIN)).isEqualTo(refreshed);
		assertThat(fakeGrass.callCount()).isEqualTo(2);
	}

	@Test
	void refresh는_TTL을_26시간으로_다시_잡는다() {
		cache.getOrFetch(memberId, LOGIN);
		redis.expire(key(TODAY), Duration.ofSeconds(100));

		cache.refresh(memberId, LOGIN);

		assertThat(redis.getExpire(key(TODAY), TimeUnit.SECONDS)).isBetween(26L * 3600 - 120, 26L * 3600);
	}

	@Test
	void refresh는_깨진_캐시_위에도_정상으로_덮어쓴다() {
		redis.opsForValue().set(key(TODAY), "{broken");

		GrassSnapshot refreshed = cache.refresh(memberId, LOGIN);

		assertThat(cache.find(memberId, TODAY)).contains(refreshed);
	}

	// ---------- 실패 ----------

	@ParameterizedTest
	@ValueSource(strings = {"API_ERROR", "IDENTITY_MISMATCH"})
	void GitHub_조회가_실패하면_사유를_그대로_던지고_캐시에_남기지_않는다(String reason) {
		fakeGrass.failWith(HoldReason.valueOf(reason));

		Throwable thrown = catchThrowable(() -> cache.getOrFetch(memberId, LOGIN));

		assertThat(thrown).isInstanceOf(GrassFetchException.class);
		assertThat(((GrassFetchException) thrown).getReason()).isEqualTo(HoldReason.valueOf(reason));
		assertThat(redis.hasKey(key(TODAY))).isFalse();
		assertThat(cache.findLatest(memberId)).isEmpty();
	}

	@Test
	void 실패_뒤_다음_조회는_GitHub를_다시_부른다() {
		fakeGrass.failWith(HoldReason.API_ERROR);
		catchThrowable(() -> cache.getOrFetch(memberId, LOGIN));
		fakeGrass.respondWith(FakeGrassClient.byCallNumber());

		GrassSnapshot snapshot = cache.getOrFetch(memberId, LOGIN);

		assertThat(snapshot.days()).hasSize(8);
		assertThat(fakeGrass.callCount()).isEqualTo(2);
	}

	@Test
	void refresh가_실패하면_기존_캐시를_그대로_둔다() {
		GrassSnapshot stored = cache.getOrFetch(memberId, LOGIN);
		fakeGrass.failWith(HoldReason.API_ERROR);

		Throwable thrown = catchThrowable(() -> cache.refresh(memberId, LOGIN));

		assertThat(thrown).isInstanceOf(GrassFetchException.class);
		assertThat(cache.find(memberId, TODAY)).contains(stored);
	}

	@Test
	void 클라이언트가_요청_구간과_다른_결과를_주면_API_ERROR로_막고_저장하지_않는다() {
		fakeGrass.respondWith((login, from, to, callNumber) -> FakeGrassClient.uniform(from, to.minusDays(1), 1));

		Throwable thrown = catchThrowable(() -> cache.getOrFetch(memberId, LOGIN));

		assertThat(thrown).isInstanceOf(GrassFetchException.class);
		assertThat(((GrassFetchException) thrown).getReason()).isEqualTo(HoldReason.API_ERROR);
		assertThat(redis.hasKey(key(TODAY))).isFalse();
	}

}
