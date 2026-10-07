package com.jandilog.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.jandilog.common.pagination.CursorCodec;
import com.jandilog.judgment.domain.JudgmentWeek;
import com.jandilog.testsupport.admin.AdminIntegrationTest;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;

// 프로필 주간 판정 이력(PR-01 ⑩)의 경계·예외: 진행 중 행과 20개 페이지, 진행 중 행이 없는 경우, NO_TEAM 숨김과 페이지,
// 정정으로 다시 보이는 행, 잘못된 커서, 접근 제어. 정상 경로는 ProfileJudgmentHistoryIntegrationTest가 덮는다
class ProfileJudgmentHistoryBoundaryIntegrationTest extends AdminIntegrationTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final LocalDateTime JOINED = LocalDateTime.of(2026, 1, 1, 0, 0);

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
	private static final String HISTORY_AND_WARNINGS = """
			query($id: ID!) {
			  profile(memberId: $id) {
			    judgmentHistory { items { weekStart } }
			    teamWarnings { onHold teams { name count } }
			  }
			}
			""";
	private static final String CORRECT = """
			mutation($input: JudgmentCorrectionInput!) {
			  correctJudgment(input: $input) { judgmentId status }
			}
			""";

	// ---- 진행 중 행과 페이지 ----

	@Test
	@DisplayName("이번 주 진행 중 행은 20개에 세지 않아 첫 페이지가 21행이고 20 · 21 · 40개 경계에서 페이지가 어긋나지 않는다")
	void inProgressRowIsNotCountedInTwentyRowPages() {
		LocalDate thisWeek = thisWeek();
		long viewer = activeMember();

		// 정확히 20개: 진행 중 행 + 20행이 한 페이지로 끝난다
		long exact = memberInTeamWithPasses(20);
		JsonNode single = history(viewer, exact, null);
		assertThat(single.path("items")).hasSize(21);
		assertRow(single.path("items").get(0), thisWeek, "IN_PROGRESS");
		assertRow(single.path("items").get(1), thisWeek.minusWeeks(1), "PASS");
		assertRow(single.path("items").get(20), thisWeek.minusWeeks(20), "PASS");
		assertThat(single.path("nextCursor").isNull()).isTrue();

		// 21개: 둘째 페이지에는 진행 중 행 없이 가장 오래된 1행만 있다
		long over = memberInTeamWithPasses(21);
		JsonNode first = history(viewer, over, null);
		assertThat(first.path("items")).hasSize(21);
		assertRow(first.path("items").get(0), thisWeek, "IN_PROGRESS");
		assertRow(first.path("items").get(20), thisWeek.minusWeeks(20), "PASS");
		String cursor = first.path("nextCursor").asText();
		assertThat(cursor).isNotBlank();
		JsonNode second = history(viewer, over, cursor);
		assertThat(second.path("items")).hasSize(1);
		assertRow(second.path("items").get(0), thisWeek.minusWeeks(21), "PASS");
		assertThat(second.path("nextCursor").isNull()).isTrue();

		// 40개: 진행 중 행 + 20행, 다음 20행으로 끝난다
		long forty = memberInTeamWithPasses(40);
		JsonNode fortyFirst = history(viewer, forty, null);
		assertThat(fortyFirst.path("items")).hasSize(21);
		JsonNode fortySecond = history(viewer, forty, fortyFirst.path("nextCursor").asText());
		assertThat(fortySecond.path("items")).hasSize(20);
		assertRow(fortySecond.path("items").get(0), thisWeek.minusWeeks(21), "PASS");
		assertRow(fortySecond.path("items").get(19), thisWeek.minusWeeks(40), "PASS");
		assertThat(fortySecond.path("nextCursor").isNull()).isTrue();

		// 관리자·본인이 봐도 같은 페이지다
		assertThat(historyAsAdmin(over, null)).isEqualTo(first);
		assertThat(historyAsAdmin(over, cursor)).isEqualTo(second);
		assertThat(history(over, over, cursor)).isEqualTo(second);
	}

	@Test
	@DisplayName("팀을 떠난 회원 · 이번 주 개인 면제가 승인된 회원 · 이번 주가 첫 참가 주인 회원은 진행 중 행이 없고 승인 대기만이면 있다")
	void inProgressRowIsMissingWhenThisWeekIsNotJudged() {
		LocalDate thisWeek = thisWeek();
		long viewer = activeMember();
		long leader = activeMember();
		long team = data.team(data.uniqueName("진행중팀"), true, leader);

		// 팀을 떠난 회원: 이후 주는 NO_TEAM이라 숨고, 떠나기 전 통과 이력만 남는다
		long left = activeMember();
		data.teamMember(team, left, JOINED, LocalDateTime.of(2026, 2, 1, 0, 0));
		data.pass(left, thisWeek.minusWeeks(5));
		data.pass(left, thisWeek.minusWeeks(4));
		for (int i = 1; i <= 3; i++) {
			noTeamWeek(left, thisWeek.minusWeeks(i));
		}
		JsonNode leftPage = history(viewer, left, null);
		assertThat(results(leftPage)).containsExactly("PASS", "PASS");
		assertThat(weeks(leftPage)).containsExactly(thisWeek.minusWeeks(4), thisWeek.minusWeeks(5));

		// 이번 주 개인 면제가 승인된 회원: 판정 대상이 아니라 진행 중 행이 없다
		long exempt = activeMember();
		data.teamMember(team, exempt, JOINED, null);
		data.pass(exempt, thisWeek.minusWeeks(1));
		insertPersonalExemption(exempt, leader, thisWeek, "APPROVED");
		assertThat(results(history(viewer, exempt, null))).containsExactly("PASS");

		// 승인 대기만 있는 회원: 아직 면제가 아니라 진행 중 행이 있다
		long pending = activeMember();
		data.teamMember(team, pending, JOINED, null);
		data.pass(pending, thisWeek.minusWeeks(1));
		insertPersonalExemption(pending, leader, thisWeek, "PENDING");
		assertThat(results(history(viewer, pending, null))).containsExactly("IN_PROGRESS", "PASS");

		// 이번 주가 첫 팀 참가 주인 회원: 첫 참가 주는 판정하지 않아 진행 중 행이 없다
		long firstWeek = activeMember();
		data.teamMember(team, firstWeek, JOINED, null);
		data.pass(firstWeek, thisWeek.minusWeeks(1));
		jdbc.update("update member set first_team_joined_at = ? where id = ?", thisWeek.atTime(12, 0), firstWeek);
		assertThat(results(history(viewer, firstWeek, null))).containsExactly("PASS");
	}

	@Test
	@DisplayName("잘못된 커서(Base64가 아니거나 날짜가 아님)는 INVALID_INPUT으로 거부한다")
	void invalidCursorIsRejected() {
		long viewer = activeMember();
		long member = memberInTeamWithPasses(2);

		for (String bad : List.of("!!!", "%%%%", CursorCodec.encode("not-a-date"), CursorCodec.encode("2044-13-45"))) {
			assertThat(asMember(viewer, HISTORY, variables(member, bad)).errorCode()).as("커서 " + bad)
					.isEqualTo("INVALID_INPUT");
		}
		assertThat(history(viewer, member, null).path("items")).hasSize(3);
	}

	// ---- 팀 없음(NO_TEAM) 숨김과 페이지 ----

	@Test
	@DisplayName("최근 20주 넘게 전부 NO_TEAM이어도 빈 페이지 대신 그 이전 판정을 이어 주고 숨은 행 때문에 다음 페이지가 생기지 않는다")
	void hiddenRowsNeverCreateEmptyOrPhantomPages() {
		LocalDate thisWeek = thisWeek();
		long viewer = activeMember();

		// 첫 페이지 전체가 숨는 경우: 최근 25주 NO_TEAM, 그 이전 3주 통과
		long allHiddenFirst = activeMember();
		for (int i = 1; i <= 25; i++) {
			noTeamWeek(allHiddenFirst, thisWeek.minusWeeks(i));
		}
		for (int i = 26; i <= 28; i++) {
			data.pass(allHiddenFirst, thisWeek.minusWeeks(i));
		}
		JsonNode page = history(viewer, allHiddenFirst, null);
		assertThat(weeks(page)).containsExactly(thisWeek.minusWeeks(26), thisWeek.minusWeeks(27),
				thisWeek.minusWeeks(28));
		assertThat(page.path("nextCursor").isNull()).isTrue();

		// 보이는 행이 정확히 20개이고 그보다 오래된 행은 전부 숨는 경우: 다음 페이지가 없다
		long hiddenAfter = activeMember();
		for (int i = 1; i <= 20; i++) {
			data.pass(hiddenAfter, thisWeek.minusWeeks(i));
		}
		for (int i = 21; i <= 25; i++) {
			noTeamWeek(hiddenAfter, thisWeek.minusWeeks(i));
		}
		JsonNode exact = history(viewer, hiddenAfter, null);
		assertThat(exact.path("items")).hasSize(20);
		assertThat(exact.path("nextCursor").isNull()).isTrue();

		// 숨는 행이 두 페이지 사이를 가르는 경우: 둘째 페이지는 숨는 행을 건너뛴 다음 행에서 시작한다
		long hiddenBetween = activeMember();
		for (int i = 1; i <= 20; i++) {
			data.pass(hiddenBetween, thisWeek.minusWeeks(i));
		}
		for (int i = 21; i <= 29; i++) {
			noTeamWeek(hiddenBetween, thisWeek.minusWeeks(i));
		}
		data.pass(hiddenBetween, thisWeek.minusWeeks(30));
		JsonNode firstPage = history(viewer, hiddenBetween, null);
		assertThat(firstPage.path("items")).hasSize(20);
		JsonNode secondPage = history(viewer, hiddenBetween, firstPage.path("nextCursor").asText());
		assertThat(weeks(secondPage)).containsExactly(thisWeek.minusWeeks(30));
		assertThat(secondPage.path("nextCursor").isNull()).isTrue();
	}

	@Test
	@DisplayName("정정으로 팀 없음 주가 면제가 되면 이력에 다시 나타나고 수치는 비어 있다")
	void noTeamWeekReappearsWhenCorrectedToExempt() {
		LocalDate thisWeek = thisWeek();
		long viewer = activeMember();
		long member = activeMember();
		long hidden = noTeamWeek(member, thisWeek.minusWeeks(2));
		noTeamWeek(member, thisWeek.minusWeeks(3));
		assertThat(history(viewer, member, null).path("items")).isEmpty();

		dataOf(asAdmin(CORRECT, Map.of("input", Map.of("judgmentId", Long.toString(hidden), "status", "EXEMPT",
				"reason", "팀 없음 주 면제 처리"))));

		JsonNode items = history(viewer, member, null).path("items");
		assertThat(items).hasSize(1);
		assertRow(items.get(0), thisWeek.minusWeeks(2), "EXEMPT");
		assertThat(items.get(0).path("verifiedDays").isNull()).isTrue();
		assertThat(items.get(0).path("recordCount").isNull()).isTrue();
	}

	// ---- 접근 제어 ----

	@Test
	@DisplayName("비로그인 · 승인 대기 · 거절 계정은 프로필을 못 보고 승인되지 않은 회원의 이력과 팀별 경고는 새지 않는다")
	void historyIsHiddenFromUnapprovedViewersAndAboutUnapprovedMembers() {
		LocalDate thisWeek = thisWeek();
		long viewer = activeMember();
		long active = memberInTeamWithPasses(1);
		long pendingViewer = pendingMember();
		long rejectedViewer = data.track(members.rejected());

		assertThat(graphQl.post(null, HISTORY, variables(active, null)).errorCode()).isEqualTo("UNAUTHENTICATED");
		assertThat(asMember(pendingViewer, HISTORY, variables(active, null)).errorCode())
				.isEqualTo("ACCOUNT_PENDING");
		assertThat(asMember(rejectedViewer, HISTORY, variables(active, null)).errorCode())
				.isEqualTo("ACCOUNT_REJECTED");

		// 승인 대기·거절 회원에게 이력이 있어도 없는 내용으로 본다
		long pendingTarget = pendingMember();
		long rejectedTarget = data.track(members.rejected());
		data.pass(pendingTarget, thisWeek.minusWeeks(1));
		data.pass(rejectedTarget, thisWeek.minusWeeks(1));
		for (long target : List.of(pendingTarget, rejectedTarget, 999_999_999_999L)) {
			GraphQlResponse byHistory = asMember(viewer, HISTORY, variables(target, null));
			assertThat(byHistory.errorCode()).as("이력 대상 " + target).isEqualTo("NOT_FOUND");
			assertThat(byHistory.dataIsNull()).isTrue();
			GraphQlResponse byWarnings = asMember(viewer, HISTORY_AND_WARNINGS, Map.of("id", Long.toString(target)));
			assertThat(byWarnings.errorCode()).as("팀별 경고 대상 " + target).isEqualTo("NOT_FOUND");
			assertThat(byWarnings.dataIsNull()).isTrue();
		}
	}

	// ---- 도우미 ----

	private static LocalDate thisWeek() {
		return JudgmentWeek.mondayOf(LocalDate.now(KST));
	}

	// 지금 공개 팀 소속이고 지난 n주가 통과인 회원
	private long memberInTeamWithPasses(int count) {
		long team = data.team(data.uniqueName("이력경계팀"), true, activeMember());
		long member = activeMember();
		data.teamMember(team, member, JOINED, null);
		for (int i = 1; i <= count; i++) {
			data.pass(member, thisWeek().minusWeeks(i));
		}
		return member;
	}

	private long noTeamWeek(long memberId, LocalDate week) {
		return data.judgment(memberId, week, "EXCLUDED", "NO_TEAM", null, 0, null, null, false,
				week.plusDays(7).atTime(7, 0));
	}

	private void insertPersonalExemption(long memberId, long requestedBy, LocalDate week, String status) {
		jdbc.update("insert into personal_exemption (member_id, requested_by, week_start, status, reason, responded_at,"
				+ " created_at) values (?, ?, ?, ?, '시험 기간', ?, ?)", memberId, requestedBy, week, status,
				"APPROVED".equals(status) ? LocalDateTime.now() : null, LocalDateTime.now());
	}

	private static Map<String, Object> variables(long targetId, String after) {
		return after == null
				? Map.of("id", Long.toString(targetId))
				: Map.of("id", Long.toString(targetId), "after", after);
	}

	private JsonNode history(long viewerId, long targetId, String after) {
		return dataOf(asMember(viewerId, HISTORY, variables(targetId, after))).path("profile").path("judgmentHistory");
	}

	private JsonNode historyAsAdmin(long targetId, String after) {
		return dataOf(asAdmin(HISTORY, variables(targetId, after))).path("profile").path("judgmentHistory");
	}

	private static List<String> results(JsonNode page) {
		List<String> results = new ArrayList<>();
		page.path("items").forEach(item -> results.add(item.path("result").asText()));
		return results;
	}

	private static List<LocalDate> weeks(JsonNode page) {
		List<LocalDate> weeks = new ArrayList<>();
		page.path("items").forEach(item -> weeks.add(LocalDate.parse(item.path("weekStart").asText())));
		return weeks;
	}

	private static void assertRow(JsonNode row, LocalDate weekStart, String result) {
		assertThat(row.path("weekStart").asText()).isEqualTo(weekStart.toString());
		assertThat(row.path("weekEnd").asText()).isEqualTo(weekStart.plusDays(6).toString());
		assertThat(row.path("result").asText()).isEqualTo(result);
	}

}
