package com.jandilog.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.jandilog.judgment.domain.HoldReason;
import com.jandilog.judgment.domain.JudgmentWeek;
import com.jandilog.testsupport.admin.AdminIntegrationTest;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;

// 대시보드 보류 목록과 한 건 재시도 (AD-01 ⑨, 기능명세서 5장, E-32 · E-35).
// [전체 재시도]는 공유 DB의 다른 보류까지 건드리므로 통합 테스트에서 부르지 않고 AdminHoldServiceTest가 맡는다
class AdminHoldIntegrationTest extends AdminIntegrationTest {

	private static final LocalDate WEEK = JudgmentWeek.mondayOf(LocalDate.of(2042, 5, 8));

	private static final String HOLDS = """
			query($after: String) {
			  adminJudgmentHolds(after: $after) {
			    totalCount identityMismatchCount nextCursor
			    items { member { id githubLogin } weekStart reason retryCount retriesExhausted }
			  }
			}
			""";

	private static final String RETRY = """
			mutation($memberId: ID!, $weekStart: String!) {
			  retryJudgmentHold(memberId: $memberId, weekStart: $weekStart) {
			    member { id } weekStart status holdReason resolved
			  }
			}
			""";

	@BeforeEach
	void fixClock() {
		clock.fixAt(WEEK.plusWeeks(1).plusDays(2).atTime(10, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant());
	}

	@Test
	@DisplayName("보류 목록에는 사람이 처리할 건만 오르고 자동 재시도가 남은 건은 빠진다")
	void listsOnlyHoldsThatNeedAPerson() {
		long mismatch = activeMember();
		long exhausted = activeMember();
		long autoRetry = activeMember();
		data.hold(mismatch, WEEK, "IDENTITY_MISMATCH", 0);
		data.hold(exhausted, WEEK.minusWeeks(1), "API_ERROR", 3);
		data.hold(autoRetry, WEEK, "API_ERROR", 1);

		JsonNode first = dataOf(asAdmin(HOLDS, Map.of())).path("adminJudgmentHolds");
		assertThat(first.path("totalCount").asInt()).isGreaterThanOrEqualTo(2);
		assertThat(first.path("identityMismatchCount").asInt()).isGreaterThanOrEqualTo(1);

		// 공유 DB에 다른 보류가 많을 수 있어 끝 페이지까지 읽는다
		List<JsonNode> items = allItems();
		JsonNode mismatchItem = item(items, mismatch);
		assertThat(mismatchItem.path("reason").asText()).isEqualTo("IDENTITY_MISMATCH");
		assertThat(mismatchItem.path("weekStart").asText()).isEqualTo(WEEK.toString());
		assertThat(mismatchItem.path("retriesExhausted").asBoolean()).isFalse();
		JsonNode exhaustedItem = item(items, exhausted);
		assertThat(exhaustedItem.path("reason").asText()).isEqualTo("API_ERROR");
		assertThat(exhaustedItem.path("retryCount").asInt()).isEqualTo(3);
		assertThat(exhaustedItem.path("retriesExhausted").asBoolean()).isTrue();
		assertThat(items).noneMatch(item -> item.path("member").path("id").asLong() == autoRetry);
	}

	@Test
	@DisplayName("보류 한 건을 다시 돌려 GitHub 조회가 풀리면 판정이 확정되고 두 번째 재시도는 건너뛴다")
	void retryResolvesHoldThenSkipsConfirmed() {
		long member = activeMember();
		long leader = activeMember();
		long team = data.team(data.uniqueName("재시도팀"), true, leader);
		long judgmentId = data.hold(member, WEEK, "API_ERROR", 3);
		data.judgmentTeam(judgmentId, team);
		// 통과 조건: 인증일 3일 이상(스텁은 매일 잔디) + 기록글 1개
		data.postIndexRecord(member, WEEK.plusDays(1));

		JsonNode first = dataOf(asAdmin(RETRY, retryVariables(member))).path("retryJudgmentHold");
		assertThat(first.path("status").asText()).isEqualTo("PASS");
		assertThat(first.path("resolved").asBoolean()).isTrue();
		assertThat(data.judgmentStatus(judgmentId)).isEqualTo("PASS");

		JsonNode second = dataOf(asAdmin(RETRY, retryVariables(member))).path("retryJudgmentHold");
		assertThat(second.path("status").asText()).isEqualTo("PASS");
		assertThat(second.path("resolved").asBoolean()).isFalse();
	}

	@Test
	@DisplayName("아이디 불일치로 조회가 계속 실패하면 보류로 남고 풀리지 않았다고 알린다")
	void retryKeepsHoldWhenLookupStillFails() {
		long member = activeMember();
		long leader = activeMember();
		long team = data.team(data.uniqueName("실패팀"), true, leader);
		long judgmentId = data.hold(member, WEEK, "IDENTITY_MISMATCH", 0);
		data.judgmentTeam(judgmentId, team);
		grassClient.failFor(members.find(member).orElseThrow().githubLogin(), HoldReason.IDENTITY_MISMATCH);

		JsonNode result = dataOf(asAdmin(RETRY, retryVariables(member))).path("retryJudgmentHold");

		assertThat(result.path("status").asText()).isEqualTo("HOLD");
		assertThat(result.path("holdReason").asText()).isEqualTo("IDENTITY_MISMATCH");
		assertThat(result.path("resolved").asBoolean()).isFalse();
		assertThat(data.judgmentStatus(judgmentId)).isEqualTo("HOLD");
	}

	@Test
	@DisplayName("판정 행이 없거나 주차 형식이 틀리면 거부한다")
	void rejectsMissingJudgmentAndBadWeek() {
		long member = activeMember();

		GraphQlResponse missing = asAdmin(RETRY, retryVariables(member));
		assertThat(missing.errorCode()).isEqualTo("NOT_FOUND");

		Map<String, Object> notMonday = new HashMap<>(retryVariables(member));
		notMonday.put("weekStart", WEEK.plusDays(1).toString());
		assertThat(asAdmin(RETRY, notMonday).errorCode()).isEqualTo("INVALID_INPUT");

		Map<String, Object> badMember = new HashMap<>(retryVariables(member));
		badMember.put("memberId", "abc");
		assertThat(asAdmin(RETRY, badMember).errorCode()).isEqualTo("NOT_FOUND");
	}

	@Test
	@DisplayName("관리자가 아니면 보류 목록과 재시도를 쓸 수 없다")
	void onlyAdminCanTouchHolds() {
		long member = activeMember();

		assertThat(asMember(member, HOLDS, Map.of()).errorCode()).isEqualTo("FORBIDDEN");
		assertThat(asMember(member, RETRY, retryVariables(member)).errorCode()).isEqualTo("FORBIDDEN");
		assertThat(asMember(member, "mutation { retryAllJudgmentHolds { attempted } }", Map.of()).errorCode())
				.isEqualTo("FORBIDDEN");
	}

	private Map<String, Object> retryVariables(long memberId) {
		return Map.of("memberId", Long.toString(memberId), "weekStart", WEEK.toString());
	}

	private List<JsonNode> allItems() {
		List<JsonNode> items = new ArrayList<>();
		String cursor = null;
		do {
			Map<String, Object> variables = cursor == null ? Map.of() : Map.of("after", cursor);
			JsonNode page = dataOf(asAdmin(HOLDS, variables)).path("adminJudgmentHolds");
			page.path("items").forEach(items::add);
			cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText();
		} while (cursor != null);
		return items;
	}

	private static JsonNode item(List<JsonNode> items, long memberId) {
		return items.stream()
				.filter(item -> item.path("member").path("id").asLong() == memberId)
				.findFirst()
				.orElseThrow(() -> new AssertionError("보류 항목이 없어요: " + memberId));
	}

}
