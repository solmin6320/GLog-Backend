package com.jandilog.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.databind.JsonNode;
import com.jandilog.judgment.domain.JudgmentWeek;
import com.jandilog.judgment.service.GrassCacheService;
import com.jandilog.testsupport.admin.AdminIntegrationTest;

// 프로필 주간 판정 이력 (PR-01 ⑩): 결과 값 · 수치 규칙, 이번 주 진행 중 행, 커서 페이지, 본인 · 타인 같은 모양
class ProfileJudgmentHistoryIntegrationTest extends AdminIntegrationTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private static final String HISTORY = """
			query($id: ID!, $after: String) {
			  profile(memberId: $id) {
			    judgmentHistory(after: $after) {
			      items { weekStart weekEnd result verifiedDays recordCount }
			      nextCursor
			    }
			  }
			}
			""";

	private static final String MY_HISTORY = """
			query {
			  myJudgmentHistory { items { weekStart status skipReason } nextCursor }
			}
			""";

	@Autowired
	private GrassCacheService grassCacheService;

	@Test
	@DisplayName("타인 프로필의 이력은 최근 주부터 이번 주 진행 중 행을 맨 위에 두고, 면제 · 제외 · 보류 주는 수치를 비운다")
	void showsInProgressWeekAndResultsForOthers() {
		long viewer = activeMember();
		long member = activeMember();
		LocalDate today = LocalDate.now(KST);
		LocalDate thisWeek = JudgmentWeek.mondayOf(today);
		long leader = activeMember();
		long team = data.team(data.uniqueName("이력팀"), true, leader);
		data.teamMember(team, member, LocalDateTime.of(2026, 1, 1, 9, 0), null);
		// 이번 주: 오늘 기록글 1개 + 잔디 캐시
		data.postIndexRecord(member, today);
		grassCacheService.refresh(member, members.find(member).orElseThrow().githubLogin());

		data.pass(member, thisWeek.minusWeeks(1));
		data.fail(member, thisWeek.minusWeeks(2));
		// 소급 면제로 면제가 된 주는 판정 수치가 남아 있어도 비워서 내보낸다
		data.judgment(member, thisWeek.minusWeeks(3), "EXEMPT", "PERSONAL_EXEMPTION", null, 0, 3, 1, false,
				thisWeek.minusWeeks(2).atTime(7, 0));
		data.firstWeek(member, thisWeek.minusWeeks(4));
		data.hold(member, thisWeek.minusWeeks(5), "API_ERROR", 3);

		JsonNode page = history(viewer, member, null);
		JsonNode items = page.path("items");

		assertThat(items).hasSize(6);
		assertRow(items.get(0), thisWeek, "IN_PROGRESS");
		// 오늘 기록글이 있고 캐시는 이번 주 월요일부터 오늘까지 매일 기여가 있다
		assertThat(items.get(0).path("verifiedDays").asInt()).isEqualTo((int) ChronoUnit.DAYS.between(thisWeek, today) + 1);
		assertThat(items.get(0).path("recordCount").asInt()).isEqualTo(1);

		assertRow(items.get(1), thisWeek.minusWeeks(1), "PASS");
		assertThat(items.get(1).path("verifiedDays").asInt()).isEqualTo(4);
		assertThat(items.get(1).path("recordCount").asInt()).isEqualTo(1);
		assertRow(items.get(2), thisWeek.minusWeeks(2), "FAIL");
		assertThat(items.get(2).path("verifiedDays").asInt()).isEqualTo(1);
		assertThat(items.get(2).path("recordCount").asInt()).isZero();
		assertRow(items.get(3), thisWeek.minusWeeks(3), "EXEMPT");
		assertRow(items.get(4), thisWeek.minusWeeks(4), "EXCLUDED");
		// 확정 전인 보류 주는 진행 중으로 나가고 사유는 내보내지 않는다
		assertRow(items.get(5), thisWeek.minusWeeks(5), "IN_PROGRESS");
		for (int i = 3; i <= 5; i++) {
			assertThat(items.get(i).path("verifiedDays").isNull()).isTrue();
			assertThat(items.get(i).path("recordCount").isNull()).isTrue();
		}
		assertThat(page.path("nextCursor").isNull()).isTrue();
	}

	@Test
	@DisplayName("이력은 커서 기반 20개로 나뉘고 본인이 보든 타인이 보든 같은 모양이며 팀이 없으면 진행 중 행이 없다")
	void pagesByCursorAndIsSameForSelfAndOthers() {
		long viewer = activeMember();
		long member = activeMember();
		LocalDate thisWeek = JudgmentWeek.mondayOf(LocalDate.now(KST));
		for (int i = 1; i <= 21; i++) {
			data.pass(member, thisWeek.minusWeeks(i));
		}

		JsonNode first = history(viewer, member, null);
		assertThat(first.path("items")).hasSize(20);
		assertRow(first.path("items").get(0), thisWeek.minusWeeks(1), "PASS");
		assertRow(first.path("items").get(19), thisWeek.minusWeeks(20), "PASS");
		String cursor = first.path("nextCursor").asText();
		assertThat(cursor).isNotBlank();

		JsonNode second = history(viewer, member, cursor);
		assertThat(second.path("items")).hasSize(1);
		assertRow(second.path("items").get(0), thisWeek.minusWeeks(21), "PASS");
		assertThat(second.path("nextCursor").isNull()).isTrue();

		// 본인이 봐도 같은 내용
		assertThat(history(member, member, null)).isEqualTo(first);
		assertThat(history(member, member, cursor)).isEqualTo(second);
		// 판정 기록이 없는 회원은 빈 목록이다
		JsonNode empty = history(viewer, viewer, null);
		assertThat(empty.path("items")).isEmpty();
		assertThat(empty.path("nextCursor").isNull()).isTrue();
	}

	@Test
	@DisplayName("팀이 없던 주(NO_TEAM)는 이력에서 빠지고 첫 참가 주는 남으며 빠진 행이 있어도 페이지는 20개와 나머지로 나뉜다")
	void hidesNoTeamWeeksWithoutBreakingPages() {
		long viewer = activeMember();
		long member = activeMember();
		LocalDate thisWeek = JudgmentWeek.mondayOf(LocalDate.now(KST));
		// 24주 중 2·21·22주 전이 NO_TEAM이라 보이는 행은 21개: 1, 3~20, 23, 24주 전. 21·22주 전이 페이지 경계에 걸친다
		for (int i = 1; i <= 24; i++) {
			LocalDate week = thisWeek.minusWeeks(i);
			if (i == 2 || i == 21 || i == 22) {
				noTeamWeek(member, week);
			}
			else if (i == 24) {
				data.firstWeek(member, week);
			}
			else {
				data.pass(member, week);
			}
		}

		JsonNode first = history(viewer, member, null);
		JsonNode firstItems = first.path("items");
		assertThat(firstItems).hasSize(20);
		assertRow(firstItems.get(0), thisWeek.minusWeeks(1), "PASS");
		assertRow(firstItems.get(1), thisWeek.minusWeeks(3), "PASS");
		assertRow(firstItems.get(18), thisWeek.minusWeeks(20), "PASS");
		assertRow(firstItems.get(19), thisWeek.minusWeeks(23), "PASS");
		String cursor = first.path("nextCursor").asText();
		assertThat(cursor).isNotBlank();

		JsonNode second = history(viewer, member, cursor);
		assertThat(second.path("items")).hasSize(1);
		assertRow(second.path("items").get(0), thisWeek.minusWeeks(24), "EXCLUDED");
		assertThat(second.path("items").get(0).path("verifiedDays").isNull()).isTrue();
		assertThat(second.path("nextCursor").isNull()).isTrue();

		// 한 번도 팀이 없던 회원은 NO_TEAM 행뿐이라 빈 목록이다. 내 주간 활동의 지난 판정에서는 계속 보인다
		long neverInTeam = activeMember();
		for (int i = 1; i <= 3; i++) {
			noTeamWeek(neverInTeam, thisWeek.minusWeeks(i));
		}
		JsonNode empty = history(viewer, neverInTeam, null);
		assertThat(empty.path("items")).isEmpty();
		assertThat(empty.path("nextCursor").isNull()).isTrue();
		JsonNode mine = dataOf(asMember(neverInTeam, MY_HISTORY, Map.of())).path("myJudgmentHistory");
		assertThat(mine.path("items")).hasSize(3);
		assertThat(mine.path("items").get(0).path("status").asText()).isEqualTo("EXCLUDED");
		assertThat(mine.path("items").get(0).path("skipReason").asText()).isEqualTo("NO_TEAM");
	}

	private void noTeamWeek(long memberId, LocalDate week) {
		data.judgment(memberId, week, "EXCLUDED", "NO_TEAM", null, 0, null, null, false, week.plusDays(7).atTime(7, 0));
	}

	private static void assertRow(JsonNode row, LocalDate weekStart, String result) {
		assertThat(row.path("weekStart").asText()).isEqualTo(weekStart.toString());
		assertThat(row.path("weekEnd").asText()).isEqualTo(weekStart.plusDays(6).toString());
		assertThat(row.path("result").asText()).isEqualTo(result);
	}

	private JsonNode history(long viewerId, long targetId, String after) {
		Map<String, Object> variables = after == null
				? Map.of("id", Long.toString(targetId))
				: Map.of("id", Long.toString(targetId), "after", after);
		return dataOf(asMember(viewerId, HISTORY, variables)).path("profile").path("judgmentHistory");
	}

}
