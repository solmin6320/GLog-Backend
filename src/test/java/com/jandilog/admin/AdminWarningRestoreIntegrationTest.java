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

// 경고 복구 · 벌칙 탭의 삭제된 경고 복구: 목록, 미리보기, 복구 (AD-01 ⑦⑧, 기능명세서 6장, E-42 · E-43 · E-59 · E-60)
class AdminWarningRestoreIntegrationTest extends AdminIntegrationTest {

	private static final LocalDate WEEK = JudgmentWeek.mondayOf(LocalDate.of(2044, 4, 7));
	private static final LocalDateTime DELETED_AT = LocalDateTime.of(2044, 1, 1, 9, 0);

	private static final String LIST = """
			query($after: String) {
			  adminDeletedWarnings(after: $after) {
			    nextCursor
			    items { warningId member { id } weekStart deleteReason deletedAt restoreTeams { id name } teamDeleted }
			  }
			}
			""";

	private static final String PREVIEW = """
			query($warningId: ID!) {
			  previewWarningRestore(warningId: $warningId) {
			    warningId member { id } weekStart restoreTeams { id name } teamDeleted
			    impact { onHold warningCountBefore warningCountAfter entersPenalty leavesPenalty recordOnly affectedWeeks }
			  }
			}
			""";

	private static final String RESTORE = """
			mutation($warningId: ID!) {
			  restoreWarning(warningId: $warningId) { warningId member { id } weekStart onHold warningCount penaltyTarget }
			}
			""";

	@BeforeEach
	void fixClock() {
		clock.fixAt(WEEK.plusWeeks(8).atTime(10, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant());
	}

	@Test
	@DisplayName("추방으로 삭제된 경고는 목록에 오르고 미리보기 뒤 복구하면 팀 카테고리까지 되살아난다")
	void restoresKickedWarningWithItsTeam() {
		long leader = activeMember();
		long team = data.team(data.uniqueName("추방팀"), false, leader);
		long member = activeMember();
		long warning = deletedWarning(member, WEEK, "KICKED", team);

		JsonNode listed = item(allDeleted(), warning);
		assertThat(listed.path("member").path("id").asLong()).isEqualTo(member);
		assertThat(listed.path("weekStart").asText()).isEqualTo(WEEK.toString());
		assertThat(listed.path("deleteReason").asText()).isEqualTo("KICKED");
		assertThat(listed.path("teamDeleted").asBoolean()).isFalse();
		// 이 화면에서는 비공개 팀도 실명이다
		assertThat(listed.path("restoreTeams")).hasSize(1);
		assertThat(listed.path("restoreTeams").get(0).path("id").asLong()).isEqualTo(team);

		JsonNode preview = dataOf(asAdmin(PREVIEW, warningVariables(warning))).path("previewWarningRestore");
		assertThat(preview.path("impact").path("warningCountBefore").asInt()).isZero();
		assertThat(preview.path("impact").path("warningCountAfter").asInt()).isEqualTo(1);
		assertThat(preview.path("impact").path("entersPenalty").asBoolean()).isFalse();
		assertThat(preview.path("impact").path("recordOnly").asBoolean()).isFalse();
		// 미리보기는 아무것도 바꾸지 않는다
		assertThat(data.warningAlive(warning)).isFalse();
		assertThat(data.categoryRemoved(warning, team)).isTrue();

		JsonNode restored = dataOf(asAdmin(RESTORE, warningVariables(warning))).path("restoreWarning");
		assertThat(restored.path("warningCount").asInt()).isEqualTo(1);
		assertThat(restored.path("penaltyTarget").asBoolean()).isFalse();
		assertThat(data.warningAlive(warning)).isTrue();
		assertThat(data.categoryRemoved(warning, team)).isFalse();
		assertThat(data.countLogs("RESTORE_WARNING", "WARNING", Long.toString(warning))).isEqualTo(1);
		assertThat(allDeleted()).noneMatch(found -> found.path("warningId").asLong() == warning);
	}

	@Test
	@DisplayName("팀이 삭제돼 카테고리가 없는 경고도 팀을 지정하지 않고 복구하며 삭제된 팀으로 표시한다")
	void restoresWarningOfDeletedTeamWithoutCategory() {
		long leader = activeMember();
		long goneTeam = data.team(data.uniqueName("삭제팀"), true, leader, DELETED_AT);
		long aliveTeam = data.team(data.uniqueName("살아있는팀"), true, leader);
		long onlyGone = activeMember();
		long mixed = activeMember();
		long onlyGoneWarning = deletedWarning(onlyGone, WEEK, "TEAM_DELETED", goneTeam);
		long mixedWarning = deletedWarning(mixed, WEEK, "KICKED", goneTeam, aliveTeam);

		List<JsonNode> all = allDeleted();
		JsonNode onlyGoneItem = item(all, onlyGoneWarning);
		assertThat(onlyGoneItem.path("deleteReason").asText()).isEqualTo("TEAM_DELETED");
		assertThat(onlyGoneItem.path("restoreTeams")).isEmpty();
		assertThat(onlyGoneItem.path("teamDeleted").asBoolean()).isTrue();
		JsonNode mixedItem = item(all, mixedWarning);
		assertThat(mixedItem.path("restoreTeams")).hasSize(1);
		assertThat(mixedItem.path("restoreTeams").get(0).path("id").asLong()).isEqualTo(aliveTeam);
		assertThat(mixedItem.path("teamDeleted").asBoolean()).isFalse();

		dataOf(asAdmin(RESTORE, warningVariables(onlyGoneWarning)));
		dataOf(asAdmin(RESTORE, warningVariables(mixedWarning)));

		assertThat(data.warningAlive(onlyGoneWarning)).isTrue();
		assertThat(data.categoryRemoved(onlyGoneWarning, goneTeam)).isTrue();
		assertThat(data.warningAlive(mixedWarning)).isTrue();
		assertThat(data.categoryRemoved(mixedWarning, aliveTeam)).isFalse();
		assertThat(data.categoryRemoved(mixedWarning, goneTeam)).isTrue();
	}

	@Test
	@DisplayName("복구해서 경고가 3개가 되면 미리보기가 벌칙 대상 재진입을 알리고 복구 뒤 대상이 된다")
	void restoreCanPutMemberBackIntoPenaltyTarget() {
		long leader = activeMember();
		long team = data.team(data.uniqueName("재진입팀"), true, leader);
		long member = activeMember();
		data.failWithWarning(member, WEEK.minusWeeks(2), team);
		data.failWithWarning(member, WEEK.minusWeeks(1), team);
		long deleted = deletedWarning(member, WEEK, "KICKED", team);

		JsonNode impact = dataOf(asAdmin(PREVIEW, warningVariables(deleted))).path("previewWarningRestore")
				.path("impact");
		assertThat(impact.path("warningCountBefore").asInt()).isEqualTo(2);
		assertThat(impact.path("warningCountAfter").asInt()).isEqualTo(3);
		assertThat(impact.path("entersPenalty").asBoolean()).isTrue();

		JsonNode restored = dataOf(asAdmin(RESTORE, warningVariables(deleted))).path("restoreWarning");
		assertThat(restored.path("warningCount").asInt()).isEqualTo(3);
		assertThat(restored.path("penaltyTarget").asBoolean()).isTrue();
	}

	@Test
	@DisplayName("벌칙 이행 이전 주의 경고는 기록만 되살리고 현재 경고 수에는 반영하지 않는다")
	void restoreBeforeFulfillmentOnlyRestoresTheRecord() {
		long leader = activeMember();
		long team = data.team(data.uniqueName("고정점팀"), true, leader);
		long member = activeMember();
		long deleted = deletedWarning(member, WEEK, "KICKED", team);
		// 그 주가 끝난 뒤에 벌칙을 이행했다
		data.fulfillment(member, adminId, WEEK.plusDays(10).atTime(12, 0), WEEK.minusWeeks(1));

		JsonNode impact = dataOf(asAdmin(PREVIEW, warningVariables(deleted))).path("previewWarningRestore")
				.path("impact");
		assertThat(impact.path("recordOnly").asBoolean()).isTrue();
		assertThat(impact.path("warningCountBefore").asInt()).isZero();
		assertThat(impact.path("warningCountAfter").asInt()).isZero();

		JsonNode restored = dataOf(asAdmin(RESTORE, warningVariables(deleted))).path("restoreWarning");
		assertThat(restored.path("warningCount").asInt()).isZero();
		assertThat(data.warningAlive(deleted)).isTrue();
	}

	@Test
	@DisplayName("정정으로 미달이 아니게 된 주의 삭제된 경고는 목록에 없고 복구할 수 없다")
	void correctedWeekWarningIsNotRestorable() {
		long leader = activeMember();
		long team = data.team(data.uniqueName("정정팀"), true, leader);
		long member = activeMember();
		long warning = data.warning(member, WEEK, DELETED_AT, "KICKED");
		data.warningTeam(warning, team, DELETED_AT);
		data.pass(member, WEEK);
		long noJudgmentMember = activeMember();
		long orphan = data.warning(noJudgmentMember, WEEK, DELETED_AT, "KICKED");
		data.warningTeam(orphan, team, DELETED_AT);

		assertThat(allDeleted()).noneMatch(found -> found.path("warningId").asLong() == warning
				|| found.path("warningId").asLong() == orphan);
		assertThat(asAdmin(PREVIEW, warningVariables(warning)).errorCode()).isEqualTo("NOT_FOUND");
		assertThat(asAdmin(RESTORE, warningVariables(warning)).errorCode()).isEqualTo("NOT_FOUND");
		assertThat(asAdmin(RESTORE, warningVariables(orphan)).errorCode()).isEqualTo("NOT_FOUND");
		assertThat(data.warningAlive(warning)).isFalse();
	}

	@Test
	@DisplayName("이미 복구된 경고나 없는 경고는 거부한다")
	void rejectsRestoredAndMissingWarnings() {
		long leader = activeMember();
		long team = data.team(data.uniqueName("거부팀"), true, leader);
		long member = activeMember();
		long warning = deletedWarning(member, WEEK, "KICKED", team);
		dataOf(asAdmin(RESTORE, warningVariables(warning)));

		assertThat(asAdmin(RESTORE, warningVariables(warning)).errorCode()).isEqualTo("ALREADY_HANDLED");
		assertThat(asAdmin(PREVIEW, warningVariables(warning)).errorCode()).isEqualTo("ALREADY_HANDLED");
		assertThat(asAdmin(RESTORE, Map.of("warningId", "999999999999")).errorCode()).isEqualTo("NOT_FOUND");
		assertThat(asAdmin(RESTORE, Map.of("warningId", "abc")).errorCode()).isEqualTo("NOT_FOUND");
		assertThat(asAdmin(LIST, Map.of("after", "%%%")).errorCode()).isEqualTo("INVALID_INPUT");
		assertThat(data.countLogs("RESTORE_WARNING", "WARNING", Long.toString(warning))).isEqualTo(1);
	}

	@Test
	@DisplayName("두 관리자가 같은 경고를 동시에 복구해도 한 번만 반영되고 로그도 한 줄이다")
	void concurrentRestoreAppliesOnce() throws Exception {
		long leader = activeMember();
		long team = data.team(data.uniqueName("동시복구팀"), true, leader);
		long member = activeMember();
		long warning = deletedWarning(member, WEEK, "KICKED", team);
		long otherAdmin = data.track(members.admin());
		String otherToken = bearerFor(otherAdmin);

		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Callable<GraphQlResponse> byFirst = () -> graphQl.post(adminToken, RESTORE, warningVariables(warning));
			Callable<GraphQlResponse> bySecond = () -> graphQl.post(otherToken, RESTORE, warningVariables(warning));
			Future<GraphQlResponse> first = pool.submit(byFirst);
			Future<GraphQlResponse> second = pool.submit(bySecond);
			List<GraphQlResponse> responses = List.of(first.get(), second.get());

			assertThat(responses.stream().filter(response -> !response.hasErrors())).hasSize(1);
			assertThat(responses.stream().filter(GraphQlResponse::hasErrors).map(GraphQlResponse::errorCode))
					.containsExactly("ALREADY_HANDLED");
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(data.countLogs("RESTORE_WARNING", "WARNING", Long.toString(warning))).isEqualTo(1);
	}

	@Test
	@DisplayName("관리자가 아니면 삭제된 경고를 보거나 복구할 수 없다")
	void onlyAdminCanRestoreWarnings() {
		long member = activeMember();

		assertThat(asMember(member, LIST, Map.of()).errorCode()).isEqualTo("FORBIDDEN");
		assertThat(asMember(member, PREVIEW, Map.of("warningId", "1")).errorCode()).isEqualTo("FORBIDDEN");
		assertThat(asMember(member, RESTORE, Map.of("warningId", "1")).errorCode()).isEqualTo("FORBIDDEN");
	}

	// 삭제 표시된 경고(미달 판정 포함)와 지정한 팀 카테고리를 모두 빠진 상태로 만든다
	private long deletedWarning(long memberId, LocalDate week, String reason, long... teams) {
		long judgmentId = data.fail(memberId, week);
		long warningId = data.warning(memberId, week, DELETED_AT, reason);
		for (long teamId : teams) {
			data.judgmentTeam(judgmentId, teamId);
			data.warningTeam(warningId, teamId, DELETED_AT);
		}
		return warningId;
	}

	private static Map<String, Object> warningVariables(long warningId) {
		return Map.of("warningId", Long.toString(warningId));
	}

	private List<JsonNode> allDeleted() {
		List<JsonNode> items = new ArrayList<>();
		String cursor = null;
		do {
			Map<String, Object> variables = cursor == null ? Map.of() : Map.of("after", cursor);
			JsonNode page = dataOf(asAdmin(LIST, variables)).path("adminDeletedWarnings");
			page.path("items").forEach(items::add);
			cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText();
		} while (cursor != null);
		return items;
	}

	private static JsonNode item(List<JsonNode> items, long warningId) {
		return items.stream()
				.filter(item -> item.path("warningId").asLong() == warningId)
				.findFirst()
				.orElseThrow(() -> new AssertionError("삭제된 경고가 목록에 없어요: " + warningId));
	}

}
