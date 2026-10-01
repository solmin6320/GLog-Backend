package com.jandilog.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.jandilog.judgment.domain.JudgmentWeek;
import com.jandilog.testsupport.admin.AdminIntegrationTest;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;

// 경고 복구 · 벌칙 탭의 벌칙 관리: 대상 목록(비공개 팀 실명)과 이행 체크 (AD-01 ⑤⑥, 기능명세서 6·9장, E-58)
class AdminPenaltyIntegrationTest extends AdminIntegrationTest {

	private static final LocalDate WEEK = JudgmentWeek.mondayOf(LocalDate.of(2043, 2, 5));

	private static final String TARGETS = """
			query($after: String) {
			  adminPenaltyTargets(after: $after) {
			    totalCount nextCursor
			    items { member { id nickname } warningCount reachedWeek teams { id name } }
			  }
			}
			""";

	private static final String FULFILL = """
			mutation($memberId: ID!) {
			  fulfillPenalty(memberId: $memberId) { member { id } fulfilledAt reachedWeek }
			}
			""";

	@BeforeEach
	void fixClock() {
		// 모든 시험 주차가 이미 끝난 시각
		clock.fixAt(WEEK.plusWeeks(6).atTime(10, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant());
	}

	@Test
	@DisplayName("벌칙 대상은 경고 3개 이상인 회원이고 비공개 팀도 실명으로 보인다")
	void targetsListMembersWithThreeWarningsWithRealTeamNames() {
		long leader = activeMember();
		long publicTeam = data.team(data.uniqueName("공개팀"), true, leader);
		long privateTeam = data.team(data.uniqueName("비공개팀"), false, leader);
		long removedTeam = data.team(data.uniqueName("떠난팀"), true, leader);
		String publicName = teamName(publicTeam);
		String privateName = teamName(privateTeam);
		String removedName = teamName(removedTeam);

		long target = activeMember();
		long w1 = data.failWithWarning(target, WEEK.minusWeeks(2), publicTeam);
		data.failWithWarning(target, WEEK.minusWeeks(1), privateTeam);
		data.failWithWarning(target, WEEK, publicTeam, privateTeam);
		// 추방으로 빠진 카테고리는 지금 세는 경고의 팀에 넣지 않는다
		data.warningTeam(w1, removedTeam, LocalDateTime.of(2043, 1, 1, 0, 0));

		long almost = activeMember();
		data.failWithWarning(almost, WEEK.minusWeeks(1), publicTeam);
		data.failWithWarning(almost, WEEK, publicTeam);

		JsonNode item = item(allTargets(), target);

		assertThat(item.path("warningCount").asInt()).isEqualTo(3);
		assertThat(item.path("reachedWeek").asText()).isEqualTo(WEEK.toString());
		List<String> names = new ArrayList<>();
		item.path("teams").forEach(team -> names.add(team.path("name").asText()));
		assertThat(names).containsExactlyInAnyOrder(publicName, privateName).doesNotContain(removedName);
		assertThat(allTargets()).noneMatch(found -> found.path("member").path("id").asLong() == almost);
	}

	@Test
	@DisplayName("경고가 3개를 넘어도 대상이고 도달한 주가 오래된 회원이 먼저 나온다")
	void targetsAreOrderedByReachedWeekAndAllowMoreThanThree() {
		long leader = activeMember();
		long team = data.team(data.uniqueName("정렬팀"), true, leader);
		long late = activeMember();
		long early = activeMember();
		for (int i = 0; i < 3; i++) {
			data.failWithWarning(late, WEEK.minusWeeks(2 - i), team);
		}
		for (int i = 0; i < 4; i++) {
			data.failWithWarning(early, WEEK.minusWeeks(10 - i), team);
		}

		List<JsonNode> all = allTargets();

		JsonNode earlyItem = item(all, early);
		assertThat(earlyItem.path("warningCount").asInt()).isEqualTo(4);
		assertThat(earlyItem.path("reachedWeek").asText()).isEqualTo(WEEK.minusWeeks(8).toString());
		assertThat(all.indexOf(earlyItem)).isLessThan(all.indexOf(item(all, late)));
	}

	@Test
	@DisplayName("이행 체크는 경고를 0으로 만들고 로그를 남기며 대상 목록에서 빠진다")
	void fulfillResetsWarningsAndLogs() {
		long leader = activeMember();
		long team = data.team(data.uniqueName("이행팀"), true, leader);
		long target = activeMember();
		for (int i = 0; i < 3; i++) {
			data.failWithWarning(target, WEEK.minusWeeks(2 - i), team);
		}

		JsonNode result = dataOf(asAdmin(FULFILL, Map.of("memberId", Long.toString(target)))).path("fulfillPenalty");

		assertThat(result.path("member").path("id").asLong()).isEqualTo(target);
		assertThat(result.path("reachedWeek").asText()).isEqualTo(WEEK.toString());
		assertThat(result.path("fulfilledAt").asText()).startsWith(WEEK.plusWeeks(6).toString());
		assertThat(data.countFulfillments(target)).isEqualTo(1);
		assertThat(data.countLogs("FULFILL_PENALTY", "MEMBER", Long.toString(target))).isEqualTo(1);
		assertThat(allTargets()).noneMatch(found -> found.path("member").path("id").asLong() == target);

		// 이미 이행했으니 다시 체크할 수 없다
		GraphQlResponse again = asAdmin(FULFILL, Map.of("memberId", Long.toString(target)));
		assertThat(again.errorCode()).isEqualTo("NOT_PENALTY_TARGET");
		assertThat(data.countFulfillments(target)).isEqualTo(1);
	}

	@Test
	@DisplayName("벌칙 대상이 아니거나 보류 중이거나 없는 회원이면 이행 체크를 거부한다")
	void fulfillRejectsNonTargets() {
		long leader = activeMember();
		long team = data.team(data.uniqueName("거부팀"), true, leader);
		long two = activeMember();
		data.failWithWarning(two, WEEK.minusWeeks(1), team);
		data.failWithWarning(two, WEEK, team);
		long held = activeMember();
		for (int i = 0; i < 3; i++) {
			data.failWithWarning(held, WEEK.minusWeeks(3 - i), team);
		}
		data.hold(held, WEEK.plusWeeks(1), "API_ERROR", 3);

		assertThat(asAdmin(FULFILL, Map.of("memberId", Long.toString(two))).errorCode()).isEqualTo("NOT_PENALTY_TARGET");
		assertThat(asAdmin(FULFILL, Map.of("memberId", Long.toString(held))).errorCode()).isEqualTo("NOT_PENALTY_TARGET");
		assertThat(asAdmin(FULFILL, Map.of("memberId", "999999999999")).errorCode()).isEqualTo("NOT_FOUND");
		assertThat(asAdmin(FULFILL, Map.of("memberId", "abc")).errorCode()).isEqualTo("NOT_FOUND");
		assertThat(data.countFulfillments(two)).isZero();
		assertThat(data.countFulfillments(held)).isZero();
	}

	@Test
	@DisplayName("두 관리자가 동시에 이행 체크해도 한 번만 기록된다")
	void concurrentFulfillRecordsOnce() throws Exception {
		long leader = activeMember();
		long team = data.team(data.uniqueName("동시팀"), true, leader);
		long target = activeMember();
		for (int i = 0; i < 3; i++) {
			data.failWithWarning(target, WEEK.minusWeeks(2 - i), team);
		}
		long otherAdmin = data.track(members.admin());
		String otherToken = bearerFor(otherAdmin);

		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Callable<GraphQlResponse> byFirst = () -> graphQl.post(adminToken, FULFILL, Map.of("memberId", Long.toString(target)));
			Callable<GraphQlResponse> bySecond = () -> graphQl.post(otherToken, FULFILL, Map.of("memberId", Long.toString(target)));
			Future<GraphQlResponse> first = pool.submit(byFirst);
			Future<GraphQlResponse> second = pool.submit(bySecond);
			List<GraphQlResponse> responses = List.of(first.get(), second.get());

			assertThat(responses.stream().filter(response -> !response.hasErrors())).hasSize(1);
			assertThat(responses.stream().filter(GraphQlResponse::hasErrors).map(GraphQlResponse::errorCode))
					.containsExactly("NOT_PENALTY_TARGET");
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(data.countFulfillments(target)).isEqualTo(1);
		assertThat(data.countLogs("FULFILL_PENALTY", "MEMBER", Long.toString(target))).isEqualTo(1);
	}

	@Test
	@DisplayName("관리자가 아니면 벌칙 대상을 보거나 이행 체크할 수 없다")
	void onlyAdminCanManagePenalties() {
		long member = activeMember();

		assertThat(asMember(member, TARGETS, Map.of()).errorCode()).isEqualTo("FORBIDDEN");
		assertThat(asMember(member, FULFILL, Map.of("memberId", Long.toString(member))).errorCode())
				.isEqualTo("FORBIDDEN");
		assertThat(graphQl.post(null, TARGETS).errorCode()).isEqualTo("UNAUTHENTICATED");
	}

	private List<JsonNode> allTargets() {
		List<JsonNode> items = new ArrayList<>();
		String cursor = null;
		do {
			Map<String, Object> variables = cursor == null ? Map.of() : Map.of("after", cursor);
			JsonNode page = dataOf(asAdmin(TARGETS, variables)).path("adminPenaltyTargets");
			page.path("items").forEach(items::add);
			cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText();
		} while (cursor != null);
		return items;
	}

	private static JsonNode item(List<JsonNode> items, long memberId) {
		return items.stream()
				.filter(item -> item.path("member").path("id").asLong() == memberId)
				.findFirst()
				.orElseThrow(() -> new AssertionError("벌칙 대상이 없어요: " + memberId));
	}

	private String teamName(long teamId) {
		return jdbc.queryForObject("select name from team where id = ?", String.class, teamId);
	}

}
