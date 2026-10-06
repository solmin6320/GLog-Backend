package com.jandilog.warning;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.jandilog.judgment.domain.JudgmentWeek;
import com.jandilog.testsupport.admin.AdminIntegrationTest;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;

// 경고의 팀 카테고리 표시 경계 (기능명세서 2·6·8장, AC-02 ③, PR-01 ⑨, E-43): 추방·팀 삭제·복구 뒤 카테고리,
// 비공개·삭제된 팀 묶기, 열람자와 상관없이 같은 모양. 정상 경로는 WarningHistoryIntegrationTest가 덮는다
class WarningCategoryBoundaryIntegrationTest extends AdminIntegrationTest {

	private static final LocalDate W = JudgmentWeek.mondayOf(LocalDate.of(2043, 3, 2));
	private static final LocalDateTime DELETED_AT = LocalDateTime.of(2043, 1, 1, 0, 0);

	private static final String HISTORY = """
			query {
			  myWarningHistory { onHold nextCursor items { weekStart categories status } }
			}
			""";
	private static final String PROFILE = """
			query($id: ID!) {
			  profile(memberId: $id) {
			    warning { onHold warningCount penaltyTarget }
			    teamWarnings { onHold teams { name count } }
			    judgmentHistory { nextCursor items { weekStart weekEnd result verifiedDays recordCount } }
			  }
			}
			""";
	private static final String RESTORE = """
			mutation($id: ID!) { restoreWarning(warningId: $id) { warningId onHold warningCount } }
			""";

	@Test
	@DisplayName("추방으로 일부 카테고리만 빠진 경고는 남은 팀만 이력과 팀별 경고에 나오고 자진 탈퇴한 팀은 그대로 남는다")
	void removedCategoriesAreHiddenButSelfLeaveKeepsThem() {
		String kickedTeamName = data.uniqueName("추방팀");
		String keptTeamName = data.uniqueName("남은팀");
		String leftTeamName = data.uniqueName("탈퇴팀");
		long leader = activeMember();
		long kickedTeam = data.team(kickedTeamName, true, leader);
		long keptTeam = data.team(keptTeamName, true, leader);
		long leftTeam = data.team(leftTeamName, true, leader);
		long member = activeMember();
		// 경고 1: 추방된 팀 카테고리만 빠졌다
		long kicked = data.failWithWarning(member, week(0), kickedTeam, keptTeam);
		jdbc.update("update warning_team set removed_at = ? where warning_id = ? and team_id = ?", DELETED_AT, kicked,
				kickedTeam);
		// 경고 2: 자진 탈퇴는 경고를 유지하므로 카테고리가 그대로다
		data.failWithWarning(member, week(1), leftTeam);
		data.teamMember(leftTeam, member, LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2043, 2, 1, 0, 0));

		JsonNode items = history(member).path("items");

		assertThat(items).hasSize(2);
		assertThat(categories(items.get(0))).containsExactly(leftTeamName);
		assertThat(categories(items.get(1))).containsExactly(keptTeamName);
		JsonNode teams = profile(activeMember(), member).path("teamWarnings").path("teams");
		assertThat(teamCounts(teams)).containsOnly(Map.entry(keptTeamName, 1), Map.entry(leftTeamName, 1));
	}

	@Test
	@DisplayName("팀이 삭제돼 소프트 삭제된 경고는 이력에 안 나오고 복구하면 '삭제된 팀' 하나로 나온다 (E-43)")
	void restoredWarningWithoutAliveTeamShowsDeletedTeam() {
		long leader = activeMember();
		long deletedTeam = data.team(data.uniqueName("지운팀"), true, leader, DELETED_AT);
		long member = activeMember();
		long warning = data.failWithWarning(member, week(0), deletedTeam);
		softDelete(warning, "TEAM_DELETED");
		assertThat(history(member).path("items")).isEmpty();
		assertThat(profile(member, member).path("warning").path("warningCount").asInt()).isZero();

		dataOf(asAdmin(RESTORE, Map.of("id", Long.toString(warning))));

		JsonNode items = history(member).path("items");
		assertThat(items).hasSize(1);
		assertThat(items.get(0).path("status").asText()).isEqualTo("ACTIVE");
		assertThat(categories(items.get(0))).containsExactly("삭제된 팀");
		JsonNode profile = profile(activeMember(), member);
		assertThat(profile.path("warning").path("warningCount").asInt()).isEqualTo(1);
		assertThat(teamCounts(profile.path("teamWarnings").path("teams"))).containsOnly(Map.entry("삭제된 팀", 1));
	}

	@Test
	@DisplayName("복구하면 아직 있는 팀의 카테고리만 되살아나고 삭제된 팀은 돌아오지 않아 남은 팀 이름만 보인다")
	void restoreBringsBackOnlyAliveTeams() {
		String aliveName = data.uniqueName("산팀");
		long leader = activeMember();
		long aliveTeam = data.team(aliveName, true, leader);
		long deletedTeam = data.team(data.uniqueName("죽은팀"), true, leader, DELETED_AT);
		long member = activeMember();
		long warning = data.failWithWarning(member, week(0), aliveTeam, deletedTeam);
		softDelete(warning, "KICKED");

		dataOf(asAdmin(RESTORE, Map.of("id", Long.toString(warning))));

		JsonNode items = history(member).path("items");
		assertThat(items).hasSize(1);
		assertThat(categories(items.get(0))).containsExactly(aliveName);
		assertThat(teamCounts(profile(member, member).path("teamWarnings").path("teams")))
				.containsOnly(Map.entry(aliveName, 1));
	}

	@Test
	@DisplayName("비공개·삭제된 팀은 여러 팀이어도 이름 하나로 묶이고 공개 팀은 이름순이며 팀별 경고는 경고마다 한 번만 센다")
	void privateAndDeletedTeamsAreGroupedPerWarning() {
		String firstName = data.uniqueName("가팀");
		String secondName = data.uniqueName("나팀");
		long leader = activeMember();
		long second = data.team(secondName, true, leader);
		long first = data.team(firstName, true, leader);
		long privateOne = data.team(data.uniqueName("비밀하나"), false, leader);
		long privateTwo = data.team(data.uniqueName("비밀둘"), false, leader);
		long deletedOne = data.team(data.uniqueName("지움하나"), true, leader, DELETED_AT);
		long deletedTwo = data.team(data.uniqueName("지움둘"), true, leader, DELETED_AT);
		long member = activeMember();
		// 경고 1: 모든 종류가 섞여 붙은 경고. 경고 2: 비공개 팀 하나만 붙은 경고
		data.failWithWarning(member, week(0), second, first, privateOne, privateTwo, deletedOne, deletedTwo);
		data.failWithWarning(member, week(1), privateTwo);

		JsonNode items = history(member).path("items");

		assertThat(items).hasSize(2);
		assertThat(categories(items.get(0))).containsExactly("비공개 팀");
		assertThat(categories(items.get(1))).containsExactly(firstName, secondName, "비공개 팀", "삭제된 팀");
		JsonNode teams = profile(activeMember(), member).path("teamWarnings").path("teams");
		assertThat(names(teams)).containsExactly(firstName, secondName, "비공개 팀", "삭제된 팀");
		assertThat(teamCounts(teams)).containsOnly(Map.entry(firstName, 1), Map.entry(secondName, 1),
				Map.entry("비공개 팀", 2), Map.entry("삭제된 팀", 1));
	}

	@Test
	@DisplayName("누가 보든 프로필의 경고 · 팀별 경고 · 판정 이력은 같은 모양이고 비공개 팀 이름은 어디에도 실리지 않는다")
	void profileShapeIsTheSameForEveryViewer() {
		long leader = activeMember();
		String secretName = data.uniqueName("비밀팀");
		String openName = data.uniqueName("열린팀");
		long secretTeam = data.team(secretName, false, leader);
		data.teamMember(secretTeam, leader, LocalDateTime.of(2026, 1, 1, 0, 0), null);
		long openTeam = data.team(openName, true, leader);
		long member = activeMember();
		data.teamMember(secretTeam, member, LocalDateTime.of(2026, 1, 1, 0, 0), null);
		long teammate = activeMember();
		data.teamMember(secretTeam, teammate, LocalDateTime.of(2026, 1, 1, 0, 0), null);
		long stranger = activeMember();
		data.failWithWarning(member, week(0), openTeam, secretTeam);
		data.failWithWarning(member, week(1), secretTeam);

		GraphQlResponse byMember = asMember(member, PROFILE, Map.of("id", Long.toString(member)));
		JsonNode expected = dataOf(byMember).path("profile");
		assertThat(expected.path("warning").path("warningCount").asInt()).isEqualTo(2);
		assertThat(teamCounts(expected.path("teamWarnings").path("teams"))).containsOnly(Map.entry(openName, 1),
				Map.entry("비공개 팀", 2));

		List<GraphQlResponse> others = new ArrayList<>();
		for (long viewer : List.of(stranger, teammate, leader)) {
			others.add(asMember(viewer, PROFILE, Map.of("id", Long.toString(member))));
		}
		others.add(asAdmin(PROFILE, Map.of("id", Long.toString(member))));
		for (GraphQlResponse other : others) {
			assertThat(dataOf(other).path("profile")).isEqualTo(expected);
		}
		// 같은 팀원이든 팀장이든 관리자든 프로필 응답에는 비공개 팀의 실제 이름이 없다
		assertThat(byMember.rawBody()).doesNotContain(secretName);
		others.forEach(other -> assertThat(other.rawBody()).doesNotContain(secretName));
		assertThat(history(member).toString()).doesNotContain(secretName);
	}

	private static LocalDate week(int index) {
		return W.plusWeeks(index);
	}

	private JsonNode history(long memberId) {
		return dataOf(asMember(memberId, HISTORY, Map.of())).path("myWarningHistory");
	}

	private JsonNode profile(long viewerId, long targetId) {
		return dataOf(asMember(viewerId, PROFILE, Map.of("id", Long.toString(targetId)))).path("profile");
	}

	private static List<String> categories(JsonNode item) {
		List<String> categories = new ArrayList<>();
		item.path("categories").forEach(category -> categories.add(category.asText()));
		return categories;
	}

	private static List<String> names(JsonNode teams) {
		List<String> names = new ArrayList<>();
		teams.forEach(team -> names.add(team.path("name").asText()));
		return names;
	}

	private static Map<String, Integer> teamCounts(JsonNode teams) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		teams.forEach(team -> counts.put(team.path("name").asText(), team.path("count").asInt()));
		return counts;
	}

	// 모든 카테고리가 빠져 경고가 소프트 삭제된 상태를 만든다 (추방·팀 삭제)
	private void softDelete(long warningId, String reason) {
		jdbc.update("update warning set deleted_at = ?, delete_reason = ? where id = ?", DELETED_AT, reason,
				warningId);
		jdbc.update("update warning_team set removed_at = ? where warning_id = ?", DELETED_AT, warningId);
	}

}
