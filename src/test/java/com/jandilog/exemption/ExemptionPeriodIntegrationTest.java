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

// 전체 면제 기간 등록·수정·삭제와 과거 주 소급 재계산 (기능명세서 7장, AD-01 면제 관리 ⑤⑥, E-45 · E-46 · E-58).
// 면제 기간은 전체 회원에 걸리므로 공유 DB의 다른 테스트와 겹치지 않는 2046년 주차만 쓴다
class ExemptionPeriodIntegrationTest extends ExemptionIntegrationTest {

	private static final LocalDate BASE = monday(2046, 3, 4);

	@BeforeEach
	void fixClock() {
		fixClockAt(BASE.plusWeeks(40).atTime(10, 0));
	}

	@Test
	@DisplayName("지난 주를 면제 기간으로 등록하면 통과·미달 판정만 면제로 바뀌고 제외·보류·이미 면제인 판정과 다른 주는 그대로다")
	void registeringPastWeekExemptsOnlyConfirmedPassAndFail() {
		LocalDate week = BASE.plusWeeks(3);
		long leader = activeMember();
		long team = newTeam("면제기간팀", leader);
		long passMember = activeMember();
		long failMember = activeMember();
		long firstWeekMember = activeMember();
		long holdMember = activeMember();
		long personalMember = activeMember();
		long otherWeekMember = activeMember();
		History pass = history(passMember, week, "P", team);
		History fail = history(failMember, week, "F", team);
		History first = history(firstWeekMember, week, "X", team);
		History hold = history(holdMember, week, "H", team);
		long personal = data.judgment(personalMember, week, "EXEMPT", "PERSONAL_EXEMPTION", null, 0, null, null, false,
				week.plusDays(7).atTime(7, 0));
		History otherWeek = history(otherWeekMember, week.plusWeeks(1), "P", team);

		JsonNode created = createPeriod(week, "  중간고사  ");

		assertThat(created.path("weekStart").asText()).isEqualTo(week.toString());
		assertThat(created.path("weekEnd").asText()).isEqualTo(week.plusDays(6).toString());
		assertThat(created.path("reason").asText()).isEqualTo("중간고사");
		assertThat(created.path("updatedAt").isNull()).isTrue();
		assertThat(periodCount(week)).isEqualTo(1);

		assertThat(judgmentStatus(pass.judgment(0))).isEqualTo("EXEMPT");
		assertThat(skipReason(pass.judgment(0))).isEqualTo("EXEMPTION_PERIOD");
		assertThat(judgmentStatus(fail.judgment(0))).isEqualTo("EXEMPT");
		assertThat(skipReason(fail.judgment(0))).isEqualTo("EXEMPTION_PERIOD");
		assertThat(judgmentStatus(first.judgment(0))).isEqualTo("EXCLUDED");
		assertThat(skipReason(first.judgment(0))).isEqualTo("FIRST_WEEK");
		assertThat(judgmentStatus(hold.judgment(0))).isEqualTo("HOLD");
		assertThat(judgmentStatus(personal)).isEqualTo("EXEMPT");
		assertThat(skipReason(personal)).isEqualTo("PERSONAL_EXEMPTION");
		assertThat(judgmentStatus(otherWeek.judgment(0))).isEqualTo("PASS");
		// 면제로 바뀐 판정은 정정 이력·관리자 로그 대상이 아니다
		assertThat(corrected(pass.judgment(0))).isFalse();
		assertThat(correctionCount(pass.judgment(0))).isZero();
		// 미달이 면제가 되면 그 주 경고는 세지 않는다
		assertThat(state(failMember).warningCount()).isZero();
	}

	@Test
	@DisplayName("미리보기는 DB를 하나도 바꾸지 않고, 반영한 뒤의 경고 수는 미리보기에서 본 값과 같다")
	void previewChangesNothingAndMatchesTheAppliedResult() {
		LocalDate week = BASE.plusWeeks(2);
		long leader = activeMember();
		long team = newTeam("미리보기팀", leader);
		long penaltyMember = activeMember();
		long revertMember = activeMember();
		long streakMember = activeMember();
		long holdMember = activeMember();
		// F F F: 세 번째 미달을 면제하면 3 -> 2, 벌칙 대상에서 빠진다
		history(penaltyMember, BASE, "F F F", team);
		// F P P: 두 번째 통과를 면제하면 2주 연속이 깨져 차감이 사라진다 (0 -> 1)
		history(revertMember, BASE, "F P P", team);
		// P P P P: 경고가 없으면 면제로 연속이 달라져도 경고 수는 0 그대로다
		history(streakMember, BASE, "P P P P", team);
		// H P P: 보류 주가 남아 있어 비교할 수 없다
		history(holdMember, BASE, "H P P", team);
		String before = snapshot(penaltyMember, revertMember, streakMember, holdMember);

		JsonNode preview = previewPeriod(week);
		JsonNode again = previewPeriod(week);

		assertThat(again).isEqualTo(preview);
		assertThat(snapshot(penaltyMember, revertMember, streakMember, holdMember)).isEqualTo(before);
		assertThat(periodCount(week)).isZero();
		assertThat(preview.path("weekStart").asText()).isEqualTo(week.toString());
		assertThat(preview.path("retroactive").asBoolean()).isTrue();

		JsonNode penalty = impactOf(preview, penaltyMember);
		assertThat(penalty.path("currentStatus").asText()).isEqualTo("FAIL");
		assertThat(penalty.path("impact").path("warningCountBefore").asInt()).isEqualTo(3);
		assertThat(penalty.path("impact").path("warningCountAfter").asInt()).isEqualTo(2);
		assertThat(penalty.path("impact").path("leavesPenalty").asBoolean()).isTrue();
		assertThat(penalty.path("impact").path("entersPenalty").asBoolean()).isFalse();
		assertThat(penalty.path("impact").path("recordOnly").asBoolean()).isFalse();
		JsonNode revert = impactOf(preview, revertMember);
		assertThat(revert.path("currentStatus").asText()).isEqualTo("PASS");
		assertThat(revert.path("impact").path("warningCountBefore").asInt()).isZero();
		assertThat(revert.path("impact").path("warningCountAfter").asInt()).isEqualTo(1);
		assertThat(revert.path("impact").path("affectedWeeks").asInt()).isZero();
		JsonNode streak = impactOf(preview, streakMember);
		assertThat(streak.path("impact").path("warningCountBefore").asInt()).isZero();
		assertThat(streak.path("impact").path("warningCountAfter").asInt()).isZero();
		assertThat(streak.path("impact").path("affectedWeeks").asInt()).isEqualTo(1);
		// 보류 주가 남은 회원은 경고 수를 비교하지 않는다 (Q-03)
		JsonNode onHold = impactOf(preview, holdMember);
		assertThat(onHold.path("impact").path("onHold").asBoolean()).isTrue();
		assertThat(onHold.path("impact").path("warningCountBefore").isNull()).isTrue();
		assertThat(onHold.path("impact").path("warningCountAfter").isNull()).isTrue();

		createPeriod(week, "중간고사");

		assertThat(state(penaltyMember).warningCount()).isEqualTo(2);
		assertThat(state(penaltyMember).penaltyTarget()).isFalse();
		assertThat(state(revertMember).warningCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("면제 주는 연속 통과를 끊지도 늘리지도 않고 그 주 이후 차감까지 처음부터 다시 계산된다 (E-45, E-46)")
	void exemptWeekNeitherBreaksNorExtendsTheStreak() {
		LocalDate week = BASE.plusWeeks(2);
		long leader = activeMember();
		long team = newTeam("연속팀", leader);
		long extendMember = activeMember();
		long breakMember = activeMember();
		// F P P: 3주째가 면제가 되면 연속은 1에서 멈춘다(늘지 않음). 차감이 사라져 경고 1개가 남는다
		history(extendMember, BASE, "F P P", team);
		// F P F P: 2주째 미달이 면제가 되면 앞뒤 통과가 이어져 차감된다(끊기지 않음)
		history(breakMember, BASE, "F P F P", team);
		assertThat(state(extendMember).warningCount()).isZero();
		assertThat(state(breakMember).warningCount()).isEqualTo(2);
		assertThat(state(breakMember).streak()).isEqualTo(1);

		JsonNode preview = previewPeriod(week);
		assertThat(impactOf(preview, extendMember).path("impact").path("warningCountAfter").asInt()).isEqualTo(1);
		assertThat(impactOf(preview, breakMember).path("impact").path("warningCountAfter").asInt()).isZero();
		// 면제로 바뀌는 주 뒤에 판정이 하나 더 있으면 영향 주차는 1
		assertThat(impactOf(preview, breakMember).path("impact").path("affectedWeeks").asInt()).isEqualTo(1);

		createPeriod(week, "시험 기간");

		WarningRecalcResult.Calculated extended = state(extendMember);
		assertThat(extended.warningCount()).isEqualTo(1);
		assertThat(extended.streak()).isEqualTo(1);
		WarningRecalcResult.Calculated broken = state(breakMember);
		assertThat(broken.warningCount()).isZero();
		assertThat(broken.streak()).isZero();
		assertThat(broken.deductedWarningWeeks()).containsExactly(BASE);
	}

	@Test
	@DisplayName("벌칙 이행 체크 이전 주를 소급 면제하면 기록만 바뀌고 이행 이후 경고 수는 그대로다 (E-58)")
	void retroactiveExemptionBeforeFulfillmentOnlyChangesTheRecord() {
		long leader = activeMember();
		long team = newTeam("고정점팀", leader);
		long member = activeMember();
		// 0~2주 미달로 벌칙 대상이 됐고 3주 월요일 새벽에 이행 체크, 그 뒤 3·4주 미달
		History history = history(member, BASE, "F F F F F", team);
		data.fulfillment(member, adminId, BASE.plusWeeks(3).atTime(1, 0), BASE.plusWeeks(2));
		assertThat(state(member).warningCount()).isEqualTo(2);

		JsonNode before = impactOf(previewPeriod(BASE.plusWeeks(1)), member).path("impact");
		assertThat(before.path("recordOnly").asBoolean()).isTrue();
		assertThat(before.path("warningCountBefore").asInt()).isEqualTo(2);
		assertThat(before.path("warningCountAfter").asInt()).isEqualTo(2);
		assertThat(before.path("affectedWeeks").asInt()).isZero();

		createPeriod(BASE.plusWeeks(1), "이행 이전 주");

		assertThat(judgmentStatus(history.judgment(1))).isEqualTo("EXEMPT");
		assertThat(state(member).warningCount()).isEqualTo(2);
		assertThat(state(member).penaltyTarget()).isFalse();

		// 이행 이후 주는 같은 면제여도 경고 수에 반영된다
		JsonNode after = impactOf(previewPeriod(BASE.plusWeeks(3)), member).path("impact");
		assertThat(after.path("recordOnly").asBoolean()).isFalse();
		assertThat(after.path("warningCountBefore").asInt()).isEqualTo(2);
		assertThat(after.path("warningCountAfter").asInt()).isEqualTo(1);
		assertThat(after.path("affectedWeeks").asInt()).isEqualTo(1);
		createPeriod(BASE.plusWeeks(3), "이행 이후 주");
		assertThat(state(member).warningCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("보류 주는 면제 기간을 등록해도 그대로 두었다가 재판정 때 면제로 확정되고 GitHub를 부르지 않는다")
	void holdWeekBecomesExemptOnlyWhenRejudged() {
		LocalDate week = BASE.plusWeeks(2);
		long leader = activeMember();
		long team = newTeam("보류팀", leader);
		long member = activeMember();
		History history = history(member, week, "H", team);
		JsonNode preview = previewPeriod(week);
		assertThat(preview.path("retroactive").asBoolean()).isFalse();
		assertThat(preview.path("items")).isEmpty();

		createPeriod(week, "중간고사");

		assertThat(judgmentStatus(history.judgment(0))).isEqualTo("HOLD");
		assertThat(recalculation.calculate(member)).isInstanceOf(WarningRecalcResult.OnHold.class);

		JsonNode retried = dataOf(asAdmin(RETRY_HOLD, Map.of("memberId", Long.toString(member), "weekStart",
				week.toString()))).path("retryJudgmentHold");

		assertThat(retried.path("status").asText()).isEqualTo("EXEMPT");
		assertThat(retried.path("resolved").asBoolean()).isTrue();
		assertThat(skipReason(history.judgment(0))).isEqualTo("EXEMPTION_PERIOD");
		assertThat(grassClient.calls()).isZero();
		assertThat(state(member).warningCount()).isZero();
	}

	@Test
	@DisplayName("아직 판정되지 않은 주를 등록하면 바뀌는 판정이 없고 미리보기도 소급 아님으로 답한다")
	void futureWeekIsNotRetroactive() {
		LocalDate week = BASE.plusWeeks(30);
		long leader = activeMember();
		long team = newTeam("미판정팀", leader);
		long member = activeMember();
		history(member, BASE, "P", team);

		JsonNode preview = previewPeriod(week);
		assertThat(preview.path("retroactive").asBoolean()).isFalse();
		assertThat(preview.path("items")).isEmpty();

		createPeriod(week, "방학");

		assertThat(periodCount(week)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from weekly_judgment where member_id = ?", Integer.class,
				member)).isEqualTo(1);
	}

	@Test
	@DisplayName("같은 주 중복 등록, 월요일이 아닌 날짜, 빈 사유, 200자 초과 사유는 거부하고 아무것도 만들지 않는다")
	void rejectsInvalidRegistration() {
		LocalDate week = BASE.plusWeeks(5);
		long leader = activeMember();
		long team = newTeam("검증팀", leader);
		long member = activeMember();
		History history = history(member, week, "P", team);
		createPeriod(week, "처음");

		assertThat(createPeriodRaw(week, "또").errorCode()).isEqualTo("EXEMPTION_PERIOD_DUPLICATE");
		assertThat(periodCount(week)).isEqualTo(1);

		LocalDate tuesday = BASE.plusWeeks(6).plusDays(1);
		assertThat(createPeriodRaw(tuesday, "화요일").errorCode()).isEqualTo("INVALID_INPUT");
		assertThat(asAdmin(CREATE_PERIOD, Map.of("input", Map.of("weekStart", "2046-13-45", "reason", "날짜")))
				.errorCode()).isEqualTo("INVALID_INPUT");
		assertThat(createPeriodRaw(BASE.plusWeeks(6), "   ").errorCode()).isEqualTo("REASON_REQUIRED");
		assertThat(createPeriodRaw(BASE.plusWeeks(6), "가".repeat(201)).errorCode()).isEqualTo("INVALID_INPUT");
		assertThat(periodCount(BASE.plusWeeks(6))).isZero();
		assertThat(periodCount(tuesday)).isZero();

		// 200자는 경계라 허용한다
		JsonNode boundary = createPeriod(BASE.plusWeeks(6), "가".repeat(200));
		assertThat(boundary.path("reason").asText()).hasSize(200);
		assertThat(judgmentStatus(history.judgment(0))).isEqualTo("EXEMPT");
	}

	@Test
	@DisplayName("사유만 고치면 수정 시각만 남고, 다른 주로 옮기면 새 주에 소급 면제가 걸리지만 이미 바뀐 판정은 되돌리지 않는다")
	void updateMovesRetroactiveExemptionWithoutRevertingTheOldWeek() {
		LocalDate oldWeek = BASE.plusWeeks(3);
		LocalDate newWeek = BASE.plusWeeks(5);
		long leader = activeMember();
		long team = newTeam("수정팀", leader);
		long oldMember = activeMember();
		long newMember = activeMember();
		History old = history(oldMember, oldWeek, "P", team);
		History fresh = history(newMember, newWeek, "F", team);
		long periodId = createPeriod(oldWeek, "원래 사유").path("id").asLong();
		assertThat(judgmentStatus(old.judgment(0))).isEqualTo("EXEMPT");

		JsonNode reasonOnly = dataOf(asAdmin(UPDATE_PERIOD, Map.of("id", Long.toString(periodId), "input",
				Map.of("weekStart", oldWeek.toString(), "reason", "  바뀐 사유 ")))).path("updateExemptionPeriod");
		assertThat(reasonOnly.path("id").asLong()).isEqualTo(periodId);
		assertThat(reasonOnly.path("weekStart").asText()).isEqualTo(oldWeek.toString());
		assertThat(reasonOnly.path("reason").asText()).isEqualTo("바뀐 사유");
		assertThat(reasonOnly.path("updatedAt").isNull()).isFalse();
		assertThat(judgmentStatus(fresh.judgment(0))).isEqualTo("FAIL");

		JsonNode moved = dataOf(asAdmin(UPDATE_PERIOD, Map.of("id", Long.toString(periodId), "input",
				Map.of("weekStart", newWeek.toString(), "reason", "옮긴 사유")))).path("updateExemptionPeriod");

		assertThat(moved.path("weekStart").asText()).isEqualTo(newWeek.toString());
		assertThat(moved.path("weekEnd").asText()).isEqualTo(newWeek.plusDays(6).toString());
		assertThat(periodCount(oldWeek)).isZero();
		assertThat(periodCount(newWeek)).isEqualTo(1);
		assertThat(judgmentStatus(fresh.judgment(0))).isEqualTo("EXEMPT");
		assertThat(skipReason(fresh.judgment(0))).isEqualTo("EXEMPTION_PERIOD");
		assertThat(state(newMember).warningCount()).isZero();
		// 확정 판정은 정정으로만 바뀐다: 이미 면제로 바뀐 옛 주 판정은 그대로다
		assertThat(judgmentStatus(old.judgment(0))).isEqualTo("EXEMPT");
	}

	@Test
	@DisplayName("수정 입력이 틀리거나 이미 있는 주로 옮기거나 없는 면제 기간이면 거부하고 원래 값을 지킨다")
	void rejectsInvalidUpdate() {
		LocalDate weekA = BASE.plusWeeks(3);
		LocalDate weekB = BASE.plusWeeks(4);
		long periodA = createPeriod(weekA, "A").path("id").asLong();
		createPeriod(weekB, "B");

		assertThat(asAdmin(UPDATE_PERIOD, Map.of("id", Long.toString(periodA), "input",
				Map.of("weekStart", weekB.toString(), "reason", "충돌"))).errorCode())
				.isEqualTo("EXEMPTION_PERIOD_DUPLICATE");
		assertThat(asAdmin(UPDATE_PERIOD, Map.of("id", Long.toString(periodA), "input",
				Map.of("weekStart", weekA.plusDays(2).toString(), "reason", "수요일"))).errorCode())
				.isEqualTo("INVALID_INPUT");
		assertThat(asAdmin(UPDATE_PERIOD, Map.of("id", Long.toString(periodA), "input",
				Map.of("weekStart", weekA.toString(), "reason", " "))).errorCode()).isEqualTo("REASON_REQUIRED");
		assertThat(asAdmin(UPDATE_PERIOD, Map.of("id", "999999999999", "input",
				Map.of("weekStart", weekA.toString(), "reason", "없음"))).errorCode()).isEqualTo("NOT_FOUND");
		assertThat(asAdmin(UPDATE_PERIOD, Map.of("id", "abc", "input",
				Map.of("weekStart", weekA.toString(), "reason", "문자"))).errorCode()).isEqualTo("NOT_FOUND");

		assertThat(periodCount(weekA)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select reason from exemption_period where id = ?", String.class, periodA))
				.isEqualTo("A");
	}

	@Test
	@DisplayName("면제 기간을 지우면 목록에서 사라지지만 이미 면제로 바뀐 판정은 그대로이고 같은 주를 다시 등록할 수 있다")
	void deleteKeepsAlreadyExemptJudgments() {
		LocalDate week = BASE.plusWeeks(3);
		long leader = activeMember();
		long team = newTeam("삭제팀", leader);
		long member = activeMember();
		History history = history(member, week, "F", team);
		long periodId = createPeriod(week, "삭제할 면제").path("id").asLong();
		assertThat(judgmentStatus(history.judgment(0))).isEqualTo("EXEMPT");
		WarningRecalcResult.Calculated before = state(member);

		assertThat(dataOf(asAdmin(DELETE_PERIOD, idVariables(periodId))).path("deleteExemptionPeriod").asBoolean())
				.isTrue();

		assertThat(periodCount(week)).isZero();
		assertThat(allPeriods()).noneMatch(item -> item.path("id").asLong() == periodId);
		assertThat(judgmentStatus(history.judgment(0))).isEqualTo("EXEMPT");
		assertThat(state(member)).isEqualTo(before);
		assertThat(asAdmin(DELETE_PERIOD, idVariables(periodId)).errorCode()).isEqualTo("NOT_FOUND");
		assertThat(asAdmin(DELETE_PERIOD, Map.of("id", "abc")).errorCode()).isEqualTo("NOT_FOUND");
		assertThat(createPeriod(week, "다시 등록").path("weekStart").asText()).isEqualTo(week.toString());
	}

	@Test
	@DisplayName("목록은 주차 최신순 커서 20개씩이고 잘못된 커서는 거부한다")
	void listsNewestWeekFirstTwentyPerPage() {
		for (int i = 0; i <= 20; i++) {
			jdbc.update("insert into exemption_period (week_start, reason, created_by, created_at) values (?, ?, ?, ?)",
					BASE.plusWeeks(i), "기간 " + i, adminId, LocalDateTime.of(2046, 1, 1, 0, 0));
		}

		JsonNode first = dataOf(asAdmin(LIST_PERIODS, Map.of())).path("exemptionPeriods");
		assertThat(first.path("items")).hasSize(20);
		assertThat(first.path("nextCursor").isNull()).isFalse();
		assertThat(first.path("items").get(0).path("weekStart").asText()).isEqualTo(BASE.plusWeeks(20).toString());
		assertThat(first.path("items").get(0).path("weekEnd").asText())
				.isEqualTo(BASE.plusWeeks(20).plusDays(6).toString());
		assertThat(first.path("items").get(19).path("weekStart").asText()).isEqualTo(BASE.plusWeeks(1).toString());

		JsonNode second = dataOf(asAdmin(LIST_PERIODS, Map.of("after", first.path("nextCursor").asText())))
				.path("exemptionPeriods");
		assertThat(second.path("items").get(0).path("weekStart").asText()).isEqualTo(BASE.toString());
		List<String> weeks = new ArrayList<>();
		allPeriods().forEach(item -> weeks.add(item.path("weekStart").asText()));
		assertThat(weeks).isSortedAccordingTo((a, b) -> b.compareTo(a));

		assertThat(asAdmin(LIST_PERIODS, Map.of("after", "!!!")).errorCode()).isEqualTo("INVALID_INPUT");
		// 디코딩은 되지만 날짜가 아닌 커서
		String notDate = java.util.Base64.getUrlEncoder().withoutPadding()
				.encodeToString("not-a-date".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		assertThat(asAdmin(LIST_PERIODS, Map.of("after", notDate)).errorCode()).isEqualTo("INVALID_INPUT");
	}

	@Test
	@DisplayName("같은 주를 동시에 등록하면 하나만 만들어지고 판정은 한 번만 면제로 바뀐다")
	void concurrentRegistrationOfTheSameWeekCreatesOne() {
		LocalDate week = BASE.plusWeeks(7);
		long leader = activeMember();
		long team = newTeam("동시팀", leader);
		long member = activeMember();
		History history = history(member, week, "F", team);

		List<GraphQlResponse> results = runTogether(() -> createPeriodRaw(week, "하나"),
				() -> createPeriodRaw(week, "둘"));

		List<GraphQlResponse> succeeded = results.stream().filter(r -> !r.hasErrors()).toList();
		List<GraphQlResponse> failed = results.stream().filter(GraphQlResponse::hasErrors).toList();
		assertThat(succeeded).hasSize(1);
		assertThat(failed).hasSize(1);
		assertThat(failed.get(0).errorCode()).isEqualTo("EXEMPTION_PERIOD_DUPLICATE");
		assertThat(periodCount(week)).isEqualTo(1);
		assertThat(judgmentStatus(history.judgment(0))).isEqualTo("EXEMPT");
	}

	@Test
	@DisplayName("면제 기간 관리는 관리자만 하고 일반 회원·승인 대기·비로그인은 아무것도 바꾸지 못한다")
	void onlyAdminManagesExemptionPeriods() {
		LocalDate week = BASE.plusWeeks(3);
		long leader = activeMember();
		long team = newTeam("권한팀", leader);
		long member = activeMember();
		History history = history(member, week, "P", team);
		long periodId = jdbcInsertPeriod(BASE.plusWeeks(9), "남의 기간");
		String before = snapshot(member);

		long plain = activeMember();
		long pending = pendingMember();
		for (String caller : List.of("member", "pending", "anonymous")) {
			String expected = switch (caller) {
				case "member" -> "FORBIDDEN";
				case "pending" -> "ACCOUNT_PENDING";
				default -> "UNAUTHENTICATED";
			};
			assertThat(call(caller, plain, pending, CREATE_PERIOD, periodInput(week, "무권한")).errorCode())
					.as(caller + " 등록").isEqualTo(expected);
			assertThat(call(caller, plain, pending, UPDATE_PERIOD, Map.of("id", Long.toString(periodId), "input",
					Map.of("weekStart", BASE.plusWeeks(9).toString(), "reason", "바꿈"))).errorCode())
					.as(caller + " 수정").isEqualTo(expected);
			assertThat(call(caller, plain, pending, DELETE_PERIOD, idVariables(periodId)).errorCode())
					.as(caller + " 삭제").isEqualTo(expected);
			assertThat(call(caller, plain, pending, LIST_PERIODS, Map.of()).errorCode()).as(caller + " 목록")
					.isEqualTo(expected);
			assertThat(call(caller, plain, pending, PREVIEW_PERIOD, Map.of("weekStart", week.toString())).errorCode())
					.as(caller + " 미리보기").isEqualTo(expected);
		}

		assertThat(periodCount(week)).isZero();
		assertThat(periodCount(BASE.plusWeeks(9))).isEqualTo(1);
		assertThat(jdbc.queryForObject("select reason from exemption_period where id = ?", String.class, periodId))
				.isEqualTo("남의 기간");
		assertThat(judgmentStatus(history.judgment(0))).isEqualTo("PASS");
		assertThat(snapshot(member)).isEqualTo(before);
	}

	private GraphQlResponse call(String caller, long plain, long pending, String query, Map<String, Object> variables) {
		return switch (caller) {
			case "member" -> asMember(plain, query, variables);
			case "pending" -> asMember(pending, query, variables);
			default -> graphQl.post(null, query, variables);
		};
	}

	private long jdbcInsertPeriod(LocalDate week, String reason) {
		jdbc.update("insert into exemption_period (week_start, reason, created_by, created_at) values (?, ?, ?, ?)",
				week, reason, adminId, LocalDateTime.of(2046, 1, 1, 0, 0));
		return jdbc.queryForObject("select id from exemption_period where week_start = ?", Long.class, week);
	}

	private List<JsonNode> allPeriods() {
		List<JsonNode> items = new ArrayList<>();
		String cursor = null;
		do {
			Map<String, Object> variables = cursor == null ? Map.of() : Map.of("after", cursor);
			JsonNode page = dataOf(asAdmin(LIST_PERIODS, variables)).path("exemptionPeriods");
			page.path("items").forEach(items::add);
			cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText();
		} while (cursor != null);
		return items;
	}

}
