package com.jandilog.warning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.jandilog.common.pagination.CursorCodec;
import com.jandilog.judgment.domain.JudgmentWeek;
import com.jandilog.testsupport.admin.AdminIntegrationTest;

// 내 경고 이력(AC-02 ③)의 경계·예외: 20개 페이지 경계, 잘못된 커서, 접근 제어, 이행 완료 기준선(Q-13, E-58),
// 정정·소급 면제 뒤 이력, 판정 보류. 정상 경로는 WarningHistoryIntegrationTest가 덮는다
class WarningHistoryBoundaryIntegrationTest extends AdminIntegrationTest {

	private static final LocalDate W = JudgmentWeek.mondayOf(LocalDate.of(2044, 3, 7));
	// 소급 면제는 전체 회원에 걸리므로 다른 테스트가 쓰지 않는 먼 연도의 주차만 쓰고 끝나면 지운다
	private static final LocalDate X = JudgmentWeek.mondayOf(LocalDate.of(2052, 3, 3));
	private static final LocalDate PERIOD_FROM = LocalDate.of(2052, 1, 1);
	private static final LocalDate PERIOD_TO = LocalDate.of(2052, 12, 31);

	private static final String HISTORY = """
			query($after: String) {
			  myWarningHistory(after: $after) {
			    onHold nextCursor
			    items { weekStart weekEnd verifiedDays recordCount categories status }
			  }
			}
			""";
	private static final String PROFILE = """
			query($id: ID!) {
			  profile(memberId: $id) {
			    warning { onHold warningCount penaltyTarget }
			    teamWarnings { onHold teams { name count } }
			  }
			}
			""";
	private static final String CORRECT = """
			mutation($input: JudgmentCorrectionInput!) {
			  correctJudgment(input: $input) { judgmentId status onHold warningCount }
			}
			""";
	private static final String CREATE_PERIOD = """
			mutation($input: ExemptionPeriodInput!) { createExemptionPeriod(input: $input) { id weekStart } }
			""";
	private static final String DELETE_PERIOD = """
			mutation($id: ID!) { deleteExemptionPeriod(id: $id) }
			""";

	@BeforeEach
	void clearPeriods() {
		jdbc.update("delete from exemption_period where week_start between ? and ?", PERIOD_FROM, PERIOD_TO);
	}

	@AfterEach
	void tearDownPeriods() {
		// 면제 기간이 관리자 회원을 참조하므로 회원 정리보다 먼저 지운다
		jdbc.update("delete from exemption_period where week_start between ? and ?", PERIOD_FROM, PERIOD_TO);
	}

	// ---- 페이지 경계 ----

	@Test
	@DisplayName("경고가 정확히 20개면 한 페이지로 끝나고 nextCursor가 없다")
	void exactlyTwentyFitsOnePage() {
		long member = memberWithFails(20);

		JsonNode page = history(member, null);

		assertThat(page.path("items")).hasSize(20);
		assertThat(page.path("nextCursor").isNull()).isTrue();
		assertThat(weeks(page)).containsExactlyElementsOf(descendingWeeks(20));
	}

	@Test
	@DisplayName("40개는 20 + 20으로 끝나고 41개는 20 + 20 + 1로 나뉘며 페이지가 겹치거나 빠지지 않는다")
	void pagesStayContiguousAtTwentyBoundaries() {
		long forty = memberWithFails(40);
		List<JsonNode> fortyPages = allPages(forty);
		assertThat(fortyPages).extracting(page -> page.path("items").size()).containsExactly(20, 20);
		assertThat(fortyPages.get(1).path("nextCursor").isNull()).isTrue();
		assertThat(weeks(fortyPages)).containsExactlyElementsOf(descendingWeeks(40));

		long fortyOne = memberWithFails(41);
		List<JsonNode> pages = allPages(fortyOne);
		assertThat(pages).extracting(page -> page.path("items").size()).containsExactly(20, 20, 1);
		assertThat(pages.get(2).path("nextCursor").isNull()).isTrue();
		assertThat(weeks(pages)).containsExactlyElementsOf(descendingWeeks(41));
	}

	@Test
	@DisplayName("잘못된 커서(Base64가 아니거나 날짜가 아님)는 INVALID_INPUT으로 거부한다")
	void invalidCursorIsRejected() {
		long member = memberWithFails(2);

		for (String bad : List.of("!!!", "%%%%", CursorCodec.encode("not-a-date"), CursorCodec.encode("2044-13-45"),
				CursorCodec.encode("12345"))) {
			assertThat(asMember(member, HISTORY, Map.of("after", bad)).errorCode()).as("커서 " + bad)
					.isEqualTo("INVALID_INPUT");
		}
		// 거부한 뒤에도 정상 조회는 그대로다
		assertThat(history(member, null).path("items")).hasSize(2);
	}

	@Test
	@DisplayName("페이지 사이에 경고가 삭제되거나 새로 생겨도 커서가 주차 기준이라 둘째 페이지가 어긋나지 않는다")
	void cursorStaysStableWhenWarningsChangeBetweenPages() {
		long team = publicTeam("커서팀");
		long member = activeMember();
		List<Long> warnings = new ArrayList<>();
		for (int i = 0; i < 25; i++) {
			warnings.add(data.failWithWarning(member, W.plusWeeks(i), team));
		}
		JsonNode first = history(member, null);
		assertThat(weeks(first)).containsExactlyElementsOf(descendingWeeks(25).subList(0, 20));
		String cursor = first.path("nextCursor").asText();

		// 이미 본 주(10)와 아직 안 본 주(2)의 경고를 삭제하고, 가장 최근 주 뒤에 새 경고를 하나 더한다
		softDelete(warnings.get(10), team);
		softDelete(warnings.get(2), team);
		data.failWithWarning(member, W.plusWeeks(25), team);

		JsonNode second = history(member, cursor);

		assertThat(weeks(second)).containsExactly(week(4), week(3), week(1), week(0));
		assertThat(second.path("nextCursor").isNull()).isTrue();
		assertThat(statusByWeek(second).values()).containsOnly("ACTIVE");
	}

	// ---- 접근 제어 ----

	@Test
	@DisplayName("비로그인·승인 대기·거절 계정은 내 경고 이력을 못 읽고 관리자는 자기 이력을 읽는다")
	void onlyApprovedMembersCanReadTheirHistory() {
		long pending = pendingMember();
		long rejected = data.track(members.rejected());

		assertThat(graphQl.post(null, HISTORY, Map.of()).errorCode()).isEqualTo("UNAUTHENTICATED");
		assertThat(asMember(pending, HISTORY, Map.of()).errorCode()).isEqualTo("ACCOUNT_PENDING");
		assertThat(asMember(rejected, HISTORY, Map.of()).errorCode()).isEqualTo("ACCOUNT_REJECTED");

		JsonNode admin = dataOf(asAdmin(HISTORY)).path("myWarningHistory");
		assertThat(admin.path("onHold").asBoolean()).isFalse();
		assertThat(admin.path("items")).isEmpty();
	}

	@Test
	@DisplayName("회원 id를 넘기는 인자가 없어 다른 회원의 이력을 지정할 수 없고 서로의 경고가 섞이지 않는다")
	void historyIsBoundToTheCaller() {
		long first = activeMember();
		long second = activeMember();
		String firstTeam = data.uniqueName("갑팀");
		String secondTeam = data.uniqueName("을팀");
		long teamA = data.team(firstTeam, true, first);
		long teamB = data.team(secondTeam, true, second);
		data.failWithWarning(first, W, teamA);
		data.failWithWarning(second, W.plusWeeks(1), teamB);
		data.failWithWarning(second, W.plusWeeks(2), teamB);

		String withOtherId = "query { myWarningHistory(memberId: \"" + second + "\") { items { weekStart } } }";
		assertThat(asMember(first, withOtherId, Map.of()).hasErrors()).isTrue();

		JsonNode firstPage = history(first, null);
		assertThat(weeks(firstPage)).containsExactly(week(0));
		assertThat(firstPage.path("items").get(0).path("categories").get(0).asText()).isEqualTo(firstTeam);
		JsonNode secondPage = history(second, null);
		assertThat(weeks(secondPage)).containsExactly(week(2), week(1));
		assertThat(secondPage.path("items").get(0).path("categories").get(0).asText()).isEqualTo(secondTeam);
	}

	// ---- 이행 완료 기준선 (Q-13, E-58) ----

	@Test
	@DisplayName("이행 체크가 주 마지막 1초 전이면 그 주를 세고 1초 뒤면 이행 완료로 본다. 주 중간의 이행도 그 주를 센다")
	void fulfillmentBoundaryAroundWeekEnd() {
		long team = publicTeam("경계이행팀");
		long justBefore = activeMember();
		long justAfter = activeMember();
		long midWeek = activeMember();
		for (long member : List.of(justBefore, justAfter, midWeek)) {
			for (int i = 0; i < 4; i++) {
				data.failWithWarning(member, week(i), team);
			}
		}
		LocalDateTime endOfWeek2 = JudgmentWeek.endExclusive(week(2));
		data.fulfillment(justBefore, adminId, endOfWeek2.minusSeconds(1), week(1));
		data.fulfillment(justAfter, adminId, endOfWeek2.plusSeconds(1), week(2));
		data.fulfillment(midWeek, adminId, week(2).plusDays(2).atTime(12, 0), week(1));

		// 2주 차가 아직 끝나기 전의 이행: 0·1주만 이행 완료
		Map<String, String> before = statusByWeek(history(justBefore, null));
		assertThat(before).containsExactly(entry(week(3).toString(), "ACTIVE"), entry(week(2).toString(), "ACTIVE"),
				entry(week(1).toString(), "FULFILLED"), entry(week(0).toString(), "FULFILLED"));
		assertThat(statusByWeek(history(midWeek, null))).isEqualTo(before);
		assertThat(warningCount(justBefore)).isEqualTo(2);

		// 2주 차가 끝난 뒤의 이행: 2주 차까지 이행 완료
		Map<String, String> after = statusByWeek(history(justAfter, null));
		assertThat(after).containsExactly(entry(week(3).toString(), "ACTIVE"), entry(week(2).toString(), "FULFILLED"),
				entry(week(1).toString(), "FULFILLED"), entry(week(0).toString(), "FULFILLED"));
		assertThat(warningCount(justAfter)).isEqualTo(1);
	}

	@Test
	@DisplayName("이행 체크가 여러 번이면 가장 최근 이행이 기준선이라 앞선 이행 이후의 경고도 이행 완료가 된다")
	void latestFulfillmentIsTheBaseline() {
		long team = publicTeam("재이행팀");
		long member = activeMember();
		for (int i = 0; i < 6; i++) {
			data.failWithWarning(member, week(i), team);
		}
		data.fulfillment(member, adminId, week(2).atTime(9, 0), week(1));
		assertThat(statusByWeek(history(member, null))).containsExactly(entry(week(5).toString(), "ACTIVE"),
				entry(week(4).toString(), "ACTIVE"), entry(week(3).toString(), "ACTIVE"),
				entry(week(2).toString(), "ACTIVE"), entry(week(1).toString(), "FULFILLED"),
				entry(week(0).toString(), "FULFILLED"));

		data.fulfillment(member, adminId, week(5).atTime(9, 0), week(4));

		assertThat(statusByWeek(history(member, null))).containsExactly(entry(week(5).toString(), "ACTIVE"),
				entry(week(4).toString(), "FULFILLED"), entry(week(3).toString(), "FULFILLED"),
				entry(week(2).toString(), "FULFILLED"), entry(week(1).toString(), "FULFILLED"),
				entry(week(0).toString(), "FULFILLED"));
		assertThat(warningCount(member)).isEqualTo(1);
	}

	@Test
	@DisplayName("모두 이행 완료된 경고는 이력에 남지만 경고 수와 팀별 경고는 0이고 이후 새 경고만 센다")
	void fulfilledWarningsStayInHistoryButNotInCounts() {
		long team = publicTeam("이행완료팀");
		long member = activeMember();
		for (int i = 0; i < 3; i++) {
			data.failWithWarning(member, week(i), team);
		}
		data.fulfillment(member, adminId, week(3).atTime(9, 0), week(2));

		JsonNode page = history(member, null);

		assertThat(statusByWeek(page)).containsExactly(entry(week(2).toString(), "FULFILLED"),
				entry(week(1).toString(), "FULFILLED"), entry(week(0).toString(), "FULFILLED"));
		JsonNode profile = profile(member, member);
		assertThat(profile.path("warning").path("onHold").asBoolean()).isFalse();
		assertThat(profile.path("warning").path("warningCount").asInt()).isZero();
		assertThat(profile.path("warning").path("penaltyTarget").asBoolean()).isFalse();
		assertThat(profile.path("teamWarnings").path("teams")).isEmpty();

		// 이행 뒤에 받은 경고만 유효로 세어진다
		data.failWithWarning(member, week(3), team);
		JsonNode again = history(member, null);
		assertThat(statusByWeek(again).get(week(3).toString())).isEqualTo("ACTIVE");
		assertThat(warningCount(member)).isEqualTo(1);
		assertThat(profile(member, member).path("teamWarnings").path("teams")).hasSize(1);
	}

	@Test
	@DisplayName("삭제 표시된 경고뿐인 회원은 이력이 비고 nextCursor도 없다")
	void onlyDeletedWarningsGiveEmptyHistory() {
		long team = publicTeam("삭제만팀");
		long member = activeMember();
		long first = data.failWithWarning(member, week(0), team);
		long second = data.failWithWarning(member, week(1), team);
		softDelete(first, team);
		softDelete(second, team);

		JsonNode page = history(member, null);

		assertThat(page.path("onHold").asBoolean()).isFalse();
		assertThat(page.path("items")).isEmpty();
		assertThat(page.path("nextCursor").isNull()).isTrue();
		assertThat(warningCount(member)).isZero();
	}

	// ---- 정정·소급 면제 뒤 이력 ----

	@Test
	@DisplayName("정정으로 미달이 아니게 된 주는 이력에서 빠지고 남은 경고의 상태가 다시 계산되며 되돌리면 같은 경고가 다시 나온다")
	void correctionRewritesHistoryRowsAndStatuses() {
		long team = publicTeam("정정이력팀");
		long member = activeMember();
		data.failWithWarning(member, week(0), team);
		data.failWithWarning(member, week(1), team);
		data.pass(member, week(2));
		data.pass(member, week(3));
		// 0주 F, 1주 F, 2·3주 통과 -> 2주 연속 통과로 가장 오래된 0주가 차감된다
		assertThat(statusByWeek(history(member, null))).containsExactly(entry(week(1).toString(), "ACTIVE"),
				entry(week(0).toString(), "DEDUCTED"));
		assertHistoryMatchesProfile(member);

		// 0주를 통과로 정정: 0주 경고는 이력에서 빠지고 차감은 1주 경고가 맡는다
		correct(judgmentId(member, week(0)), "PASS");
		assertThat(statusByWeek(history(member, null))).containsExactly(entry(week(1).toString(), "DEDUCTED"));
		assertHistoryMatchesProfile(member);

		// 다시 미달로 되돌리면 새 경고를 만들지 않고 원래 경고가 같은 자리로 돌아온다
		correct(judgmentId(member, week(0)), "FAIL");
		assertThat(statusByWeek(history(member, null))).containsExactly(entry(week(1).toString(), "ACTIVE"),
				entry(week(0).toString(), "DEDUCTED"));
		assertThat(jdbc.queryForObject("select count(*) from warning where member_id = ?", Integer.class, member))
				.isEqualTo(2);
		assertHistoryMatchesProfile(member);

		// 1주를 면제로 정정: 1주 경고는 빠지고 0주 경고가 다시 차감된다
		correct(judgmentId(member, week(1)), "EXEMPT");
		assertThat(statusByWeek(history(member, null))).containsExactly(entry(week(0).toString(), "DEDUCTED"));
		assertHistoryMatchesProfile(member);
	}

	@Test
	@DisplayName("면제 주를 미달로 정정하면 팀 카테고리가 붙은 경고가 이력에 생기고 사유 수치는 null이다")
	void correctingExemptWeekToFailShowsUnknownNumbers() {
		String teamName = data.uniqueName("면제정정팀");
		long team = data.team(teamName, true, activeMember());
		long member = activeMember();
		long exempt = data.exempt(member, week(0));
		data.judgmentTeam(exempt, team);
		assertThat(history(member, null).path("items")).isEmpty();

		correct(exempt, "FAIL");

		JsonNode items = history(member, null).path("items");
		assertThat(items).hasSize(1);
		JsonNode row = items.get(0);
		assertThat(row.path("weekStart").asText()).isEqualTo(week(0).toString());
		assertThat(row.path("status").asText()).isEqualTo("ACTIVE");
		assertThat(row.path("verifiedDays").isNull()).isTrue();
		assertThat(row.path("recordCount").isNull()).isTrue();
		assertThat(row.path("categories").get(0).asText()).isEqualTo(teamName);
		assertHistoryMatchesProfile(member);
	}

	@Test
	@DisplayName("소급 면제가 걸린 주의 경고는 이력에서 빠지고 면제 기간을 지워도 되돌아오지 않는다")
	void retroactiveExemptionHidesRowAndStaysAfterPeriodDelete() {
		long team = publicTeam("소급이력팀");
		long member = activeMember();
		long bystander = activeMember();
		data.failWithWarning(member, X, team);
		data.failWithWarning(member, X.plusWeeks(1), team);
		data.failWithWarning(bystander, X.plusWeeks(1), team);
		assertThat(statusByWeek(history(member, null))).hasSize(2);

		long period = dataOf(asAdmin(CREATE_PERIOD, Map.of("input", Map.of("weekStart", X.toString(), "reason",
				"시험 기간")))).path("createExemptionPeriod").path("id").asLong();

		assertThat(statusByWeek(history(member, null))).containsExactly(entry(X.plusWeeks(1).toString(), "ACTIVE"));
		assertThat(warningCount(member)).isEqualTo(1);
		// 그 주 판정이 없는 회원은 영향이 없다
		assertThat(statusByWeek(history(bystander, null))).containsExactly(entry(X.plusWeeks(1).toString(), "ACTIVE"));

		dataOf(asAdmin(DELETE_PERIOD, Map.of("id", Long.toString(period))));

		assertThat(statusByWeek(history(member, null))).containsExactly(entry(X.plusWeeks(1).toString(), "ACTIVE"));
		assertThat(warningCount(member)).isEqualTo(1);
	}

	@Test
	@DisplayName("이행 체크 이전 주를 소급 면제하면 이력에서 그 행만 빠지고 이행 이후 경고 수는 그대로다 (E-58)")
	void retroactiveExemptionBeforeFulfillmentKeepsCounts() {
		long team = publicTeam("고정점이력팀");
		long member = activeMember();
		data.failWithWarning(member, X, team);
		data.failWithWarning(member, X.plusWeeks(1), team);
		data.fulfillment(member, adminId, X.plusWeeks(2).atTime(9, 0), X.plusWeeks(1));
		data.failWithWarning(member, X.plusWeeks(2), team);
		assertThat(warningCount(member)).isEqualTo(1);

		dataOf(asAdmin(CREATE_PERIOD, Map.of("input", Map.of("weekStart", X.toString(), "reason", "시험 기간"))));

		assertThat(statusByWeek(history(member, null))).containsExactly(entry(X.plusWeeks(2).toString(), "ACTIVE"),
				entry(X.plusWeeks(1).toString(), "FULFILLED"));
		assertThat(warningCount(member)).isEqualTo(1);
		assertHistoryMatchesProfile(member);
	}

	// ---- 판정 보류 ----

	@Test
	@DisplayName("판정 보류가 남은 회원은 이력·프로필 경고 모두 계산하지 않고 보류가 풀리면 정상으로 돌아온다 (Q-03)")
	void holdBlocksHistoryUntilResolved() {
		long team = publicTeam("보류경계팀");
		long member = activeMember();
		data.failWithWarning(member, week(0), team);
		data.failWithWarning(member, week(1), team);
		long hold = data.hold(member, week(2), "API_ERROR", 3);

		JsonNode held = history(member, null);
		assertThat(held.path("onHold").asBoolean()).isTrue();
		assertThat(held.path("items")).isEmpty();
		assertThat(held.path("nextCursor").isNull()).isTrue();
		JsonNode heldProfile = profile(member, member);
		assertThat(heldProfile.path("warning").path("onHold").asBoolean()).isTrue();
		assertThat(heldProfile.path("warning").path("warningCount").isNull()).isTrue();
		assertThat(heldProfile.path("teamWarnings").path("onHold").asBoolean()).isTrue();
		assertThat(heldProfile.path("teamWarnings").path("teams")).isEmpty();

		// 재시도로 보류가 통과로 확정됐다고 보고 같은 조회를 다시 한다
		jdbc.update("update weekly_judgment set status = 'PASS', hold_reason = null, verified_days = 4,"
				+ " record_count = 1, judged_at = ? where id = ?", week(3).atTime(7, 0), hold);

		JsonNode resolved = history(member, null);
		assertThat(resolved.path("onHold").asBoolean()).isFalse();
		assertThat(statusByWeek(resolved)).containsExactly(entry(week(1).toString(), "ACTIVE"),
				entry(week(0).toString(), "ACTIVE"));
		assertThat(profile(member, member).path("warning").path("warningCount").asInt()).isEqualTo(2);
	}

	// ---- 도우미 ----

	private static LocalDate week(int index) {
		return W.plusWeeks(index);
	}

	private long publicTeam(String prefix) {
		return data.team(data.uniqueName(prefix), true, activeMember());
	}

	private long memberWithFails(int count) {
		long team = publicTeam("경계팀");
		long member = activeMember();
		for (int i = 0; i < count; i++) {
			data.failWithWarning(member, week(i), team);
		}
		return member;
	}

	private JsonNode history(long memberId, String after) {
		Map<String, Object> variables = after == null ? Map.of() : Map.of("after", after);
		return dataOf(asMember(memberId, HISTORY, variables)).path("myWarningHistory");
	}

	private List<JsonNode> allPages(long memberId) {
		List<JsonNode> pages = new ArrayList<>();
		String cursor = null;
		do {
			JsonNode page = history(memberId, cursor);
			pages.add(page);
			cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText();
		} while (cursor != null && pages.size() < 10);
		return pages;
	}

	private static List<LocalDate> weeks(JsonNode page) {
		List<LocalDate> weeks = new ArrayList<>();
		page.path("items").forEach(item -> weeks.add(LocalDate.parse(item.path("weekStart").asText())));
		return weeks;
	}

	private static List<LocalDate> weeks(List<JsonNode> pages) {
		List<LocalDate> weeks = new ArrayList<>();
		pages.forEach(page -> weeks.addAll(weeks(page)));
		return weeks;
	}

	private static List<LocalDate> descendingWeeks(int count) {
		List<LocalDate> weeks = new ArrayList<>();
		for (int i = count - 1; i >= 0; i--) {
			weeks.add(week(i));
		}
		return weeks;
	}

	// 주차(최근 순) -> 상태
	private static Map<String, String> statusByWeek(JsonNode page) {
		Map<String, String> result = new LinkedHashMap<>();
		page.path("items").forEach(item -> result.put(item.path("weekStart").asText(), item.path("status").asText()));
		return result;
	}

	private JsonNode profile(long viewerId, long targetId) {
		return dataOf(asMember(viewerId, PROFILE, Map.of("id", Long.toString(targetId)))).path("profile");
	}

	private int warningCount(long memberId) {
		return profile(memberId, memberId).path("warning").path("warningCount").asInt();
	}

	// 이력의 유효 행 수가 프로필 경고 합계와 같다 (같은 재계산 결과를 쓴다)
	private void assertHistoryMatchesProfile(long memberId) {
		long active = statusByWeek(history(memberId, null)).values().stream().filter("ACTIVE"::equals).count();
		assertThat(warningCount(memberId)).isEqualTo((int) active);
	}

	private void softDelete(long warningId, long teamId) {
		LocalDateTime at = LocalDateTime.of(2044, 1, 1, 0, 0);
		jdbc.update("update warning set deleted_at = ?, delete_reason = 'KICKED' where id = ?", at, warningId);
		jdbc.update("update warning_team set removed_at = ? where warning_id = ? and team_id = ?", at, warningId,
				teamId);
	}

	private long judgmentId(long memberId, LocalDate week) {
		Long id = jdbc.queryForObject("select id from weekly_judgment where member_id = ? and week_start = ?",
				Long.class, memberId, week);
		return id == null ? 0 : id;
	}

	private void correct(long judgmentId, String status) {
		dataOf(asAdmin(CORRECT, Map.of("input", Map.of("judgmentId", Long.toString(judgmentId), "status", status,
				"reason", "경계 테스트 정정"))));
	}

}
