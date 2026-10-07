package com.jandilog.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.jandilog.common.time.KstFormats;
import com.jandilog.judgment.domain.JudgmentWeek;
import com.jandilog.testsupport.admin.AdminIntegrationTest;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;

// 관리자 대시보드: 처리 대기 요약, 주차별 판정 결과(집계 · 검색 · 필터 · 페이지), 접근 제어 (AD-01 ⑤~⑧)
// 다른 테스트의 판정과 섞이지 않게 먼 미래의 주와 고정 시계를 쓴다
class AdminDashboardIntegrationTest extends AdminIntegrationTest {

	// 이번 월요일 판정의 대상 주가 WEEK가 되도록 시계를 다음 주 수요일 오전에 맞춘다
	private static final LocalDate WEEK = JudgmentWeek.mondayOf(LocalDate.of(2041, 3, 6));

	private static final String SUMMARY = """
			{ adminDashboardSummary { judgedWeekStart pendingMembers failedMembers penaltyTargets pendingExemptions } }
			""";

	private static final String JUDGMENTS = """
			query($weekStart: String, $keyword: String, $filter: AdminJudgmentFilter, $after: String) {
			  adminJudgments(weekStart: $weekStart, keyword: $keyword, filter: $filter, after: $after) {
			    weekStart weekEnd executedAt passCount failCount exemptCount firstWeekCount holdCount nextCursor
			    items { judgmentId member { id nickname githubLogin } status skipReason holdReason verifiedDays
			            recordCount warningCount corrected manualHold }
			  }
			}
			""";

	@BeforeEach
	void fixClock() {
		clock.fixAt(WEEK.plusWeeks(1).plusDays(2).atTime(10, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant());
	}

	@Test
	@DisplayName("요약은 이번 주 미달 · 벌칙 대상 · 승인 대기 · 면제 요청 대기 건수를 센다")
	void summaryCountsPendingWork() {
		JsonNode before = dataOf(asAdmin(SUMMARY)).path("adminDashboardSummary");
		assertThat(before.path("judgedWeekStart").asText()).isEqualTo(WEEK.toString());

		long failedA = activeMember();
		long failedB = activeMember();
		long passed = activeMember();
		data.fail(failedA, WEEK);
		data.fail(failedB, WEEK);
		data.pass(passed, WEEK);
		pendingMember();
		pendingMember();
		data.personalExemptionPending(passed, adminId, WEEK.plusWeeks(5));

		// 경고 3개가 쌓인 회원 한 명 (WEEK의 미달도 이번 주 미달에 함께 센다)
		long penalty = activeMember();
		long leader = activeMember();
		long team = data.team(data.uniqueName("벌칙팀"), true, leader);
		data.failWithWarning(penalty, WEEK.minusWeeks(2), team);
		data.failWithWarning(penalty, WEEK.minusWeeks(1), team);
		data.failWithWarning(penalty, WEEK, team);

		JsonNode after = dataOf(asAdmin(SUMMARY)).path("adminDashboardSummary");
		assertThat(after.path("failedMembers").asInt()).isEqualTo(before.path("failedMembers").asInt() + 3);
		assertThat(after.path("penaltyTargets").asInt()).isGreaterThanOrEqualTo(before.path("penaltyTargets").asInt() + 1);
		assertThat(after.path("pendingMembers").asInt()).isGreaterThanOrEqualTo(before.path("pendingMembers").asInt() + 2);
		assertThat(after.path("pendingExemptions").asInt()).isGreaterThanOrEqualTo(before.path("pendingExemptions").asInt() + 1);
	}

	@Test
	@DisplayName("판정 결과는 그 주의 집계와 회원별 행을 보여주고 사람이 처리할 보류만 센다")
	void judgmentsShowWeekAggregatesAndRows() {
		long passA = activeMember();
		long passB = activeMember();
		long failed = activeMember();
		long exempt = activeMember();
		long first = activeMember();
		long mismatch = activeMember();
		long autoRetry = activeMember();
		data.pass(passA, WEEK);
		data.pass(passB, WEEK);
		long failedJudgment = data.fail(failed, WEEK);
		data.exempt(exempt, WEEK);
		data.firstWeek(first, WEEK);
		data.hold(mismatch, WEEK, "IDENTITY_MISMATCH", 0);
		data.hold(autoRetry, WEEK, "API_ERROR", 1);
		long leader = activeMember();
		long team = data.team(data.uniqueName("결과팀"), false, leader);
		data.judgmentTeam(failedJudgment, team);
		data.warning(failed, WEEK, null, null);

		JsonNode page = dataOf(asAdmin(JUDGMENTS, Map.of("weekStart", WEEK.toString()))).path("adminJudgments");

		assertThat(page.path("weekStart").asText()).isEqualTo(WEEK.toString());
		assertThat(page.path("weekEnd").asText()).isEqualTo(WEEK.plusDays(6).toString());
		assertThat(page.path("passCount").asInt()).isEqualTo(2);
		assertThat(page.path("failCount").asInt()).isEqualTo(1);
		assertThat(page.path("exemptCount").asInt()).isEqualTo(1);
		assertThat(page.path("firstWeekCount").asInt()).isEqualTo(1);
		// 자동 재시도가 남은 보류는 세지 않는다
		assertThat(page.path("holdCount").asInt()).isEqualTo(1);
		// 확정된 마지막 시각 (다음 월요일 07:00)
		assertThat(page.path("executedAt").asText()).isEqualTo(KstFormats.of(WEEK.plusDays(7).atTime(7, 0)));
		assertThat(page.path("nextCursor").isNull()).isTrue();
		assertThat(page.path("items")).hasSize(7);

		JsonNode failedRow = row(page, failed);
		assertThat(failedRow.path("status").asText()).isEqualTo("FAIL");
		assertThat(failedRow.path("warningCount").asInt()).isEqualTo(1);
		assertThat(failedRow.path("manualHold").asBoolean()).isFalse();
		assertThat(row(page, passA).path("warningCount").asInt()).isZero();
		assertThat(row(page, exempt).path("skipReason").asText()).isEqualTo("EXEMPTION_PERIOD");
		assertThat(row(page, first).path("skipReason").asText()).isEqualTo("FIRST_WEEK");

		// 보류 주가 남은 회원은 경고를 계산하지 않아 null이다 (Q-03)
		JsonNode mismatchRow = row(page, mismatch);
		assertThat(mismatchRow.path("status").asText()).isEqualTo("HOLD");
		assertThat(mismatchRow.path("holdReason").asText()).isEqualTo("IDENTITY_MISMATCH");
		assertThat(mismatchRow.path("warningCount").isNull()).isTrue();
		assertThat(mismatchRow.path("manualHold").asBoolean()).isTrue();
		assertThat(row(page, autoRetry).path("manualHold").asBoolean()).isFalse();
		// 팀 이름은 어느 필드에도 싣지 않는다
		assertThat(page.toString()).doesNotContain("결과팀");
	}

	@Test
	@DisplayName("결과 필터와 회원 검색은 목록만 좁히고 집계는 그 주 전체를 유지한다")
	void filterAndKeywordNarrowOnlyTheList() {
		long passed = activeMember();
		long failed = activeMember();
		long held = activeMember();
		data.pass(passed, WEEK);
		data.fail(failed, WEEK);
		data.hold(held, WEEK, "IDENTITY_MISMATCH", 0);
		String failedLogin = members.find(failed).orElseThrow().githubLogin();

		JsonNode failOnly = dataOf(asAdmin(JUDGMENTS, Map.of("weekStart", WEEK.toString(), "filter", "FAIL_ONLY")))
				.path("adminJudgments");
		assertThat(ids(failOnly)).containsExactly(failed);
		assertThat(failOnly.path("passCount").asInt()).isEqualTo(1);

		JsonNode holdOnly = dataOf(asAdmin(JUDGMENTS, Map.of("weekStart", WEEK.toString(), "filter", "HOLD_ONLY")))
				.path("adminJudgments");
		assertThat(ids(holdOnly)).containsExactly(held);

		JsonNode searched = dataOf(asAdmin(JUDGMENTS, Map.of("weekStart", WEEK.toString(), "keyword", failedLogin)))
				.path("adminJudgments");
		assertThat(ids(searched)).containsExactly(failed);
		assertThat(searched.path("failCount").asInt()).isEqualTo(1);
		assertThat(searched.path("passCount").asInt()).isEqualTo(1);

		// LIKE 와일드카드는 글자 그대로 찾는다
		JsonNode wildcard = dataOf(asAdmin(JUDGMENTS, Map.of("weekStart", WEEK.toString(), "keyword", "%")))
				.path("adminJudgments");
		assertThat(wildcard.path("items")).isEmpty();
	}

	@Test
	@DisplayName("주차를 생략하면 직전 주를 보여주고 월요일이 아닌 주차는 거부한다")
	void defaultWeekAndInvalidWeek() {
		long failed = activeMember();
		data.fail(failed, WEEK);

		JsonNode page = dataOf(asAdmin(JUDGMENTS, Map.of())).path("adminJudgments");
		assertThat(page.path("weekStart").asText()).isEqualTo(WEEK.toString());
		assertThat(ids(page)).contains(failed);

		GraphQlResponse notMonday = asAdmin(JUDGMENTS, Map.of("weekStart", WEEK.plusDays(1).toString()));
		assertThat(notMonday.errorCode()).isEqualTo("INVALID_INPUT");
		GraphQlResponse tooLong = asAdmin(JUDGMENTS, Map.of("weekStart", WEEK.toString(), "keyword", "가".repeat(51)));
		assertThat(tooLong.errorCode()).isEqualTo("INVALID_INPUT");
		GraphQlResponse badCursor = asAdmin(JUDGMENTS, Map.of("weekStart", WEEK.toString(), "after", "%%%"));
		assertThat(badCursor.errorCode()).isEqualTo("INVALID_INPUT");
	}

	@Test
	@DisplayName("판정 결과는 20개씩 커서로 나뉘고 끝 페이지에 nextCursor가 없다")
	void judgmentsPaginateByCursor() {
		LocalDate week = WEEK.minusWeeks(40);
		List<Long> created = new ArrayList<>();
		for (int i = 0; i < 23; i++) {
			long memberId = activeMember();
			data.pass(memberId, week);
			created.add(memberId);
		}

		JsonNode first = dataOf(asAdmin(JUDGMENTS, Map.of("weekStart", week.toString()))).path("adminJudgments");
		assertThat(first.path("items")).hasSize(20);
		assertThat(first.path("nextCursor").isNull()).isFalse();
		assertThat(first.path("passCount").asInt()).isEqualTo(23);

		JsonNode second = dataOf(asAdmin(JUDGMENTS,
				Map.of("weekStart", week.toString(), "after", first.path("nextCursor").asText())))
				.path("adminJudgments");
		assertThat(second.path("items")).hasSize(3);
		assertThat(second.path("nextCursor").isNull()).isTrue();

		List<Long> seen = new ArrayList<>(ids(first));
		seen.addAll(ids(second));
		assertThat(seen).containsExactlyInAnyOrderElementsOf(created);
	}

	@Test
	@DisplayName("관리자가 아니면 대시보드를 부를 수 없다")
	void onlyAdminCanUseDashboard() {
		long member = activeMember();

		GraphQlResponse summary = asMember(member, SUMMARY, Map.of());
		assertThat(summary.errorCode()).isEqualTo("FORBIDDEN");
		GraphQlResponse judgments = asMember(member, JUDGMENTS, Map.of());
		assertThat(judgments.errorCode()).isEqualTo("FORBIDDEN");
		GraphQlResponse pending = asMember(pendingMember(), SUMMARY, Map.of());
		assertThat(pending.errorCode()).isEqualTo("ACCOUNT_PENDING");
		GraphQlResponse anonymous = graphQl.post(null, SUMMARY);
		assertThat(anonymous.errorCode()).isEqualTo("UNAUTHENTICATED");
	}

	private static JsonNode row(JsonNode page, long memberId) {
		for (JsonNode item : page.path("items")) {
			if (item.path("member").path("id").asLong() == memberId) {
				return item;
			}
		}
		throw new AssertionError("행이 없어요: " + memberId);
	}

	private static List<Long> ids(JsonNode page) {
		List<Long> ids = new ArrayList<>();
		for (JsonNode item : page.path("items")) {
			ids.add(item.path("member").path("id").asLong());
		}
		return ids;
	}

}
