package com.jandilog.exemption;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;
import com.jandilog.testsupport.exemption.ExemptionIntegrationTest;
import com.jandilog.warning.domain.WarningRecalcResult;

// 관리자 판정 정정: 미리보기 → 정정 → 경고 연쇄 재계산 (기능명세서 7장, AD-01 ⑩, E-49 · E-50 · E-58 · E-60, Q-05).
// 정정은 회원 단위라 공유 DB의 다른 테스트와 겹치지 않는다
class JudgmentCorrectionIntegrationTest extends ExemptionIntegrationTest {

	private static final LocalDate BASE = monday(2048, 3, 2);

	private static final String PENALTY_TARGETS = """
			query($after: String) {
			  adminPenaltyTargets(after: $after) { nextCursor items { member { id } warningCount } }
			}
			""";
	private static final String DASHBOARD = """
			query($weekStart: String) {
			  adminJudgments(weekStart: $weekStart) {
			    nextCursor
			    items { judgmentId member { id } status corrected warningCount }
			  }
			}
			""";

	@BeforeEach
	void fixClock() {
		fixClockAt(BASE.plusWeeks(30).atTime(10, 0));
	}

	@Test
	@DisplayName("통과를 미달로 정정하면 경고가 부여되고 팀 카테고리가 붙으며 이후 주차가 다시 계산된다 (E-49)")
	void passToFailGrantsWarningAndRecalculatesFollowingWeeks() {
		long leader = activeMember();
		long team1 = newTeam("정정팀1", leader);
		long team2 = newTeam("정정팀2", leader);
		long member = activeMember();
		join(team1, member);
		join(team2, member);
		History history = history(member, BASE, "P P P", team1, team2);
		long judgment = history.judgment(1);
		assertThat(state(member).warningCount()).isZero();
		assertThat(state(member).streak()).isEqualTo(1);
		String before = snapshot(member);

		JsonNode preview = previewCorrection(judgment, "FAIL");

		assertThat(preview.path("judgmentId").asLong()).isEqualTo(judgment);
		assertThat(preview.path("member").path("id").asLong()).isEqualTo(member);
		assertThat(preview.path("weekStart").asText()).isEqualTo(history.week(1).toString());
		assertThat(preview.path("currentStatus").asText()).isEqualTo("PASS");
		assertThat(preview.path("targetStatus").asText()).isEqualTo("FAIL");
		assertThat(preview.path("impact").path("warningCountBefore").asInt()).isZero();
		assertThat(preview.path("impact").path("warningCountAfter").asInt()).isEqualTo(1);
		assertThat(preview.path("impact").path("entersPenalty").asBoolean()).isFalse();
		assertThat(preview.path("impact").path("recordOnly").asBoolean()).isFalse();
		// 그 주 뒤에 다시 계산되는 판정 수
		assertThat(preview.path("impact").path("affectedWeeks").asInt()).isEqualTo(1);
		// 미리보기는 아무것도 바꾸지 않는다
		assertThat(snapshot(member)).isEqualTo(before);
		assertThat(correctionCount(judgment)).isZero();

		JsonNode corrected = correct(judgment, "FAIL", "  빈 커밋만 있음  ");

		assertThat(corrected.path("judgmentId").asLong()).isEqualTo(judgment);
		assertThat(corrected.path("status").asText()).isEqualTo("FAIL");
		assertThat(corrected.path("onHold").asBoolean()).isFalse();
		assertThat(corrected.path("warningCount").asInt()).isEqualTo(1);
		assertThat(corrected.path("penaltyTarget").asBoolean()).isFalse();
		assertThat(judgmentStatus(judgment)).isEqualTo("FAIL");
		assertThat(skipReason(judgment)).isNull();
		assertThat(corrected(judgment)).isTrue();
		// 경고는 사람당 하나이고 소속 스냅샷의 팀 전부가 카테고리로 붙는다
		Long warningId = jdbc.queryForObject("select id from warning where member_id = ? and week_start = ?",
				Long.class, member, history.week(1));
		assertThat(data.warningAlive(warningId)).isTrue();
		assertThat(jdbc.queryForList("select team_id from warning_team where warning_id = ? and removed_at is null",
				Long.class, warningId)).containsExactlyInAnyOrder(team1, team2);
		// 이후 주차가 처음부터 다시 계산된다: 통과 / 미달 / 통과 -> 경고 1, 연속 1
		WarningRecalcResult.Calculated after = state(member);
		assertThat(after.warningCount()).isEqualTo(1);
		assertThat(after.streak()).isEqualTo(1);
		assertThat(after.activeWarningWeeks()).containsExactly(history.week(1));

		// 대시보드 목록도 정정 결과와 표시를 따른다
		JsonNode row = dashboardRow(history.week(1), member);
		assertThat(row.path("status").asText()).isEqualTo("FAIL");
		assertThat(row.path("corrected").asBoolean()).isTrue();
		assertThat(row.path("warningCount").asInt()).isEqualTo(1);
	}

	@Test
	@DisplayName("경고 3개 중 하나를 통과나 면제로 정정하면 벌칙 대상에서 빠지고 벌칙 목록에서도 사라진다 (E-50)")
	void correctionCanTakeMemberOutOfPenaltyTarget() {
		long leader = activeMember();
		long team = newTeam("벌칙팀", leader);
		long toPass = activeMember();
		long toExempt = activeMember();
		join(team, toPass);
		join(team, toExempt);
		History passed = history(toPass, BASE, "F F F", team);
		History exempted = history(toExempt, BASE, "F F F", team);
		assertThat(state(toPass).penaltyTarget()).isTrue();
		assertThat(penaltyTargetIds()).contains(toPass, toExempt);

		JsonNode preview = previewCorrection(passed.judgment(2), "PASS");
		assertThat(preview.path("impact").path("warningCountBefore").asInt()).isEqualTo(3);
		assertThat(preview.path("impact").path("warningCountAfter").asInt()).isEqualTo(2);
		assertThat(preview.path("impact").path("leavesPenalty").asBoolean()).isTrue();
		assertThat(preview.path("impact").path("entersPenalty").asBoolean()).isFalse();

		JsonNode toPassResult = correct(passed.judgment(2), "PASS", "성의 있는 글이 있었음");
		JsonNode toExemptResult = correct(exempted.judgment(2), "EXEMPT", "병가");

		assertThat(toPassResult.path("warningCount").asInt()).isEqualTo(2);
		assertThat(toPassResult.path("penaltyTarget").asBoolean()).isFalse();
		assertThat(toExemptResult.path("warningCount").asInt()).isEqualTo(2);
		assertThat(toExemptResult.path("penaltyTarget").asBoolean()).isFalse();
		assertThat(judgmentStatus(exempted.judgment(2))).isEqualTo("EXEMPT");
		assertThat(skipReason(exempted.judgment(2))).isEqualTo("PERSONAL_EXEMPTION");
		assertThat(state(toPass).penaltyTarget()).isFalse();
		assertThat(penaltyTargetIds()).doesNotContain(toPass, toExempt);

		// 반대로 통과를 미달로 되돌리면 다시 벌칙 대상에 들어간다는 것을 미리보기가 알린다
		JsonNode back = previewCorrection(passed.judgment(2), "FAIL");
		assertThat(back.path("impact").path("entersPenalty").asBoolean()).isTrue();
		assertThat(back.path("impact").path("warningCountAfter").asInt()).isEqualTo(3);
	}

	@Test
	@DisplayName("벌칙 이행 체크 이전 주차를 정정하면 기록만 고치고 이행 이후 경고 수는 그대로다 (E-58)")
	void correctionBeforeFulfillmentOnlyFixesTheRecord() {
		long leader = activeMember();
		long team = newTeam("고정점팀", leader);
		long member = activeMember();
		join(team, member);
		// 0~2주 미달로 벌칙 대상이 됐고 3주 월요일 새벽에 이행 체크, 이후 3·4주 미달
		History history = history(member, BASE, "F F F F F", team);
		data.fulfillment(member, adminId, BASE.plusWeeks(3).atTime(1, 0), BASE.plusWeeks(2));
		assertThat(state(member).warningCount()).isEqualTo(2);
		String before = snapshot(member);

		JsonNode preview = previewCorrection(history.judgment(1), "PASS");
		assertThat(preview.path("impact").path("recordOnly").asBoolean()).isTrue();
		assertThat(preview.path("impact").path("warningCountBefore").asInt()).isEqualTo(2);
		assertThat(preview.path("impact").path("warningCountAfter").asInt()).isEqualTo(2);
		assertThat(preview.path("impact").path("affectedWeeks").asInt()).isZero();
		assertThat(snapshot(member)).isEqualTo(before);

		JsonNode corrected = correct(history.judgment(1), "PASS", "이행 전 오판정 기록 수정");

		assertThat(corrected.path("warningCount").asInt()).isEqualTo(2);
		assertThat(corrected.path("penaltyTarget").asBoolean()).isFalse();
		// 판정 기록은 정확하게 고쳐진다
		assertThat(judgmentStatus(history.judgment(1))).isEqualTo("PASS");
		assertThat(corrected(history.judgment(1))).isTrue();
		assertThat(correctionCount(history.judgment(1))).isEqualTo(1);
		assertThat(state(member).warningCount()).isEqualTo(2);

		// 이행 이전 주를 면제로 정정해도, 되돌려 미달로 정정해도 이행 이후 경고 수는 그대로
		correct(history.judgment(0), "EXEMPT", "이행 전 면제");
		correct(history.judgment(1), "FAIL", "다시 미달");
		assertThat(state(member).warningCount()).isEqualTo(2);
		assertThat(state(member).penaltyTarget()).isFalse();

		// 이행 이후 주를 정정하면 경고 수에 반영된다
		JsonNode after = previewCorrection(history.judgment(3), "PASS");
		assertThat(after.path("impact").path("recordOnly").asBoolean()).isFalse();
		assertThat(after.path("impact").path("warningCountAfter").asInt()).isEqualTo(1);
		assertThat(correct(history.judgment(3), "PASS", "이행 후 정정").path("warningCount").asInt()).isEqualTo(1);
	}

	@Test
	@DisplayName("이행 체크 이전 통과를 미달로 정정하면 경고 기록은 생기지만 이행 이후 경고 수에는 세지 않는다 (E-58)")
	void failCorrectionBeforeFulfillmentCreatesRecordButIsNotCounted() {
		long leader = activeMember();
		long team = newTeam("기록팀", leader);
		long member = activeMember();
		join(team, member);
		History history = history(member, BASE, "P P P P P", team);
		data.fulfillment(member, adminId, BASE.plusWeeks(2).atTime(1, 0), BASE);

		correct(history.judgment(0), "FAIL", "이행 전 미달이었음");

		assertThat(warningRowCount(member)).isEqualTo(1);
		WarningRecalcResult.Calculated state = state(member);
		assertThat(state.warningCount()).isZero();
		assertThat(state.penaltyTarget()).isFalse();
		assertThat(state.beforeBaselineWarningWeeks()).containsExactly(BASE);
	}

	@Test
	@DisplayName("삭제 표시된 경고가 있는 회원의 다른 주차를 정정해도 삭제된 경고는 되살아나지 않는다 (E-60)")
	void correctingOtherWeeksNeverRevivesDeletedWarnings() {
		long leader = activeMember();
		long team = newTeam("삭제팀", leader);
		long member = activeMember();
		join(team, member);
		History history = history(member, BASE, "F P P F", team);
		// 0주 경고는 추방으로 소프트 삭제됐다
		jdbc.update("update warning set deleted_at = ?, delete_reason = 'KICKED' where id = ?",
				LocalDateTime.of(2048, 1, 1, 0, 0), history.warning(0));
		jdbc.update("update warning_team set removed_at = ? where warning_id = ?", LocalDateTime.of(2048, 1, 1, 0, 0),
				history.warning(0));
		assertThat(state(member).warningCount()).isEqualTo(1);

		// 상관없는 1주를 정정한다. 미달 주차를 세면 0주 미달이 되살아나 2가 된다
		JsonNode preview = previewCorrection(history.judgment(1), "EXEMPT");
		assertThat(preview.path("impact").path("warningCountBefore").asInt()).isEqualTo(1);
		assertThat(preview.path("impact").path("warningCountAfter").asInt()).isEqualTo(1);
		assertThat(correct(history.judgment(1), "EXEMPT", "관련 없는 정정").path("warningCount").asInt()).isEqualTo(1);
		assertThat(data.warningAlive(history.warning(0))).isFalse();

		// 삭제된 경고가 붙은 주 자체를 통과로 바꿨다가 다시 미달로 바꿔도 새 경고를 만들거나 되살리지 않는다
		correct(history.judgment(0), "PASS", "잠깐 통과로");
		JsonNode again = correct(history.judgment(0), "FAIL", "다시 미달");
		assertThat(again.path("warningCount").asInt()).isEqualTo(1);
		assertThat(data.warningAlive(history.warning(0))).isFalse();
		assertThat(warningRowCount(member)).isEqualTo(2);
		assertThat(state(member).activeWarningWeeks()).containsExactly(history.week(3));
		assertThat(jdbc.queryForObject("select delete_reason from warning where id = ?", String.class,
				history.warning(0))).isEqualTo("KICKED");
	}

	@Test
	@DisplayName("정정할 수 없는 판정(보류, 같은 결과, 제외를 통과·미달로)은 거부하고 아무것도 바꾸지 않는다 (Q-05)")
	void rejectsUncorrectableCases() {
		long leader = activeMember();
		long team = newTeam("거부팀", leader);
		long holdMember = activeMember();
		long firstWeekMember = activeMember();
		long plainMember = activeMember();
		long noTeamSnapshot = activeMember();
		History hold = history(holdMember, BASE, "H", team);
		History first = history(firstWeekMember, BASE, "X", team);
		History plain = history(plainMember, BASE, "P F E", team);
		// 소속 스냅샷이 없는 통과: 미달로 정정하면 경고에 붙일 팀이 없다
		long noTeamPass = data.pass(noTeamSnapshot, BASE);
		String before = snapshot(holdMember, firstWeekMember, plainMember, noTeamSnapshot);

		for (String target : List.of("PASS", "FAIL", "EXEMPT")) {
			assertThat(correctRaw(hold.judgment(0), target, "보류 정정").errorCode()).as("보류 -> " + target)
					.isEqualTo("JUDGMENT_NOT_CORRECTABLE");
			assertThat(asAdmin(PREVIEW_CORRECTION, Map.of("judgmentId", Long.toString(hold.judgment(0)), "status",
					target)).errorCode()).as("보류 미리보기 -> " + target).isEqualTo("JUDGMENT_NOT_CORRECTABLE");
		}
		assertThat(correctRaw(first.judgment(0), "PASS", "제외를 통과로").errorCode())
				.isEqualTo("JUDGMENT_NOT_CORRECTABLE");
		assertThat(correctRaw(first.judgment(0), "FAIL", "제외를 미달로").errorCode())
				.isEqualTo("JUDGMENT_NOT_CORRECTABLE");
		// 같은 결과로는 정정하지 않는다
		assertThat(correctRaw(plain.judgment(0), "PASS", "같은 결과").errorCode()).isEqualTo("JUDGMENT_NOT_CORRECTABLE");
		assertThat(correctRaw(plain.judgment(1), "FAIL", "같은 결과").errorCode()).isEqualTo("JUDGMENT_NOT_CORRECTABLE");
		assertThat(correctRaw(plain.judgment(2), "EXEMPT", "같은 결과").errorCode())
				.isEqualTo("JUDGMENT_NOT_CORRECTABLE");
		assertThat(correctRaw(noTeamPass, "FAIL", "스냅샷 없음").errorCode()).isEqualTo("JUDGMENT_NOT_CORRECTABLE");
		assertThat(asAdmin(PREVIEW_CORRECTION, Map.of("judgmentId", Long.toString(noTeamPass), "status", "FAIL"))
				.errorCode()).isEqualTo("JUDGMENT_NOT_CORRECTABLE");

		assertThat(snapshot(holdMember, firstWeekMember, plainMember, noTeamSnapshot)).isEqualTo(before);
		assertThat(actionLogCount(adminId)).isZero();
	}

	@Test
	@DisplayName("정정 결과는 통과·미달·면제 셋뿐이라 보류나 제외로 정정하는 요청은 스키마가 거부한다")
	void onlyThreeResultsExistInTheSchema() {
		long leader = activeMember();
		long team = newTeam("스키마팀", leader);
		long member = activeMember();
		join(team, member);
		History history = history(member, BASE, "P", team);

		for (String target : List.of("HOLD", "EXCLUDED")) {
			GraphQlResponse correct = asAdmin(CORRECT, correctionInput(history.judgment(0), target, "사유", null));
			assertThat(correct.hasErrors()).as("정정 " + target).isTrue();
			assertThat(correct.errorCode()).isNotEqualTo("INTERNAL_ERROR");
			GraphQlResponse preview = asAdmin(PREVIEW_CORRECTION,
					Map.of("judgmentId", Long.toString(history.judgment(0)), "status", target));
			assertThat(preview.hasErrors()).as("미리보기 " + target).isTrue();
		}
		assertThat(judgmentStatus(history.judgment(0))).isEqualTo("PASS");
		assertThat(correctionCount(history.judgment(0))).isZero();
	}

	@Test
	@DisplayName("제외(첫 참가·팀 없음)는 면제로만 정정할 수 있고 면제는 통과·미달로 다시 정정할 수 있다")
	void excludedCanOnlyBeCorrectedToExempt() {
		long leader = activeMember();
		long team = newTeam("제외팀", leader);
		long firstWeekMember = activeMember();
		long noTeamMember = activeMember();
		long exemptMember = activeMember();
		join(team, firstWeekMember);
		join(team, exemptMember);
		History first = history(firstWeekMember, BASE, "X", team);
		History noTeam = history(noTeamMember, BASE, "N");
		History exempt = history(exemptMember, BASE, "E P", team);

		JsonNode firstResult = correct(first.judgment(0), "EXEMPT", "첫 참가 주 면제 처리");
		JsonNode noTeamResult = correct(noTeam.judgment(0), "EXEMPT", "팀 없음 면제 처리");

		assertThat(firstResult.path("status").asText()).isEqualTo("EXEMPT");
		assertThat(noTeamResult.path("status").asText()).isEqualTo("EXEMPT");
		assertThat(judgmentStatus(first.judgment(0))).isEqualTo("EXEMPT");
		assertThat(skipReason(first.judgment(0))).isEqualTo("PERSONAL_EXEMPTION");
		assertThat(jdbc.queryForObject("select before_status from judgment_correction where judgment_id = ?",
				String.class, first.judgment(0))).isEqualTo("EXCLUDED");

		// 면제를 통과로, 다시 미달로 정정할 수 있다 (소속 스냅샷이 있을 때)
		assertThat(correct(exempt.judgment(0), "PASS", "면제 취소").path("status").asText()).isEqualTo("PASS");
		assertThat(skipReason(exempt.judgment(0))).isNull();
		assertThat(correct(exempt.judgment(0), "FAIL", "미달로").path("warningCount").asInt()).isEqualTo(1);
		assertThat(correctionCount(exempt.judgment(0))).isEqualTo(2);
	}

	@Test
	@DisplayName("사유는 필수이고 500자까지이며 없는 판정은 NOT_FOUND이고 반영 전에는 이력이 남지 않는다")
	void validatesReasonAndTarget() {
		long leader = activeMember();
		long team = newTeam("사유팀", leader);
		long member = activeMember();
		join(team, member);
		History history = history(member, BASE, "P P", team);
		long judgment = history.judgment(0);

		assertThat(correctRaw(judgment, "FAIL", "   ").errorCode()).isEqualTo("REASON_REQUIRED");
		assertThat(correctRaw(judgment, "FAIL", "가".repeat(501)).errorCode()).isEqualTo("INVALID_INPUT");
		assertThat(correctRaw(999_999_999_999L, "FAIL", "없는 판정").errorCode()).isEqualTo("NOT_FOUND");
		assertThat(asAdmin(CORRECT, Map.of("input", Map.of("judgmentId", "abc", "status", "FAIL", "reason", "문자")))
				.errorCode()).isEqualTo("NOT_FOUND");
		assertThat(asAdmin(PREVIEW_CORRECTION, Map.of("judgmentId", "999999999999", "status", "FAIL")).errorCode())
				.isEqualTo("NOT_FOUND");
		assertThat(judgmentStatus(judgment)).isEqualTo("PASS");
		assertThat(correctionCount(judgment)).isZero();
		assertThat(warningRowCount(member)).isZero();
		assertThat(actionLogCount(adminId)).isZero();

		// 500자는 경계라 허용한다
		correct(judgment, "FAIL", "가".repeat(500));
		assertThat(jdbc.queryForObject("select reason from judgment_correction where judgment_id = ?", String.class,
				judgment)).hasSize(500);
	}

	@Test
	@DisplayName("정정 이력과 관리자 행동 로그가 사유·관리자·결과와 함께 쌓이고 같은 판정을 다시 정정하면 이력이 늘어난다")
	void recordsCorrectionHistoryAndAdminLog() {
		long leader = activeMember();
		long team = newTeam("이력팀", leader);
		long member = activeMember();
		join(team, member);
		History history = history(member, BASE, "P P", team);
		long judgment = history.judgment(0);

		correct(judgment, "FAIL", "  빈 커밋  ");
		correct(judgment, "PASS", "다시 확인하니 정상");

		List<Map<String, Object>> corrections = jdbc.queryForList(
				"select before_status, after_status, reason, admin_id from judgment_correction where judgment_id = ?"
						+ " order by id", judgment);
		assertThat(corrections).hasSize(2);
		assertThat(corrections.get(0)).containsEntry("before_status", "PASS").containsEntry("after_status", "FAIL")
				.containsEntry("reason", "빈 커밋").containsEntry("admin_id", adminId);
		assertThat(corrections.get(1)).containsEntry("before_status", "FAIL").containsEntry("after_status", "PASS")
				.containsEntry("reason", "다시 확인하니 정상").containsEntry("admin_id", adminId);
		List<Map<String, Object>> logs = jdbc.queryForList(
				"select admin_id, target_type, reason from admin_action_log where action = 'CORRECT_JUDGMENT'"
						+ " and target_id = ? order by id", Long.toString(judgment));
		assertThat(logs).hasSize(2);
		assertThat(logs.get(0)).containsEntry("admin_id", adminId).containsEntry("target_type", "WEEKLY_JUDGMENT")
				.containsEntry("reason", "빈 커밋");
		assertThat(logs.get(1)).containsEntry("reason", "다시 확인하니 정상");
		assertThat(judgmentStatus(judgment)).isEqualTo("PASS");
		assertThat(corrected(judgment)).isTrue();
		// 한 번 만든 경고 기록은 지우지 않고 그 주가 통과가 되면 세지 않는다
		assertThat(warningRowCount(member)).isEqualTo(1);
		assertThat(state(member).warningCount()).isZero();
		// 다른 판정에는 이력이 없다
		assertThat(correctionCount(history.judgment(1))).isZero();
	}

	@Test
	@DisplayName("expectedStatus가 지금 판정과 다르면 JUDGMENT_CHANGED로 거부하고 같으면 반영한다")
	void expectedStatusGuardsAgainstConcurrentChange() {
		long leader = activeMember();
		long team = newTeam("재검증팀", leader);
		long member = activeMember();
		join(team, member);
		History history = history(member, BASE, "P", team);
		long judgment = history.judgment(0);

		GraphQlResponse stale = asAdmin(CORRECT, correctionInput(judgment, "FAIL", "오래된 화면", "FAIL"));
		assertThat(stale.errorCode()).isEqualTo("JUDGMENT_CHANGED");
		assertThat(judgmentStatus(judgment)).isEqualTo("PASS");
		assertThat(correctionCount(judgment)).isZero();
		assertThat(actionLogCount(adminId)).isZero();

		GraphQlResponse fresh = asAdmin(CORRECT, correctionInput(judgment, "FAIL", "최신 화면", "PASS"));
		assertThat(dataOf(fresh).path("correctJudgment").path("status").asText()).isEqualTo("FAIL");
		assertThat(correctionCount(judgment)).isEqualTo(1);
	}

	@Test
	@DisplayName("보류 주가 남은 회원도 정정은 반영되지만 경고 수는 계산하지 않고 보류로 알린다 (Q-03)")
	void correctionWithAnotherHoldReportsOnHold() {
		long leader = activeMember();
		long team = newTeam("보류팀", leader);
		long member = activeMember();
		join(team, member);
		History history = history(member, BASE, "P F H", team);

		JsonNode preview = previewCorrection(history.judgment(1), "PASS");
		assertThat(preview.path("impact").path("onHold").asBoolean()).isTrue();
		assertThat(preview.path("impact").path("warningCountBefore").isNull()).isTrue();
		assertThat(preview.path("impact").path("warningCountAfter").isNull()).isTrue();

		JsonNode corrected = correct(history.judgment(1), "PASS", "보류와 별개로 정정");

		assertThat(corrected.path("onHold").asBoolean()).isTrue();
		assertThat(corrected.path("warningCount").isNull()).isTrue();
		assertThat(corrected.path("penaltyTarget").asBoolean()).isFalse();
		assertThat(judgmentStatus(history.judgment(1))).isEqualTo("PASS");
	}

	@Test
	@DisplayName("정정과 미리보기는 관리자만 부를 수 있고 일반 회원·승인 대기·비로그인은 아무것도 바꾸지 못한다")
	void onlyAdminCorrectsJudgments() {
		long leader = activeMember();
		long team = newTeam("권한팀", leader);
		long member = activeMember();
		join(team, member);
		History history = history(member, BASE, "P", team);
		String before = snapshot(member);
		long plain = activeMember();
		long pending = pendingMember();
		Map<String, Object> correctVariables = correctionInput(history.judgment(0), "FAIL", "무권한", null);
		Map<String, Object> previewVariables = Map.of("judgmentId", Long.toString(history.judgment(0)), "status",
				"FAIL");

		assertThat(asMember(plain, CORRECT, correctVariables).errorCode()).isEqualTo("FORBIDDEN");
		assertThat(asMember(plain, PREVIEW_CORRECTION, previewVariables).errorCode()).isEqualTo("FORBIDDEN");
		assertThat(asMember(leader, CORRECT, correctVariables).errorCode()).isEqualTo("FORBIDDEN");
		assertThat(asMember(pending, CORRECT, correctVariables).errorCode()).isEqualTo("ACCOUNT_PENDING");
		assertThat(asMember(pending, PREVIEW_CORRECTION, previewVariables).errorCode())
				.isEqualTo("ACCOUNT_PENDING");
		assertThat(graphQl.post(null, CORRECT, correctVariables).errorCode()).isEqualTo("UNAUTHENTICATED");
		assertThat(graphQl.post(null, PREVIEW_CORRECTION, previewVariables).errorCode())
				.isEqualTo("UNAUTHENTICATED");

		assertThat(snapshot(member)).isEqualTo(before);
		assertThat(judgmentStatus(history.judgment(0))).isEqualTo("PASS");
	}

	@Test
	@DisplayName("두 관리자가 같은 판정을 동시에 정정하면 회원 락으로 한 번씩 순서대로 반영되고 이력이 끊기지 않는다")
	void concurrentCorrectionsAreSerialized() {
		long leader = activeMember();
		long team = newTeam("동시팀", leader);
		long member = activeMember();
		join(team, member);
		History history = history(member, BASE, "P P", team);
		long judgment = history.judgment(0);
		long secondAdmin = data.track(members.admin());
		String secondToken = bearerFor(secondAdmin);

		// 같은 화면(PASS)을 보고 둘 다 미달로 정정하면 한쪽만 반영되고 다른 쪽은 재검증에서 막힌다
		List<GraphQlResponse> same = runTogether(
				() -> asAdmin(CORRECT, correctionInput(judgment, "FAIL", "관리자 1", "PASS")),
				() -> graphQl.post(secondToken, CORRECT, correctionInput(judgment, "FAIL", "관리자 2", "PASS")));
		assertThat(same.stream().filter(r -> !r.hasErrors())).hasSize(1);
		assertThat(same.stream().filter(GraphQlResponse::hasErrors).map(GraphQlResponse::errorCode))
				.containsExactly("JUDGMENT_CHANGED");
		assertThat(correctionCount(judgment)).isEqualTo(1);
		assertThat(warningRowCount(member)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from admin_action_log where action = 'CORRECT_JUDGMENT'"
				+ " and target_id = ?", Integer.class, Long.toString(judgment))).isEqualTo(1);

		// 서로 다른 결과로 동시에 정정하면 순서대로 둘 다 반영되고 이력의 앞뒤 상태가 이어진다
		long other = history.judgment(1);
		List<GraphQlResponse> different = runTogether(() -> asAdmin(CORRECT, correctionInput(other, "FAIL", "미달로", null)),
				() -> graphQl.post(secondToken, CORRECT, correctionInput(other, "EXEMPT", "면제로", null)));
		assertThat(different.stream().filter(GraphQlResponse::hasErrors)).isEmpty();
		List<Map<String, Object>> chain = jdbc.queryForList(
				"select before_status, after_status from judgment_correction where judgment_id = ? order by id", other);
		assertThat(chain).hasSize(2);
		assertThat(chain.get(0)).containsEntry("before_status", "PASS");
		assertThat(chain.get(1).get("before_status")).isEqualTo(chain.get(0).get("after_status"));
		assertThat(judgmentStatus(other)).isEqualTo(chain.get(1).get("after_status"));
		// 마지막 상태로 계산한 값과 저장된 경고 레코드가 어긋나지 않는다
		boolean finalFail = "FAIL".equals(judgmentStatus(other));
		assertThat(state(member).activeWarningWeeks().contains(history.week(1))).isEqualTo(finalFail);
	}

	private JsonNode dashboardRow(LocalDate week, long memberId) {
		JsonNode page = dataOf(asAdmin(DASHBOARD, Map.of("weekStart", week.toString()))).path("adminJudgments");
		for (JsonNode item : page.path("items")) {
			if (item.path("member").path("id").asLong() == memberId) {
				return item;
			}
		}
		throw new AssertionError("대시보드에 회원이 없어요: " + memberId);
	}

	private List<Long> penaltyTargetIds() {
		List<Long> ids = new ArrayList<>();
		String cursor = null;
		do {
			Map<String, Object> variables = cursor == null ? Map.of() : Map.of("after", cursor);
			JsonNode page = dataOf(asAdmin(PENALTY_TARGETS, variables)).path("adminPenaltyTargets");
			page.path("items").forEach(item -> ids.add(item.path("member").path("id").asLong()));
			cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText();
		} while (cursor != null);
		return ids;
	}

}
