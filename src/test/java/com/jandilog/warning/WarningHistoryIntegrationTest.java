package com.jandilog.warning;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.jandilog.judgment.domain.JudgmentWeek;
import com.jandilog.testsupport.admin.AdminIntegrationTest;

// 내 경고 이력 표(AC-02 ③)와 프로필 팀별 경고(PR-01 ⑨): 유효 · 차감됨 · 이행 완료 상태(Q-13), 팀 카테고리 표시 이름, 중복 집계
class WarningHistoryIntegrationTest extends AdminIntegrationTest {

	private static final LocalDate W = JudgmentWeek.mondayOf(LocalDate.of(2045, 3, 6));

	private static final String HISTORY = """
			query($after: String) {
			  myWarningHistory(after: $after) {
			    onHold nextCursor
			    items { weekStart weekEnd verifiedDays recordCount categories status }
			  }
			}
			""";

	private static final String PROFILE_WARNINGS = """
			query($id: ID!) {
			  profile(memberId: $id) {
			    warning { onHold warningCount }
			    teamWarnings { onHold teams { name count } }
			  }
			}
			""";

	// w1·w2는 벌칙 이행 이전, w3은 2주 연속 통과(w4·w5)로 차감, w6·w9는 유효,
	// w7은 삭제 표시된 경고, w8은 통과로 정정돼 판정만 남은 경고(둘 다 이력에 나오지 않는다)
	private record Scenario(long member, String publicTeamName) {
	}

	private Scenario scenario() {
		long leader = activeMember();
		String publicName = data.uniqueName("A팀");
		long publicTeam = data.team(publicName, true, leader);
		long privateTeam = data.team(data.uniqueName("비공개"), false, leader);
		long deletedTeam = data.team(data.uniqueName("삭제팀"), true, leader, LocalDateTime.of(2045, 1, 1, 0, 0));
		long member = activeMember();

		data.failWithWarning(member, W, publicTeam);
		data.failWithWarning(member, W.plusWeeks(1), privateTeam);
		data.fulfillment(member, adminId, W.plusWeeks(2).atStartOfDay(), W.plusWeeks(1));
		data.failWithWarning(member, W.plusWeeks(2), publicTeam, privateTeam);
		data.pass(member, W.plusWeeks(3));
		data.pass(member, W.plusWeeks(4));
		data.failWithWarning(member, W.plusWeeks(5), publicTeam, deletedTeam);
		data.fail(member, W.plusWeeks(6));
		long deletedWarning = data.warning(member, W.plusWeeks(6), LocalDateTime.of(2045, 5, 1, 0, 0), "KICKED");
		data.warningTeam(deletedWarning, publicTeam, LocalDateTime.of(2045, 5, 1, 0, 0));
		data.pass(member, W.plusWeeks(7));
		long revokedWarning = data.warning(member, W.plusWeeks(7), null, null);
		data.warningTeam(revokedWarning, publicTeam, null);
		data.failWithWarning(member, W.plusWeeks(8), publicTeam, privateTeam);
		return new Scenario(member, publicName);
	}

	@Test
	@DisplayName("경고 이력은 최근 주부터 유효 · 차감됨 · 이행 완료로 나오고 삭제 표시된 경고와 정정으로 회수된 경고는 빠진다")
	void historyShowsStatusesAndHidesRevokedWarnings() {
		Scenario scenario = scenario();

		JsonNode page = dataOf(asMember(scenario.member(), HISTORY, Map.of())).path("myWarningHistory");
		JsonNode items = page.path("items");

		assertThat(page.path("onHold").asBoolean()).isFalse();
		assertThat(page.path("nextCursor").isNull()).isTrue();
		assertThat(items).hasSize(5);
		assertRow(items.get(0), W.plusWeeks(8), "ACTIVE", scenario.publicTeamName(), "비공개 팀");
		assertRow(items.get(1), W.plusWeeks(5), "ACTIVE", scenario.publicTeamName(), "삭제된 팀");
		assertRow(items.get(2), W.plusWeeks(2), "DEDUCTED", scenario.publicTeamName(), "비공개 팀");
		assertRow(items.get(3), W.plusWeeks(1), "FULFILLED", "비공개 팀");
		assertRow(items.get(4), W, "FULFILLED", scenario.publicTeamName());
		// 사유는 그 주 판정의 인증일 · 기록글 수
		assertThat(items.get(0).path("verifiedDays").asInt()).isEqualTo(1);
		assertThat(items.get(0).path("recordCount").asInt()).isZero();

		// 이력의 유효 행 수가 프로필 ⑧ 경고 합계와 같다
		JsonNode profile = dataOf(asMember(scenario.member(), PROFILE_WARNINGS,
				Map.of("id", Long.toString(scenario.member())))).path("profile");
		long activeRows = 0;
		for (JsonNode item : items) {
			activeRows += "ACTIVE".equals(item.path("status").asText()) ? 1 : 0;
		}
		assertThat(profile.path("warning").path("warningCount").asLong()).isEqualTo(activeRows);
	}

	@Test
	@DisplayName("프로필 팀별 경고는 현재 경고 수에 드는 경고만 팀 카테고리마다 세어 합계가 경고 수와 달라도 되고 본인 · 타인이 같다")
	void teamWarningsCountOnlyActiveWarningsPerCategory() {
		Scenario scenario = scenario();
		long viewer = activeMember();

		JsonNode other = dataOf(asMember(viewer, PROFILE_WARNINGS, Map.of("id", Long.toString(scenario.member()))))
				.path("profile");
		JsonNode teams = other.path("teamWarnings").path("teams");

		assertThat(other.path("warning").path("warningCount").asInt()).isEqualTo(2);
		assertThat(other.path("teamWarnings").path("onHold").asBoolean()).isFalse();
		// 유효 경고 w6(공개·삭제된 팀)과 w9(공개·비공개 팀)만 센다. 이행 완료·차감된 경고는 뺀다
		assertThat(teams).hasSize(3);
		assertCount(teams.get(0), scenario.publicTeamName(), 2);
		assertCount(teams.get(1), "비공개 팀", 1);
		assertCount(teams.get(2), "삭제된 팀", 1);

		JsonNode self = dataOf(asMember(scenario.member(), PROFILE_WARNINGS,
				Map.of("id", Long.toString(scenario.member())))).path("profile");
		assertThat(self).isEqualTo(other);
	}

	@Test
	@DisplayName("경고가 없으면 빈 목록이고 판정 보류가 남으면 계산하지 않고 onHold만 true다")
	void emptyAndOnHold() {
		long viewer = activeMember();
		long leader = activeMember();
		long team = data.team(data.uniqueName("보류팀"), true, leader);
		long none = activeMember();
		long held = activeMember();
		data.failWithWarning(held, W, team);
		data.hold(held, W.plusWeeks(1), "API_ERROR", 3);

		JsonNode noneHistory = dataOf(asMember(none, HISTORY, Map.of())).path("myWarningHistory");
		assertThat(noneHistory.path("onHold").asBoolean()).isFalse();
		assertThat(noneHistory.path("items")).isEmpty();
		JsonNode noneTeams = dataOf(asMember(viewer, PROFILE_WARNINGS, Map.of("id", Long.toString(none))))
				.path("profile").path("teamWarnings");
		assertThat(noneTeams.path("onHold").asBoolean()).isFalse();
		assertThat(noneTeams.path("teams")).isEmpty();

		JsonNode heldHistory = dataOf(asMember(held, HISTORY, Map.of())).path("myWarningHistory");
		assertThat(heldHistory.path("onHold").asBoolean()).isTrue();
		assertThat(heldHistory.path("items")).isEmpty();
		assertThat(heldHistory.path("nextCursor").isNull()).isTrue();
		JsonNode heldTeams = dataOf(asMember(viewer, PROFILE_WARNINGS, Map.of("id", Long.toString(held))))
				.path("profile").path("teamWarnings");
		assertThat(heldTeams.path("onHold").asBoolean()).isTrue();
		assertThat(heldTeams.path("teams")).isEmpty();
	}

	@Test
	@DisplayName("경고 이력은 커서 기반 20개로 나뉜다")
	void historyPagesByCursor() {
		long leader = activeMember();
		long team = data.team(data.uniqueName("페이지팀"), true, leader);
		long member = activeMember();
		List<LocalDate> weeks = new ArrayList<>();
		for (int i = 0; i < 21; i++) {
			weeks.add(W.plusWeeks(i));
			data.failWithWarning(member, W.plusWeeks(i), team);
		}

		JsonNode first = dataOf(asMember(member, HISTORY, Map.of())).path("myWarningHistory");
		assertThat(first.path("items")).hasSize(20);
		assertThat(first.path("items").get(0).path("weekStart").asText()).isEqualTo(weeks.get(20).toString());
		assertThat(first.path("items").get(19).path("weekStart").asText()).isEqualTo(weeks.get(1).toString());
		String cursor = first.path("nextCursor").asText();
		assertThat(cursor).isNotBlank();

		JsonNode second = dataOf(asMember(member, HISTORY, Map.of("after", cursor))).path("myWarningHistory");
		assertThat(second.path("items")).hasSize(1);
		assertThat(second.path("items").get(0).path("weekStart").asText()).isEqualTo(weeks.get(0).toString());
		assertThat(second.path("nextCursor").isNull()).isTrue();
	}

	private static void assertRow(JsonNode row, LocalDate week, String status, String... categories) {
		assertThat(row.path("weekStart").asText()).isEqualTo(week.toString());
		assertThat(row.path("weekEnd").asText()).isEqualTo(week.plusDays(6).toString());
		assertThat(row.path("status").asText()).isEqualTo(status);
		List<String> actual = new ArrayList<>();
		row.path("categories").forEach(category -> actual.add(category.asText()));
		assertThat(actual).containsExactly(categories);
	}

	private static void assertCount(JsonNode team, String name, int count) {
		assertThat(team.path("name").asText()).isEqualTo(name);
		assertThat(team.path("count").asInt()).isEqualTo(count);
	}

}
