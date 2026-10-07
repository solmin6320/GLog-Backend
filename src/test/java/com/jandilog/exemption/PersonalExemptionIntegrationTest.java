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
import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.databind.JsonNode;
import com.jandilog.judgment.service.JudgmentRecordService.Outcome;
import com.jandilog.judgment.service.MemberJudgmentService;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;
import com.jandilog.testsupport.exemption.ExemptionIntegrationTest;

// 개인 면제: 팀장 요청 → 관리자 승인·거절 (기능명세서 7·9장, TM-08, AD-01 면제 관리 ⑦, E-44 · E-47 · E-48).
// 면제 기간과 겹치는 시험은 전체 회원에 걸리는 면제 기간 행을 쓰므로 2047년 주차만 쓴다
class PersonalExemptionIntegrationTest extends ExemptionIntegrationTest {

	private static final LocalDate WEEK = monday(2047, 3, 4);

	@Autowired
	private MemberJudgmentService memberJudgment;

	@BeforeEach
	void fixClock() {
		fixClockAt(WEEK.plusWeeks(10).atTime(10, 0));
	}

	@Test
	@DisplayName("팀장은 팀원과 본인에게 개인 면제를 요청할 수 있고 요청은 대기 상태로 남는다")
	void leaderRequestsForTeammateAndForSelf() {
		long leader = activeMember();
		long team = newTeam("요청팀", leader);
		long mate = activeMember();
		join(team, mate);

		JsonNode forMate = requestPersonal(leader, team, mate, WEEK, "  가족 행사  ");

		assertThat(forMate.path("id").asLong()).isPositive();
		assertThat(forMate.path("member").path("id").asLong()).isEqualTo(mate);
		assertThat(forMate.path("requester").path("id").asLong()).isEqualTo(leader);
		assertThat(forMate.path("weekStart").asText()).isEqualTo(WEEK.toString());
		assertThat(forMate.path("weekEnd").asText()).isEqualTo(WEEK.plusDays(6).toString());
		assertThat(forMate.path("status").asText()).isEqualTo("PENDING");
		assertThat(forMate.path("reason").asText()).isEqualTo("가족 행사");
		assertThat(forMate.path("rejectReason").isNull()).isTrue();
		assertThat(forMate.path("respondedAt").isNull()).isTrue();
		assertThat(forMate.path("createdAt").isNull()).isFalse();
		assertThat(forMate.path("overlapsExemptionPeriod").asBoolean()).isFalse();
		assertThat(personalExemptionStatus(forMate.path("id").asLong())).isEqualTo("PENDING");

		JsonNode forSelf = requestPersonal(leader, team, leader, WEEK, "본인 사정");
		assertThat(forSelf.path("member").path("id").asLong()).isEqualTo(leader);
		assertThat(forSelf.path("requester").path("id").asLong()).isEqualTo(leader);
		assertThat(personalExemptionCount(leader)).isEqualTo(1);
	}

	@Test
	@DisplayName("그 팀의 팀장만 요청할 수 있고 팀원·다른 팀 팀장·관리자·없는 팀·삭제된 팀은 거부한다")
	void onlyThatTeamsLeaderCanRequest() {
		long leader = activeMember();
		long team = newTeam("권한팀", leader);
		long mate = activeMember();
		join(team, mate);
		long otherLeader = activeMember();
		newTeam("다른팀", otherLeader);
		long deletedTeam = data.team(data.uniqueName("삭제된팀"), true, leader, LocalDateTime.of(2026, 5, 1, 0, 0));
		data.teamMember(deletedTeam, mate, JOINED_AT, null);

		// 팀원이 팀장 몫을 대신하지 못한다
		assertThat(requestPersonalRaw(mate, team, mate, WEEK, "내가 요청").errorCode()).isEqualTo("FORBIDDEN");
		// 다른 팀의 팀장은 이 팀 팀원을 요청할 수 없다
		assertThat(requestPersonalRaw(otherLeader, team, mate, WEEK, "남의 팀").errorCode()).isEqualTo("FORBIDDEN");
		// 관리자라도 남의 팀 팀장 자격은 없다 (기능명세서 9장)
		assertThat(asAdmin(REQUEST_PERSONAL, requestVariables(team, mate, WEEK, "관리자")).errorCode())
				.isEqualTo("FORBIDDEN");
		assertThat(requestPersonalRaw(leader, 999_999_999_999L, mate, WEEK, "없는 팀").errorCode())
				.isEqualTo("NOT_FOUND");
		assertThat(requestPersonalRaw(leader, deletedTeam, mate, WEEK, "삭제된 팀").errorCode()).isEqualTo("NOT_FOUND");

		assertThat(personalExemptionCount(mate)).isZero();
	}

	@Test
	@DisplayName("대상은 그 팀의 지금 팀원이거나 팀장 본인이어야 한다 (나간 사람·다른 팀 사람은 거부)")
	void targetMustBeCurrentTeamMember() {
		long leader = activeMember();
		long team = newTeam("대상팀", leader);
		long outsider = activeMember();
		long left = activeMember();
		data.teamMember(team, left, JOINED_AT, LocalDateTime.of(2026, 2, 1, 0, 0));
		long otherLeader = activeMember();
		long otherTeam = newTeam("옆팀", otherLeader);
		long otherMember = activeMember();
		join(otherTeam, otherMember);

		assertThat(requestPersonalRaw(leader, team, outsider, WEEK, "소속 없음").errorCode()).isEqualTo("INVALID_INPUT");
		assertThat(requestPersonalRaw(leader, team, left, WEEK, "나간 사람").errorCode()).isEqualTo("INVALID_INPUT");
		assertThat(requestPersonalRaw(leader, team, otherMember, WEEK, "다른 팀 사람").errorCode())
				.isEqualTo("INVALID_INPUT");
		assertThat(personalExemptionCount(outsider) + personalExemptionCount(left) + personalExemptionCount(otherMember))
				.isZero();
	}

	@Test
	@DisplayName("같은 회원·주간에 대기·승인 건이 있으면 중복 요청을 거부하고 거절된 뒤에는 다시 요청할 수 있다 (E-47)")
	void duplicateRequestIsRejectedUntilRejected() {
		long leader = activeMember();
		long team = newTeam("중복팀", leader);
		long mate = activeMember();
		join(team, mate);
		long otherMate = activeMember();
		join(team, otherMate);
		// mate는 두 팀에 속하고 다른 팀 팀장도 같은 주를 요청할 수 있다
		long otherLeader = activeMember();
		long otherTeam = newTeam("두번째팀", otherLeader);
		join(otherTeam, mate);

		long first = requestPersonal(leader, team, mate, WEEK, "첫 요청").path("id").asLong();
		assertThat(requestPersonalRaw(leader, team, mate, WEEK, "또").errorCode()).isEqualTo("EXEMPTION_REQUEST_DUPLICATE");
		// 사람 단위 중복이라 다른 팀 팀장의 요청도 막는다
		assertThat(requestPersonalRaw(otherLeader, otherTeam, mate, WEEK, "옆팀 요청").errorCode())
				.isEqualTo("EXEMPTION_REQUEST_DUPLICATE");
		// 다른 주·다른 회원은 허용한다
		requestPersonal(leader, team, mate, WEEK.plusWeeks(1), "다음 주");
		requestPersonal(leader, team, otherMate, WEEK, "다른 사람");
		assertThat(personalExemptionCount(mate)).isEqualTo(2);

		// 승인된 뒤에도 같은 주 재요청은 막는다
		approve(first);
		assertThat(requestPersonalRaw(leader, team, mate, WEEK, "승인 뒤").errorCode())
				.isEqualTo("EXEMPTION_REQUEST_DUPLICATE");

		// 거절되면 다시 요청할 수 있다
		long rejected = requestPersonal(leader, team, otherMate, WEEK.plusWeeks(2), "거절될 요청").path("id").asLong();
		dataOf(asAdmin(REJECT, Map.of("id", Long.toString(rejected), "reason", "근거 부족")));
		JsonNode again = requestPersonal(leader, team, otherMate, WEEK.plusWeeks(2), "다시");
		assertThat(again.path("id").asLong()).isNotEqualTo(rejected);
		assertThat(again.path("status").asText()).isEqualTo("PENDING");
		assertThat(requestPersonalRaw(leader, team, otherMate, WEEK.plusWeeks(2), "또 다시").errorCode())
				.isEqualTo("EXEMPTION_REQUEST_DUPLICATE");
	}

	@Test
	@DisplayName("월요일이 아닌 주, 빈 사유, 200자 초과 사유, 잘못된 id는 거부하고 승인 대기·비로그인은 요청할 수 없다")
	void validatesRequestInput() {
		long leader = activeMember();
		long team = newTeam("입력팀", leader);
		long mate = activeMember();
		join(team, mate);

		assertThat(requestPersonalRaw(leader, team, mate, WEEK.plusDays(1), "화요일").errorCode())
				.isEqualTo("INVALID_INPUT");
		assertThat(asMember(leader, REQUEST_PERSONAL, Map.of("teamId", Long.toString(team), "memberId",
				Long.toString(mate), "weekStart", "yesterday", "reason", "문자열")).errorCode())
				.isEqualTo("INVALID_INPUT");
		assertThat(requestPersonalRaw(leader, team, mate, WEEK, "   ").errorCode()).isEqualTo("REASON_REQUIRED");
		assertThat(requestPersonalRaw(leader, team, mate, WEEK, "가".repeat(201)).errorCode())
				.isEqualTo("INVALID_INPUT");
		assertThat(asMember(leader, REQUEST_PERSONAL, Map.of("teamId", "abc", "memberId", Long.toString(mate),
				"weekStart", WEEK.toString(), "reason", "팀 id")).errorCode()).isEqualTo("NOT_FOUND");
		assertThat(asMember(leader, REQUEST_PERSONAL, Map.of("teamId", Long.toString(team), "memberId", "abc",
				"weekStart", WEEK.toString(), "reason", "회원 id")).errorCode()).isEqualTo("NOT_FOUND");
		assertThat(personalExemptionCount(mate)).isZero();

		// 200자는 경계라 허용한다
		assertThat(requestPersonal(leader, team, mate, WEEK, "가".repeat(200)).path("reason").asText()).hasSize(200);

		long pending = pendingMember();
		assertThat(asMember(pending, REQUEST_PERSONAL, requestVariables(team, mate, WEEK.plusWeeks(1), "대기"))
				.errorCode()).isEqualTo("ACCOUNT_PENDING");
		assertThat(graphQl.post(null, REQUEST_PERSONAL, requestVariables(team, mate, WEEK.plusWeeks(1), "비로그인"))
				.errorCode()).isEqualTo("UNAUTHENTICATED");
		assertThat(personalExemptionCount(mate)).isEqualTo(1);
	}

	@Test
	@DisplayName("승인하면 이미 판정된 그 회원의 통과·미달이 면제로 바뀌고 다른 팀원·다른 주 판정은 그대로다")
	void approvalExemptsOnlyThatPersonsConfirmedJudgment() {
		long leader = activeMember();
		long team = newTeam("승인팀", leader);
		long failMember = activeMember();
		long passMember = activeMember();
		long mate = activeMember();
		long notJudged = activeMember();
		join(team, failMember);
		join(team, passMember);
		join(team, mate);
		join(team, notJudged);
		History fail = history(failMember, WEEK, "F P", team);
		History pass = history(passMember, WEEK, "P", team);
		History teammate = history(mate, WEEK, "F", team);
		long failRequest = requestPersonal(leader, team, failMember, WEEK, "병가").path("id").asLong();
		long passRequest = requestPersonal(leader, team, passMember, WEEK, "여행").path("id").asLong();
		long futureRequest = requestPersonal(leader, team, notJudged, WEEK, "아직 판정 전").path("id").asLong();
		assertThat(state(failMember).warningCount()).isEqualTo(1);

		JsonNode approved = approve(failRequest);
		approve(passRequest);
		approve(futureRequest);

		assertThat(approved.path("status").asText()).isEqualTo("APPROVED");
		assertThat(approved.path("respondedAt").isNull()).isFalse();
		assertThat(approved.path("rejectReason").isNull()).isTrue();
		assertThat(personalExemptionStatus(failRequest)).isEqualTo("APPROVED");
		assertThat(judgmentStatus(fail.judgment(0))).isEqualTo("EXEMPT");
		assertThat(skipReason(fail.judgment(0))).isEqualTo("PERSONAL_EXEMPTION");
		assertThat(judgmentStatus(pass.judgment(0))).isEqualTo("EXEMPT");
		assertThat(skipReason(pass.judgment(0))).isEqualTo("PERSONAL_EXEMPTION");
		// 같은 팀 팀원은 면제가 번지지 않는다
		assertThat(judgmentStatus(teammate.judgment(0))).isEqualTo("FAIL");
		// 다른 주 판정은 그대로, 아직 판정되지 않은 주는 행을 만들지 않는다
		assertThat(judgmentStatus(fail.judgment(1))).isEqualTo("PASS");
		assertThat(jdbc.queryForObject("select count(*) from weekly_judgment where member_id = ?", Integer.class,
				notJudged)).isZero();
		// 면제로 바뀐 미달 주의 경고는 세지 않는다
		assertThat(state(failMember).warningCount()).isZero();
		assertThat(state(mate).warningCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("승인 전 미리보기는 DB를 바꾸지 않고 경고 변화를 보여주며 판정 전 주는 소급 아님으로 답한다")
	void approvalPreviewChangesNothing() {
		long leader = activeMember();
		long team = newTeam("미리보기팀", leader);
		long failMember = activeMember();
		long holdMember = activeMember();
		long futureMember = activeMember();
		join(team, failMember);
		join(team, holdMember);
		join(team, futureMember);
		// F F F 중 세 번째를 면제하면 벌칙 대상에서 빠진다
		history(failMember, WEEK.minusWeeks(2), "F F F", team);
		history(holdMember, WEEK, "H", team);
		long failRequest = requestPersonal(leader, team, failMember, WEEK, "병가").path("id").asLong();
		long holdRequest = requestPersonal(leader, team, holdMember, WEEK, "보류 주").path("id").asLong();
		long futureRequest = requestPersonal(leader, team, futureMember, WEEK, "판정 전").path("id").asLong();
		String before = snapshot(failMember, holdMember, futureMember);

		JsonNode preview = dataOf(asAdmin(PREVIEW_APPROVAL, idVariables(failRequest)))
				.path("previewPersonalExemptionApproval");
		JsonNode holdPreview = dataOf(asAdmin(PREVIEW_APPROVAL, idVariables(holdRequest)))
				.path("previewPersonalExemptionApproval");
		JsonNode futurePreview = dataOf(asAdmin(PREVIEW_APPROVAL, idVariables(futureRequest)))
				.path("previewPersonalExemptionApproval");

		assertThat(preview.path("retroactive").asBoolean()).isTrue();
		assertThat(preview.path("items")).hasSize(1);
		JsonNode impact = impactOf(preview, failMember);
		assertThat(impact.path("currentStatus").asText()).isEqualTo("FAIL");
		assertThat(impact.path("impact").path("warningCountBefore").asInt()).isEqualTo(3);
		assertThat(impact.path("impact").path("warningCountAfter").asInt()).isEqualTo(2);
		assertThat(impact.path("impact").path("leavesPenalty").asBoolean()).isTrue();
		// 보류 주와 판정 전 주는 바뀌는 판정이 없다
		assertThat(holdPreview.path("retroactive").asBoolean()).isFalse();
		assertThat(holdPreview.path("items")).isEmpty();
		assertThat(futurePreview.path("retroactive").asBoolean()).isFalse();
		assertThat(snapshot(failMember, holdMember, futureMember)).isEqualTo(before);
		assertThat(personalExemptionStatus(failRequest)).isEqualTo("PENDING");

		approve(failRequest);
		assertThat(state(failMember).warningCount()).isEqualTo(2);
		// 처리된 요청은 미리보기도 거부한다
		assertThat(asAdmin(PREVIEW_APPROVAL, idVariables(failRequest)).errorCode()).isEqualTo("ALREADY_HANDLED");
		assertThat(asAdmin(PREVIEW_APPROVAL, Map.of("id", "999999999999")).errorCode()).isEqualTo("NOT_FOUND");
	}

	@Test
	@DisplayName("승인된 개인 면제는 여러 팀에 속한 회원을 통째로 판정에서 빼고 같은 팀 다른 팀원은 평소대로 판정한다")
	void approvedExemptionRemovesThePersonFromEveryTeamWhenJudged() {
		long leader = activeMember();
		long team = newTeam("첫팀", leader);
		long otherLeader = activeMember();
		long otherTeam = newTeam("둘째팀", otherLeader);
		long member = activeMember();
		long mate = activeMember();
		join(team, member);
		join(otherTeam, member);
		join(team, mate);
		long request = requestPersonal(leader, team, member, WEEK, "시험").path("id").asLong();
		approve(request);

		Outcome exempt = memberJudgment.judge(member, WEEK);

		assertThat(exempt.judgment().getStatus().name()).isEqualTo("EXEMPT");
		assertThat(exempt.judgment().getSkipReason().name()).isEqualTo("PERSONAL_EXEMPTION");
		// 면제 주는 잔디를 조회하지 않는다
		assertThat(grassClient.calls()).isZero();
		assertThat(warningRowCount(member)).isZero();
		// 소속 스냅샷은 두 팀 모두 남는다
		assertThat(jdbc.queryForObject("select count(*) from judgment_team where judgment_id = ?", Integer.class,
				exempt.judgment().getId())).isEqualTo(2);

		// 같은 팀의 면제 아닌 팀원은 기록글이 없어 미달이다
		Outcome failed = memberJudgment.judge(mate, WEEK);
		assertThat(failed.judgment().getStatus().name()).isEqualTo("FAIL");
		assertThat(warningRowCount(mate)).isEqualTo(1);
	}

	@Test
	@DisplayName("면제 기간과 겹쳐도 두 면제는 독립으로 보존되고 기간을 지워도 개인 면제는 남는다 (E-44)")
	void overlappingExemptionPeriodAndPersonalExemptionStayIndependent() {
		LocalDate week = WEEK.plusWeeks(1);
		long leader = activeMember();
		long team = newTeam("겹침팀", leader);
		long exemptByPeriod = activeMember();
		long both = activeMember();
		join(team, exemptByPeriod);
		join(team, both);
		History history = history(exemptByPeriod, week, "E", team);
		long periodId = insertPeriod(week, "중간고사");

		// 이미 면제 기간인 주도 요청은 받고 겹침을 알린다
		JsonNode requested = requestPersonal(leader, team, exemptByPeriod, week, "겹치는 요청");
		assertThat(requested.path("overlapsExemptionPeriod").asBoolean()).isTrue();
		long requestId = requested.path("id").asLong();
		assertThat(mine(leader)).anyMatch(item -> item.path("id").asLong() == requestId
				&& item.path("overlapsExemptionPeriod").asBoolean());

		// 승인해도 요청은 그대로 보관되고 이미 면제인 판정의 사유는 건드리지 않는다
		JsonNode approved = approve(requestId);
		assertThat(approved.path("status").asText()).isEqualTo("APPROVED");
		assertThat(approved.path("overlapsExemptionPeriod").asBoolean()).isTrue();
		assertThat(judgmentStatus(history.judgment(0))).isEqualTo("EXEMPT");
		assertThat(skipReason(history.judgment(0))).isEqualTo("EXEMPTION_PERIOD");

		// 기간과 개인 면제가 모두 있는 주는 판정 때 제외된다
		long bothRequest = requestPersonal(leader, team, both, week, "둘 다").path("id").asLong();
		approve(bothRequest);
		assertThat(memberJudgment.judge(both, week).judgment().getStatus().name()).isEqualTo("EXEMPT");
		assertThat(grassClient.calls()).isZero();

		// 기간을 지워도 승인된 개인 면제는 남아 있다
		jdbc.update("delete from exemption_period where id = ?", periodId);
		assertThat(personalExemptionStatus(requestId)).isEqualTo("APPROVED");
		long afterDelete = activeMember();
		join(team, afterDelete);
		approve(requestPersonal(leader, team, afterDelete, week, "기간 없이").path("id").asLong());
		Outcome judged = memberJudgment.judge(afterDelete, week);
		assertThat(judged.judgment().getStatus().name()).isEqualTo("EXEMPT");
		assertThat(judged.judgment().getSkipReason().name()).isEqualTo("PERSONAL_EXEMPTION");
	}

	@Test
	@DisplayName("거절은 사유가 필수이고 거절해도 판정은 바뀌지 않으며 처리된 요청은 다시 처리할 수 없다 (E-48)")
	void rejectNeedsReasonAndKeepsJudgment() {
		long leader = activeMember();
		long team = newTeam("거절팀", leader);
		long mate = activeMember();
		join(team, mate);
		History history = history(mate, WEEK, "P", team);
		long request = requestPersonal(leader, team, mate, WEEK, "여행").path("id").asLong();

		assertThat(asAdmin(REJECT, Map.of("id", Long.toString(request), "reason", "   ")).errorCode())
				.isEqualTo("REJECT_REASON_REQUIRED");
		assertThat(asAdmin(REJECT, Map.of("id", Long.toString(request), "reason", "가".repeat(501))).errorCode())
				.isEqualTo("INVALID_INPUT");
		assertThat(personalExemptionStatus(request)).isEqualTo("PENDING");

		JsonNode rejected = dataOf(asAdmin(REJECT, Map.of("id", Long.toString(request), "reason", "  근거 부족  ")))
				.path("rejectPersonalExemption");

		assertThat(rejected.path("status").asText()).isEqualTo("REJECTED");
		assertThat(rejected.path("rejectReason").asText()).isEqualTo("근거 부족");
		assertThat(rejected.path("respondedAt").isNull()).isFalse();
		assertThat(judgmentStatus(history.judgment(0))).isEqualTo("PASS");
		// 처리된 요청은 승인·거절 모두 거부한다
		assertThat(asAdmin(APPROVE, idVariables(request)).errorCode()).isEqualTo("ALREADY_HANDLED");
		assertThat(asAdmin(REJECT, Map.of("id", Long.toString(request), "reason", "또")).errorCode())
				.isEqualTo("ALREADY_HANDLED");
		assertThat(personalExemptionStatus(request)).isEqualTo("REJECTED");
		// 팀장 목록에 거절 사유가 보인다
		assertThat(mine(leader)).anyMatch(item -> item.path("id").asLong() == request
				&& "REJECTED".equals(item.path("status").asText())
				&& "근거 부족".equals(item.path("rejectReason").asText()));

		long approvedRequest = requestPersonal(leader, team, mate, WEEK.plusWeeks(1), "다음 주").path("id").asLong();
		approve(approvedRequest);
		assertThat(asAdmin(REJECT, Map.of("id", Long.toString(approvedRequest), "reason", "늦은 거절")).errorCode())
				.isEqualTo("ALREADY_HANDLED");
		assertThat(personalExemptionStatus(approvedRequest)).isEqualTo("APPROVED");
		assertThat(asAdmin(APPROVE, Map.of("id", "999999999999")).errorCode()).isEqualTo("NOT_FOUND");
		assertThat(asAdmin(REJECT, Map.of("id", "999999999999", "reason", "없음")).errorCode()).isEqualTo("NOT_FOUND");
		assertThat(asAdmin(REJECT, Map.of("id", "abc", "reason", "문자")).errorCode()).isEqualTo("NOT_FOUND");
	}

	@Test
	@DisplayName("내 요청 목록은 내가 보낸 것만 최신순 20개씩 주고 관리자 목록은 상태별로 나눈다")
	void listsAreScopedAndPaged() {
		long leader = activeMember();
		long team = newTeam("목록팀", leader);
		long otherLeader = activeMember();
		long otherTeam = newTeam("남의팀", otherLeader);
		long mate = activeMember();
		long otherMate = activeMember();
		join(team, mate);
		join(otherTeam, otherMate);
		long pendingId = requestPersonal(leader, team, mate, WEEK, "대기").path("id").asLong();
		long approvedId = requestPersonal(leader, team, mate, WEEK.plusWeeks(1), "승인").path("id").asLong();
		long rejectedId = requestPersonal(leader, team, mate, WEEK.plusWeeks(2), "거절").path("id").asLong();
		long othersId = requestPersonal(otherLeader, otherTeam, otherMate, WEEK, "남의 요청").path("id").asLong();
		approve(approvedId);
		dataOf(asAdmin(REJECT, Map.of("id", Long.toString(rejectedId), "reason", "사유")));

		List<JsonNode> mine = mine(leader);
		assertThat(mine).extracting(item -> item.path("id").asLong()).containsExactly(rejectedId, approvedId, pendingId);
		assertThat(mine(otherLeader)).extracting(item -> item.path("id").asLong()).containsExactly(othersId);
		assertThat(mine(mate)).isEmpty();

		assertThat(adminRequests(null)).extracting(item -> item.path("id").asLong()).contains(pendingId, othersId)
				.doesNotContain(approvedId, rejectedId);
		assertThat(adminRequests("APPROVED")).extracting(item -> item.path("id").asLong()).contains(approvedId)
				.doesNotContain(pendingId, rejectedId);
		JsonNode rejectedItem = adminRequests("REJECTED").stream().filter(item -> item.path("id").asLong() == rejectedId)
				.findFirst().orElseThrow();
		assertThat(rejectedItem.path("rejectReason").asText()).isEqualTo("사유");
		assertThat(rejectedItem.path("requester").path("id").asLong()).isEqualTo(leader);
		assertThat(rejectedItem.path("member").path("id").asLong()).isEqualTo(mate);

		// 21건을 넣어 커서 20개씩 나뉘는지 본다
		long pager = activeMember();
		for (int i = 0; i < 21; i++) {
			data.personalExemptionPending(pager, leader, WEEK.plusWeeks(20 + i));
		}
		JsonNode first = dataOf(asMember(leader, MY_PERSONAL, Map.of())).path("myPersonalExemptionRequests");
		assertThat(first.path("items")).hasSize(20);
		assertThat(first.path("nextCursor").isNull()).isFalse();
		List<Long> firstIds = new ArrayList<>();
		first.path("items").forEach(item -> firstIds.add(item.path("id").asLong()));
		assertThat(firstIds).isSortedAccordingTo((a, b) -> Long.compare(b, a));
		JsonNode second = dataOf(asMember(leader, MY_PERSONAL, Map.of("after", first.path("nextCursor").asText())))
				.path("myPersonalExemptionRequests");
		assertThat(second.path("items")).hasSize(4);
		assertThat(second.path("nextCursor").isNull()).isTrue();
		assertThat(second.path("items").get(0).path("id").asLong()).isLessThan(firstIds.get(19));

		assertThat(asMember(leader, MY_PERSONAL, Map.of("after", "!!!")).errorCode()).isEqualTo("INVALID_INPUT");
		assertThat(asAdmin(ADMIN_PERSONAL, Map.of("after", "!!!")).errorCode()).isEqualTo("INVALID_INPUT");
	}

	@Test
	@DisplayName("승인·거절·관리자 목록·미리보기는 관리자만 부를 수 있고 일반 회원·승인 대기·비로그인은 아무것도 바꾸지 못한다")
	void onlyAdminHandlesRequests() {
		long leader = activeMember();
		long team = newTeam("권한팀", leader);
		long mate = activeMember();
		join(team, mate);
		History history = history(mate, WEEK, "F", team);
		long request = requestPersonal(leader, team, mate, WEEK, "병가").path("id").asLong();
		String before = snapshot(mate);

		long plain = activeMember();
		long pending = pendingMember();
		for (String caller : List.of("member", "leader", "pending", "anonymous")) {
			String expected = switch (caller) {
				case "member", "leader" -> "FORBIDDEN";
				case "pending" -> "ACCOUNT_PENDING";
				default -> "UNAUTHENTICATED";
			};
			assertThat(as(caller, plain, leader, pending, APPROVE, idVariables(request)).errorCode())
					.as(caller + " 승인").isEqualTo(expected);
			assertThat(as(caller, plain, leader, pending, REJECT, Map.of("id", Long.toString(request), "reason", "x"))
					.errorCode()).as(caller + " 거절").isEqualTo(expected);
			assertThat(as(caller, plain, leader, pending, ADMIN_PERSONAL, Map.of()).errorCode())
					.as(caller + " 관리자 목록").isEqualTo(expected);
			assertThat(as(caller, plain, leader, pending, PREVIEW_APPROVAL, idVariables(request)).errorCode())
					.as(caller + " 미리보기").isEqualTo(expected);
		}
		// 내 요청 목록은 로그인한 승인 회원만
		assertThat(graphQl.post(null, MY_PERSONAL, Map.of()).errorCode()).isEqualTo("UNAUTHENTICATED");
		assertThat(asMember(pending, MY_PERSONAL, Map.of()).errorCode()).isEqualTo("ACCOUNT_PENDING");

		assertThat(personalExemptionStatus(request)).isEqualTo("PENDING");
		assertThat(judgmentStatus(history.judgment(0))).isEqualTo("FAIL");
		assertThat(snapshot(mate)).isEqualTo(before);
	}

	@Test
	@DisplayName("같은 요청을 동시에 두 번 보내면 하나만 만들어지고, 같은 요청을 동시에 승인하면 한 번만 처리된다")
	void concurrentRequestsAndApprovalsAreSerialized() {
		long leader = activeMember();
		long team = newTeam("동시팀", leader);
		long mate = activeMember();
		join(team, mate);
		History history = history(mate, WEEK, "F", team);

		List<GraphQlResponse> requests = runTogether(() -> requestPersonalRaw(leader, team, mate, WEEK, "하나"),
				() -> requestPersonalRaw(leader, team, mate, WEEK, "둘"));
		assertThat(requests.stream().filter(r -> !r.hasErrors())).hasSize(1);
		assertThat(requests.stream().filter(GraphQlResponse::hasErrors).map(GraphQlResponse::errorCode))
				.containsExactly("EXEMPTION_REQUEST_DUPLICATE");
		assertThat(personalExemptionCount(mate)).isEqualTo(1);

		long request = jdbc.queryForObject("select id from personal_exemption where member_id = ?", Long.class, mate);
		List<GraphQlResponse> approvals = runTogether(() -> asAdmin(APPROVE, idVariables(request)),
				() -> asAdmin(APPROVE, idVariables(request)));
		assertThat(approvals.stream().filter(r -> !r.hasErrors())).hasSize(1);
		assertThat(approvals.stream().filter(GraphQlResponse::hasErrors).map(GraphQlResponse::errorCode))
				.containsExactly("ALREADY_HANDLED");
		assertThat(judgmentStatus(history.judgment(0))).isEqualTo("EXEMPT");

		// 승인과 거절이 겹치면 먼저 잡은 쪽만 반영된다
		long other = activeMember();
		join(team, other);
		long second = requestPersonal(leader, team, other, WEEK, "경합").path("id").asLong();
		List<GraphQlResponse> race = runTogether(() -> asAdmin(APPROVE, idVariables(second)),
				() -> asAdmin(REJECT, Map.of("id", Long.toString(second), "reason", "거절")));
		assertThat(race.stream().filter(r -> !r.hasErrors())).hasSize(1);
		assertThat(race.stream().filter(GraphQlResponse::hasErrors).map(GraphQlResponse::errorCode))
				.containsExactly("ALREADY_HANDLED");
		String finalStatus = personalExemptionStatus(second);
		assertThat(finalStatus).isIn("APPROVED", "REJECTED");
		// 반영된 쪽 응답과 DB 상태가 같다
		GraphQlResponse winner = race.stream().filter(r -> !r.hasErrors()).findFirst().orElseThrow();
		String winnerStatus = winner.data().has("approvePersonalExemption")
				? winner.data().path("approvePersonalExemption").path("status").asText()
				: winner.data().path("rejectPersonalExemption").path("status").asText();
		assertThat(finalStatus).isEqualTo(winnerStatus);
	}

	private GraphQlResponse as(String caller, long plain, long leader, long pending, String query,
			Map<String, Object> variables) {
		return switch (caller) {
			case "member" -> asMember(plain, query, variables);
			case "leader" -> asMember(leader, query, variables);
			case "pending" -> asMember(pending, query, variables);
			default -> graphQl.post(null, query, variables);
		};
	}

	private long insertPeriod(LocalDate week, String reason) {
		jdbc.update("insert into exemption_period (week_start, reason, created_by, created_at) values (?, ?, ?, ?)",
				week, reason, adminId, LocalDateTime.of(2047, 1, 1, 0, 0));
		return jdbc.queryForObject("select id from exemption_period where week_start = ?", Long.class, week);
	}

	private List<JsonNode> mine(long memberId) {
		List<JsonNode> items = new ArrayList<>();
		String cursor = null;
		do {
			Map<String, Object> variables = cursor == null ? Map.of() : Map.of("after", cursor);
			JsonNode page = dataOf(asMember(memberId, MY_PERSONAL, variables)).path("myPersonalExemptionRequests");
			page.path("items").forEach(items::add);
			cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText();
		} while (cursor != null);
		return items;
	}

	private List<JsonNode> adminRequests(String status) {
		List<JsonNode> items = new ArrayList<>();
		String cursor = null;
		do {
			Map<String, Object> variables = new java.util.HashMap<>();
			if (status != null) {
				variables.put("status", status);
			}
			if (cursor != null) {
				variables.put("after", cursor);
			}
			JsonNode page = dataOf(asAdmin(ADMIN_PERSONAL, variables)).path("personalExemptionRequests");
			page.path("items").forEach(items::add);
			cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText();
		} while (cursor != null);
		return items;
	}

}
