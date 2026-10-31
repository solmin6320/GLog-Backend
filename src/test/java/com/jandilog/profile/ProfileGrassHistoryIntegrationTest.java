package com.jandilog.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jandilog.judgment.dto.GrassDay;
import com.jandilog.judgment.service.GrassCacheService;
import com.jandilog.judgment.service.GrassHistoryCacheService;
import com.jandilog.testsupport.admin.AdminIntegrationTest;

// 프로필 잔디는 GitHub 가입일부터의 전체 이력 캐시를 먼저 쓰고, 없거나 깨졌으면 판정용 2주 캐시를 쓴다 (2026-10-31 사용자 지시)
class ProfileGrassHistoryIntegrationTest extends AdminIntegrationTest {

	private static final LocalDate JOINED = LocalDate.of(2021, 3, 15);

	private static final String PROFILE = """
			query($id: ID!) {
			  profile(memberId: $id) {
			    grass { from to fetchedAt days { date count } }
			  }
			}
			""";

	@Autowired
	private GrassCacheService grassCacheService;
	@Autowired
	private Clock appClock;
	@Autowired
	private ObjectMapper objectMapper;

	private final AtomicInteger historyCalls = new AtomicInteger();
	private final List<Long> touched = new ArrayList<>();
	private GrassHistoryCacheService writer;
	private LocalDate today;

	@BeforeEach
	void setUpWriter() {
		today = LocalDate.now(appClock.withZone(ZoneId.of("Asia/Seoul")));
		writer = new GrassHistoryCacheService(redis, (githubId, login, day) -> {
			historyCalls.incrementAndGet();
			List<GrassDay> days = new ArrayList<>();
			for (LocalDate d = JOINED; !d.isAfter(day); d = d.plusDays(1)) {
				days.add(new GrassDay(d, d.getDayOfMonth() == 1 ? 4 : 0));
			}
			return days;
		}, appClock, objectMapper);
	}

	@AfterEach
	void removeHistoryKeys() {
		touched.forEach(id -> redis.delete(GrassHistoryCacheService.key(id)));
	}

	private void history(long memberId) {
		touched.add(memberId);
		writer.refresh(memberId, null, members.find(memberId).orElseThrow().githubLogin());
	}

	private void twoWeeks(long memberId) {
		grassCacheService.refresh(memberId, members.find(memberId).orElseThrow().githubLogin());
	}

	private JsonNode grassOf(long viewer, long target) {
		return dataOf(asMember(viewer, PROFILE, Map.of("id", Long.toString(target)))).path("profile").path("grass");
	}

	@Test
	@DisplayName("전체 이력 캐시가 있으면 가입일부터 오늘까지 보이고 2주 캐시보다 앞선다")
	void historyWinsOverTwoWeeks() {
		long viewer = activeMember();
		long target = activeMember();
		twoWeeks(target);
		history(target);
		int stubCallsBefore = grassClient.calls();
		int historyCallsBefore = historyCalls.get();

		JsonNode grass = grassOf(viewer, target);

		assertThat(grass.path("from").asText()).isEqualTo(JOINED.toString());
		assertThat(grass.path("to").asText()).isEqualTo(today.toString());
		assertThat(grass.path("days").size()).isEqualTo(JOINED.datesUntil(today.plusDays(1)).count());
		assertThat(grass.path("days").get(0).path("date").asText()).isEqualTo(JOINED.toString());
		assertThat(grass.path("days").get(17).path("count").asInt()).isEqualTo(4);
		// 프로필 조회는 GitHub를 부르지 않는다
		assertThat(grassClient.calls()).isEqualTo(stubCallsBefore);
		assertThat(historyCalls.get()).isEqualTo(historyCallsBefore);
	}

	@Test
	@DisplayName("전체 이력 캐시가 없거나 깨졌으면 지난 주 월요일부터의 2주 캐시가 보인다")
	void fallsBackToTwoWeeks() {
		long viewer = activeMember();
		long missing = activeMember();
		long broken = activeMember();
		twoWeeks(missing);
		twoWeeks(broken);
		touched.add(broken);
		redis.opsForValue().set(GrassHistoryCacheService.key(broken), "{깨짐");
		String twoWeeksFrom = GrassCacheService.defaultFrom(today).toString();

		assertThat(grassOf(viewer, missing).path("from").asText()).isEqualTo(twoWeeksFrom);
		assertThat(grassOf(viewer, broken).path("from").asText()).isEqualTo(twoWeeksFrom);
	}

	@Test
	@DisplayName("두 캐시가 모두 없으면 잔디는 null이다")
	void nullWhenNoCache() {
		long viewer = activeMember();
		long target = activeMember();

		assertThat(grassOf(viewer, target).isNull()).isTrue();
	}

}
