package com.jandilog.team;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.databind.JsonNode;
import com.jandilog.admin.domain.AdminActionLog;
import com.jandilog.admin.domain.AdminActionType;
import com.jandilog.admin.repository.AdminActionLogRepository;
import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.member.domain.MemberRole;
import com.jandilog.team.support.TeamGql;
import com.jandilog.team.support.TeamIntegrationTest;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;

// 시스템 관리자 팀 권한의 시나리오·경계 (2026-10-19 결정). 기능별 단일 확인은 TeamAdminAccessIntegrationTest가 맡고,
// 여기서는 같은 토큰의 권한 하락·복원, 소속 여부에 따른 응답 값, admin_action_log 기록의 경계, 동시 요청을 본다
class TeamAdminScenarioIntegrationTest extends TeamIntegrationTest {

	private static final LocalDate WEEK_1 = LocalDate.of(2026, 9, 7);
	private static final String OK = "OK";
	private static final int ROUNDS = 5;

	@Autowired
	private AdminActionLogRepository actionLogRepository;

	private long leader;
	private long mate;
	private long otherLeader;
	private long otherMate;
	private long outsider;
	private long admin;
	private long secondAdmin;
	private long invitee;
	private long guest;
	private CreatedTeam teamA;
	private CreatedTeam teamB;
	private long invitationId;

	@BeforeEach
	void setUpTeams() {
		leader = member();
		mate = member();
		otherLeader = member();
		otherMate = member();
		outsider = member();
		admin = adminMember();
		secondAdmin = adminMember();
		invitee = member();
		guest = member();
		teamA = newTeam(leader, "시나리오 팀 A", null, true);
		teamB = newTeam(otherLeader, "시나리오 팀 B", null, false);
		joinService.joinByCode(mate, teamA.inviteCode());
		joinService.joinByCode(otherMate, teamB.inviteCode());
		invitationId = invitationService.invite(leader, teamA.id(), loginOf(invitee)).id();
	}

	// ----- 호출 도우미 -----

	// 관리자가 쓸 수 있는 팀 기능 열 개
	static Stream<String> adminOperations() {
		return Stream.of("team", "teamMembers", "teamInviteCode", "sentTeamInvitations", "updateTeam", "inviteToTeam",
				"cancelTeamInvitation", "kickTeamMember", "transferTeamLeadership", "deleteTeam");
	}

	private record Call(String query, Map<String, Object> variables) {
	}

	private Call call(String operation, long teamId, long target) {
		String id = Long.toString(teamId);
		return switch (operation) {
			case "team" -> new Call(TeamGql.TEAM, Map.of("id", id));
			case "teamMembers" -> new Call(TeamGql.TEAM_MEMBERS, Map.of("id", id));
			case "teamInviteCode" -> new Call(TeamGql.INVITE_CODE, Map.of("id", id));
			case "sentTeamInvitations" -> new Call(TeamGql.SENT, Map.of("id", id));
			case "updateTeam" -> new Call(TeamGql.UPDATE, Map.of("id", id, "input", Map.of("isPublic", false)));
			case "inviteToTeam" -> new Call(TeamGql.INVITE, Map.of("id", id, "login", loginOf(guest)));
			case "cancelTeamInvitation" -> new Call(TeamGql.CANCEL, Map.of("id", Long.toString(invitationId)));
			case "kickTeamMember" -> new Call(TeamGql.KICK, Map.of("teamId", id, "memberId", Long.toString(target)));
			case "transferTeamLeadership" ->
					new Call(TeamGql.TRANSFER, Map.of("teamId", id, "newLeaderId", Long.toString(target)));
			case "deleteTeam" -> new Call(TeamGql.DELETE, Map.of("teamId", id));
			default -> throw new IllegalArgumentException(operation);
		};
	}

	private GraphQlResponse run(long caller, String operation, long teamId, long target) {
		Call call = call(operation, teamId, target);
		return gql(caller, call.query(), call.variables());
	}

	// 이미 받아 둔 토큰으로 호출한다 (토큰은 그대로이고 DB의 회원 상태만 바뀌는 상황)
	private GraphQlResponse runWithToken(String token, String operation, long teamId, long target) {
		Call call = call(operation, teamId, target);
		return graphQl.post(token, call.query(), call.variables());
	}

	// 오류 응답을 오류 코드 예외로 바꿔 경합 결과에 담는다
	private void runOrThrow(long caller, String operation, long teamId, long target) {
		GraphQlResponse response = run(caller, operation, teamId, target);
		if (response.hasErrors()) {
			throw new ApiException(ErrorCode.valueOf(response.errorCode()));
		}
	}

	private List<Map<String, Object>> adminLogs(long adminId) {
		return jdbc.queryForList("select * from admin_action_log where admin_id = ? order by id", adminId);
	}

	private long membershipId(long teamId, long memberId) {
		return ((Number) membershipRows(teamId, memberId).get(0).get("id")).longValue();
	}

	// 그 팀·회원의 소속 행(이력 전부)을 대상으로 한 KICK_MEMBER 기록
	private List<Map<String, Object>> kickLogs(long teamId, long memberId) {
		List<String> ids = membershipRows(teamId, memberId).stream().map(r -> "'" + r.get("id") + "'").toList();
		if (ids.isEmpty()) {
			return List.of();
		}
		return jdbc.queryForList("select * from admin_action_log where action = 'KICK_MEMBER' "
				+ "and target_type = 'TEAM_MEMBER' and target_id in (" + String.join(",", ids) + ") order by id");
	}

	private List<Map<String, Object>> deleteTeamLogs(long teamId) {
		return jdbc.queryForList("select * from admin_action_log where action = 'DELETE_TEAM' "
				+ "and target_type = 'TEAM' and target_id = ? order by id", Long.toString(teamId));
	}

	// 팀 A·B가 처음 상태 그대로인가 (거부된 호출은 아무것도 바꾸지 못한다)
	private void assertTeamsUntouched() {
		for (CreatedTeam team : List.of(teamA, teamB)) {
			assertThat(teamRow(team.id()).get("deleted_at")).isNull();
			assertThat(activeMemberCount(team.id())).isEqualTo(2);
		}
		assertThat(((Number) teamRow(teamA.id()).get("leader_id")).longValue()).isEqualTo(leader);
		assertThat(teamRow(teamA.id()).get("is_public")).isIn(true, 1);
		assertThat(redis.hasKey(banKey(teamA.id(), mate))).isFalse();
		assertThat(invitationRows(teamA.id(), invitee)).extracting(r -> r.get("status")).containsExactly("PENDING");
		assertThat(invitationRows(teamA.id(), guest)).isEmpty();
	}

	// ----- 같은 토큰으로 권한이 바뀌는 순간 -----

	@ParameterizedTest
	@MethodSource("adminOperations")
	void 같은_토큰으로_관리자에서_일반_회원으로_내려가면_바로_거부되고_다시_올라가면_바로_통과한다(String operation) {
		String token = bearerFor(admin);
		assertGraphQlOk(runWithToken(token, "teamInviteCode", teamB.id(), otherMate));

		members.setRole(admin, MemberRole.MEMBER);

		// 하락 직후의 같은 토큰: 호출하려던 기능도, 직전까지 되던 다른 팀 조회도 막힌다
		assertGraphQlError(runWithToken(token, operation, teamA.id(), mate), ErrorCode.FORBIDDEN);
		assertGraphQlError(runWithToken(token, "teamInviteCode", teamB.id(), otherMate), ErrorCode.FORBIDDEN);
		assertTeamsUntouched();
		assertThat(adminLogs(admin)).isEmpty();

		members.setRole(admin, MemberRole.ADMIN);

		assertGraphQlOk(runWithToken(token, operation, teamA.id(), mate));
	}

	@Test
	void 하락한_관리자가_자기_팀이_아닌_팀에_할_수_있는_일은_일반_회원과_같다() {
		String token = bearerFor(admin);
		members.setRole(admin, MemberRole.MEMBER);

		// 팀 참가는 일반 회원처럼 가능하고, 참가한 뒤에도 팀장 전용은 막힌다
		assertGraphQlOk(graphQl.post(token, TeamGql.JOIN, Map.of("code", teamA.inviteCode())));
		assertGraphQlOk(runWithToken(token, "team", teamA.id(), mate));
		assertGraphQlOk(runWithToken(token, "teamMembers", teamA.id(), mate));
		assertGraphQlError(runWithToken(token, "teamInviteCode", teamA.id(), mate), ErrorCode.FORBIDDEN);
		assertGraphQlError(runWithToken(token, "kickTeamMember", teamA.id(), mate), ErrorCode.FORBIDDEN);
		assertGraphQlError(runWithToken(token, "team", teamB.id(), mate), ErrorCode.FORBIDDEN);
		assertThat(isActiveMember(teamA.id(), mate)).isTrue();
		assertThat(adminLogs(admin)).isEmpty();
	}

	// ----- 응답 직렬화: 소속 여부에 따른 joinedAt -----

	private JsonNode teamField(GraphQlResponse response) {
		assertGraphQlOk(response);
		assertThat(response.rawBody()).doesNotContain("\"errors\"");
		return response.data().path("team");
	}

	private JsonNode myTeams(long memberId) {
		GraphQlResponse response = gql(memberId, TeamGql.MY_TEAMS);
		assertGraphQlOk(response);
		return response.data().path("myTeams");
	}

	private void assertJoinedAtNull(JsonNode team) {
		assertThat(team.has("joinedAt")).as("joinedAt 필드가 응답에 있다").isTrue();
		assertThat(team.get("joinedAt").isNull()).isTrue();
	}

	@Test
	void 한_팀에만_속한_관리자는_그_팀에서는_참가_시각이_있고_다른_팀에서는_null이며_myTeams에는_참가한_팀만_있다() {
		joinService.joinByCode(admin, teamA.inviteCode());

		JsonNode mine = myTeams(admin);
		JsonNode inA = teamField(run(admin, "team", teamA.id(), mate));
		JsonNode inB = teamField(run(admin, "team", teamB.id(), mate));

		assertThat(mine).hasSize(1);
		assertThat(mine.get(0).path("id").asText()).isEqualTo(Long.toString(teamA.id()));
		assertThat(mine.get(0).path("joinedAt").asText()).isNotBlank();
		assertThat(inA.path("joinedAt").asText()).isEqualTo(mine.get(0).path("joinedAt").asText());
		assertThat(inA.path("memberCount").asInt()).isEqualTo(3);
		// 소속이 아닌 팀: 값은 null이고 나머지 필드는 실제 값이다
		assertJoinedAtNull(inB);
		assertThat(inB.path("id").asText()).isEqualTo(Long.toString(teamB.id()));
		assertThat(inB.path("name").asText()).isEqualTo("시나리오 팀 B");
		assertThat(inB.path("isPublic").asBoolean()).isFalse();
		assertThat(inB.path("isLeader").asBoolean()).isFalse();
		assertThat(inB.path("memberCount").asInt()).isEqualTo(2);
		assertThat(inB.path("createdAt").asText()).isNotBlank();
		assertThat(inB.path("leader").path("id").asText()).isEqualTo(Long.toString(otherLeader));
	}

	@Test
	void 한_요청에서_소속_팀과_비소속_팀을_함께_조회해도_오류_없이_값과_null이_각각_온다() {
		joinService.joinByCode(admin, teamA.inviteCode());
		String query = "query($a: ID!, $b: ID!) { mine: team(id: $a) { joinedAt isLeader } other: team(id: $b) "
				+ "{ joinedAt isLeader } myTeams { id joinedAt } }";

		GraphQlResponse response = gql(admin, query, Map.of("a", Long.toString(teamA.id()), "b", Long.toString(teamB.id())));

		assertGraphQlOk(response);
		assertThat(response.rawBody()).doesNotContain("\"errors\"");
		assertThat(response.data().path("mine").path("joinedAt").asText()).isNotBlank();
		assertJoinedAtNull(response.data().path("other"));
		assertThat(response.data().path("myTeams")).hasSize(1);
		assertThat(response.data().path("myTeams").get(0).path("joinedAt").asText())
				.isEqualTo(response.data().path("mine").path("joinedAt").asText());
	}

	@Test
	void 관리자가_팀에_들어오고_나가고_추방당하는_대로_참가_시각이_값과_null을_오간다() {
		assertJoinedAtNull(teamField(run(admin, "team", teamA.id(), mate)));
		assertThat(myTeams(admin)).isEmpty();

		joinService.joinByCode(admin, teamA.inviteCode());
		assertThat(teamField(run(admin, "team", teamA.id(), mate)).path("joinedAt").asText()).isNotBlank();
		assertThat(myTeams(admin)).hasSize(1);

		leaveService.leave(admin, teamA.id());
		assertJoinedAtNull(teamField(run(admin, "team", teamA.id(), mate)));
		assertThat(myTeams(admin)).isEmpty();

		joinService.joinByCode(admin, teamA.inviteCode());
		leaveService.kick(leader, teamA.id(), admin);
		// 추방돼도 관리자 권한은 그대로라 조회와 추방은 계속 되고, 초대코드 재참가만 막힌다
		assertJoinedAtNull(teamField(run(admin, "team", teamA.id(), mate)));
		assertThat(myTeams(admin)).isEmpty();
		assertGraphQlOk(run(admin, "teamMembers", teamA.id(), mate));
		assertGraphQlOk(run(admin, "kickTeamMember", teamA.id(), mate));
		assertGraphQlError(gql(admin, TeamGql.JOIN, Map.of("code", teamA.inviteCode())), ErrorCode.TEAM_JOIN_BLOCKED);
	}

	@Test
	void 팀원인_관리자는_자기_자신을_추방할_수_없고_소속과_차단_키와_기록이_그대로다() {
		joinService.joinByCode(admin, teamA.inviteCode());

		assertGraphQlError(run(admin, "kickTeamMember", teamA.id(), admin), ErrorCode.INVALID_INPUT);

		assertThat(isActiveMember(teamA.id(), admin)).isTrue();
		assertThat(redis.hasKey(banKey(teamA.id(), admin))).isFalse();
		assertThat(adminLogs(admin)).isEmpty();
	}

	@Test
	void 관리자가_팀장이면_isLeader와_참가_시각이_있고_위임해도_참가_시각은_그대로다() {
		CreatedTeam own = newTeam(admin, "관리자가 만든 팀", null, true);
		long ownMate = joinedMember(own);

		JsonNode before = teamField(run(admin, "team", own.id(), ownMate));
		assertThat(before.path("isLeader").asBoolean()).isTrue();
		String joinedAt = before.path("joinedAt").asText();
		assertThat(joinedAt).isNotBlank();
		assertThat(myTeams(admin)).hasSize(1);

		assertGraphQlOk(run(admin, "transferTeamLeadership", own.id(), ownMate));

		JsonNode after = teamField(run(admin, "team", own.id(), ownMate));
		assertThat(after.path("isLeader").asBoolean()).isFalse();
		assertThat(after.path("joinedAt").asText()).isEqualTo(joinedAt);
		assertThat(myTeams(admin).get(0).path("joinedAt").asText()).isEqualTo(joinedAt);
	}

	// ----- admin_action_log 기록의 경계 -----

	@Test
	void 관리자가_여러_팀원을_추방하면_소속_행마다_기록이_하나씩_남고_팀장이_한_추방은_남지_않는다() {
		long mate2 = joinedMember(teamA);
		long mate3 = joinedMember(teamA);

		assertGraphQlOk(run(admin, "kickTeamMember", teamA.id(), mate));
		assertGraphQlOk(run(admin, "kickTeamMember", teamA.id(), mate2));
		assertGraphQlOk(run(leader, "kickTeamMember", teamA.id(), mate3));

		List<Map<String, Object>> rows = adminLogs(admin);
		assertThat(rows).extracting(r -> r.get("action")).containsExactly("KICK_MEMBER", "KICK_MEMBER");
		assertThat(rows).extracting(r -> r.get("target_id")).containsExactly(
				Long.toString(membershipId(teamA.id(), mate)), Long.toString(membershipId(teamA.id(), mate2)));
		assertThat(kickLogs(teamA.id(), mate3)).isEmpty();
		assertThat(adminLogs(leader)).isEmpty();
	}

	@Test
	void 관리자가_추방한_사람이_아이디_초대로_돌아왔다가_다시_추방되면_새_소속_행을_대상으로_기록이_또_남는다() {
		assertGraphQlOk(run(admin, "kickTeamMember", teamA.id(), mate));
		inviteAndAccept(leader, teamA, mate);
		assertGraphQlOk(run(admin, "kickTeamMember", teamA.id(), mate));

		// 참가할 때마다 소속 행이 새로 생기고, 추방 기록은 그 행을 각각 가리킨다
		List<Map<String, Object>> memberships = membershipRows(teamA.id(), mate);
		assertThat(memberships).hasSize(2);
		assertThat(memberships).extracting(r -> r.get("leave_type")).containsExactly("KICKED", "KICKED");
		assertThat(adminLogs(admin)).extracting(r -> r.get("action")).containsExactly("KICK_MEMBER", "KICK_MEMBER");
		assertThat(adminLogs(admin)).extracting(r -> r.get("target_id")).containsExactly(
				memberships.get(0).get("id").toString(), memberships.get(1).get("id").toString());
	}

	@Test
	void 이미_나갔거나_추방됐거나_다른_팀의_팀원이면_관리자의_추방은_대상_없음이고_기록과_차단_키가_남지_않는다() {
		long selfLeft = joinedMember(teamA);
		leaveService.leave(selfLeft, teamA.id());
		long alreadyKicked = joinedMember(teamA);
		leaveService.kick(leader, teamA.id(), alreadyKicked);

		assertGraphQlError(run(admin, "kickTeamMember", teamA.id(), selfLeft), ErrorCode.TEAM_MEMBER_NOT_FOUND);
		assertGraphQlError(run(admin, "kickTeamMember", teamA.id(), alreadyKicked), ErrorCode.TEAM_MEMBER_NOT_FOUND);
		// 팀 B 소속인 사람을 팀 A에서 추방하려는 경우
		assertGraphQlError(run(admin, "kickTeamMember", teamA.id(), otherMate), ErrorCode.TEAM_MEMBER_NOT_FOUND);
		assertGraphQlError(run(admin, "kickTeamMember", teamB.id(), mate), ErrorCode.TEAM_MEMBER_NOT_FOUND);

		assertThat(adminLogs(admin)).isEmpty();
		assertThat(redis.hasKey(banKey(teamA.id(), selfLeft))).isFalse();
		assertThat(isActiveMember(teamB.id(), otherMate)).isTrue();
		assertThat(isActiveMember(teamA.id(), mate)).isTrue();
		assertThat(membershipRows(teamA.id(), selfLeft).get(0).get("leave_type")).isEqualTo("SELF");
	}

	@Test
	void 관리자가_팀원이_여럿인_팀을_삭제하면_DELETE_TEAM_한_건만_남고_팀원별_추방_기록은_생기지_않는다() {
		long mate2 = joinedMember(teamA);

		assertGraphQlOk(run(admin, "deleteTeam", teamA.id(), mate));

		assertThat(adminLogs(admin)).extracting(r -> r.get("action")).containsExactly("DELETE_TEAM");
		assertThat(deleteTeamLogs(teamA.id())).hasSize(1);
		for (long who : List.of(leader, mate, mate2)) {
			assertThat(membershipRows(teamA.id(), who).get(0).get("leave_type")).isEqualTo("TEAM_DELETED");
			assertThat(kickLogs(teamA.id(), who)).isEmpty();
		}
		assertThat(deleteTeamLogs(teamB.id())).isEmpty();
	}

	@Test
	void 관리자_둘이_각자_한_일은_각자의_admin_id로_기록된다() {
		assertGraphQlOk(run(admin, "kickTeamMember", teamA.id(), mate));
		assertGraphQlOk(run(secondAdmin, "deleteTeam", teamB.id(), otherMate));
		assertGraphQlOk(run(admin, "deleteTeam", teamA.id(), mate));

		assertThat(adminLogs(admin)).extracting(r -> r.get("action")).containsExactly("KICK_MEMBER", "DELETE_TEAM");
		assertThat(adminLogs(secondAdmin)).extracting(r -> r.get("action")).containsExactly("DELETE_TEAM");
		assertThat(adminLogs(secondAdmin).get(0).get("target_id")).isEqualTo(Long.toString(teamB.id()));
		// 기록 시각은 행 순서를 거스르지 않는다
		List<Map<String, Object>> rows = adminLogs(admin);
		assertThat(time(rows.get(0).get("created_at"))).isBeforeOrEqualTo(time(rows.get(1).get("created_at")));
	}

	@Test
	void 팀원으로_속한_관리자도_팀장이_아니면_관리자_권한으로_추방할_수_있고_효과는_같다() {
		// 팀원 관리자의 기록 여부는 명세가 팀장 직접 사용·관리자 본인 팀의 범위를 가르지 않아 단언하지 않는다
		joinService.joinByCode(admin, teamA.inviteCode());
		long warning = warnings.warning(mate, WEEK_1, teamA.id());

		assertGraphQlOk(run(admin, "kickTeamMember", teamA.id(), mate));

		assertThat(isActiveMember(teamA.id(), mate)).isFalse();
		assertThat(redis.hasKey(banKey(teamA.id(), mate))).isTrue();
		assertThat(warnings.warning(warning).alive()).isFalse();
		assertThat(isActiveMember(teamA.id(), admin)).isTrue();
	}

	@Test
	void AdminActionType의_모든_값이_JPA로_저장되고_같은_값으로_읽힌다() {
		LocalDateTime now = LocalDateTime.of(2026, 10, 19, 9, 0);
		List<Long> ids = new ArrayList<>();
		for (AdminActionType type : AdminActionType.values()) {
			ids.add(actionLogRepository.saveAndFlush(AdminActionLog.of(admin, type, "TEST", "1", null, now)).getId());
		}

		List<AdminActionLog> read = actionLogRepository.findAllById(ids);

		assertThat(read).extracting(AdminActionLog::getAction).containsExactlyInAnyOrder(AdminActionType.values());
		assertThat(adminLogs(admin)).extracting(r -> r.get("action"))
				.containsExactlyElementsOf(Arrays.stream(AdminActionType.values()).map(Enum::name).toList());
	}

	// ----- 동시 요청 -----

	@FunctionalInterface
	private interface Task {

		void run() throws Exception;

	}

	// 모든 작업을 같은 순간에 출발시켜 입력 순서대로 결과를 모은다. 정상은 OK, 업무 오류는 오류 코드 이름, 그 밖은 UNEXPECTED:...
	private List<String> race(List<Task> tasks) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
		CountDownLatch ready = new CountDownLatch(tasks.size());
		CountDownLatch go = new CountDownLatch(1);
		try {
			List<Future<String>> futures = new ArrayList<>();
			for (Task task : tasks) {
				futures.add(pool.submit(() -> {
					ready.countDown();
					go.await(10, TimeUnit.SECONDS);
					try {
						task.run();
						return OK;
					} catch (ApiException e) {
						return e.getErrorCode().name();
					} catch (Throwable e) {
						return "UNEXPECTED:" + e.getClass().getName() + ":" + e.getMessage();
					}
				}));
			}
			assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
			go.countDown();
			List<String> results = new ArrayList<>();
			for (Future<String> future : futures) {
				results.add(future.get(60, TimeUnit.SECONDS));
			}
			return results;
		} finally {
			pool.shutdownNow();
		}
	}

	private static void assertNoUnexpected(List<String> results) {
		assertThat(results).noneMatch(r -> r.startsWith("UNEXPECTED"));
	}

	@Test
	void 팀장과_관리자가_같은_팀원을_동시에_추방하면_한_번만_처리되고_기록은_관리자가_이겼을_때만_한_건이다() throws Exception {
		for (int round = 0; round < ROUNDS; round++) {
			long target = joinedMember(teamA);
			long warningId = warnings.warning(target, WEEK_1.plusWeeks(round), teamA.id());

			List<String> results = race(List.of(() -> leaveService.kick(leader, teamA.id(), target),
					() -> leaveService.kick(admin, teamA.id(), target)));

			assertNoUnexpected(results);
			assertThat(results).containsExactlyInAnyOrder(OK, ErrorCode.TEAM_MEMBER_NOT_FOUND.name());
			assertThat(membershipRows(teamA.id(), target)).hasSize(1);
			assertThat(membershipRows(teamA.id(), target).get(0).get("leave_type")).isEqualTo("KICKED");
			assertThat(redis.hasKey(banKey(teamA.id(), target))).isTrue();
			assertThat(warnings.warning(warningId).alive()).isFalse();
			boolean adminWon = OK.equals(results.get(1));
			assertThat(kickLogs(teamA.id(), target)).hasSize(adminWon ? 1 : 0);
		}
		assertThat(adminLogs(leader)).isEmpty();
	}

	@Test
	void 관리자_둘이_같은_팀원을_동시에_추방하면_기록은_한_건이고_이긴_관리자의_것이다() throws Exception {
		for (int round = 0; round < ROUNDS; round++) {
			long target = joinedMember(teamA);

			List<String> results = race(List.of(() -> leaveService.kick(admin, teamA.id(), target),
					() -> leaveService.kick(secondAdmin, teamA.id(), target)));

			assertNoUnexpected(results);
			assertThat(results).containsExactlyInAnyOrder(OK, ErrorCode.TEAM_MEMBER_NOT_FOUND.name());
			List<Map<String, Object>> rows = kickLogs(teamA.id(), target);
			assertThat(rows).hasSize(1);
			long winner = OK.equals(results.get(0)) ? admin : secondAdmin;
			assertThat(((Number) rows.get(0).get("admin_id")).longValue()).isEqualTo(winner);
			assertThat(rows.get(0).get("target_id")).isEqualTo(Long.toString(membershipId(teamA.id(), target)));
		}
	}

	@Test
	void 관리자_둘이_같은_팀을_동시에_삭제하면_한_번만_삭제되고_기록은_한_건이다() throws Exception {
		for (int round = 0; round < ROUNDS; round++) {
			CreatedTeam team = newTeam(member());

			List<String> results = race(List.of(() -> leaveService.delete(admin, team.id()),
					() -> leaveService.delete(secondAdmin, team.id())));

			assertNoUnexpected(results);
			assertThat(results).containsExactlyInAnyOrder(OK, ErrorCode.NOT_FOUND.name());
			assertThat(teamRow(team.id()).get("deleted_at")).isNotNull();
			List<Map<String, Object>> rows = deleteTeamLogs(team.id());
			assertThat(rows).hasSize(1);
			long winner = OK.equals(results.get(0)) ? admin : secondAdmin;
			assertThat(((Number) rows.get(0).get("admin_id")).longValue()).isEqualTo(winner);
		}
	}

	@Test
	void 팀장과_관리자가_같은_팀을_동시에_삭제하면_한_번만_삭제되고_기록은_관리자가_이겼을_때만_한_건이다() throws Exception {
		for (int round = 0; round < ROUNDS; round++) {
			long roundLeader = member();
			CreatedTeam team = newTeam(roundLeader);
			joinedMember(team);

			List<String> results = race(List.of(() -> leaveService.delete(roundLeader, team.id()),
					() -> leaveService.delete(admin, team.id())));

			assertNoUnexpected(results);
			assertThat(results).containsExactlyInAnyOrder(OK, ErrorCode.NOT_FOUND.name());
			assertThat(activeMemberCount(team.id())).isZero();
			assertThat(deleteTeamLogs(team.id())).hasSize(OK.equals(results.get(1)) ? 1 : 0);
		}
	}

	@Test
	void 관리자의_추방과_팀장의_위임이_후임을_두고_겹치면_하나만_성립하고_기록은_추방이_이겼을_때만_남는다() throws Exception {
		for (int round = 0; round < ROUNDS; round++) {
			long roundLeader = member();
			CreatedTeam team = newTeam(roundLeader);
			long successor = joinedMember(team);

			List<String> results = race(List.of(() -> leaveService.transferLeadership(roundLeader, team.id(), successor),
					() -> leaveService.kick(admin, team.id(), successor)));

			assertNoUnexpected(results);
			assertThat(results).filteredOn(OK::equals).hasSize(1);
			long leaderId = ((Number) teamRow(team.id()).get("leader_id")).longValue();
			if (OK.equals(results.get(0))) {
				// 위임이 먼저: 후임이 팀장이라 관리자도 추방할 수 없다
				assertThat(leaderId).isEqualTo(successor);
				assertThat(results.get(1)).isEqualTo(ErrorCode.INVALID_INPUT.name());
				assertThat(isActiveMember(team.id(), successor)).isTrue();
				assertThat(kickLogs(team.id(), successor)).isEmpty();
			} else {
				// 추방이 먼저: 위임은 대상이 팀원이 아니라 실패한다
				assertThat(leaderId).isEqualTo(roundLeader);
				assertThat(results.get(0)).isEqualTo(ErrorCode.TEAM_MEMBER_NOT_FOUND.name());
				assertThat(isActiveMember(team.id(), successor)).isFalse();
				assertThat(kickLogs(team.id(), successor)).hasSize(1);
			}
		}
	}

	@Test
	void 관리자_추방과_관리자_강등이_겹쳐도_추방과_기록은_함께_성립하거나_함께_없다() throws Exception {
		try {
			for (int round = 0; round < ROUNDS; round++) {
				long target = joinedMember(teamA);
				members.setRole(admin, MemberRole.ADMIN);

				List<String> results = race(List.of(() -> leaveService.kick(admin, teamA.id(), target),
						() -> members.setRole(admin, MemberRole.MEMBER)));

				assertNoUnexpected(results);
				assertThat(results.get(1)).isEqualTo(OK);
				boolean kicked = OK.equals(results.get(0));
				if (!kicked) {
					assertThat(results.get(0)).isEqualTo(ErrorCode.FORBIDDEN.name());
				}
				assertThat(isActiveMember(teamA.id(), target)).isEqualTo(!kicked);
				assertThat(redis.hasKey(banKey(teamA.id(), target))).isEqualTo(kicked);
				assertThat(kickLogs(teamA.id(), target)).hasSize(kicked ? 1 : 0);
			}
		} finally {
			members.setRole(admin, MemberRole.ADMIN);
		}
	}

	@Test
	void HTTP로_팀장과_관리자가_같은_팀원을_동시에_추방해도_오류_코드가_예측_가능하고_한_번만_처리된다() throws Exception {
		for (int round = 0; round < ROUNDS; round++) {
			long target = joinedMember(teamA);

			List<String> results = race(List.of(() -> runOrThrow(leader, "kickTeamMember", teamA.id(), target),
					() -> runOrThrow(admin, "kickTeamMember", teamA.id(), target)));

			assertNoUnexpected(results);
			assertThat(results).containsExactlyInAnyOrder(OK, ErrorCode.TEAM_MEMBER_NOT_FOUND.name());
			assertThat(membershipRows(teamA.id(), target)).hasSize(1);
			assertThat(kickLogs(teamA.id(), target)).hasSize(OK.equals(results.get(1)) ? 1 : 0);
		}
	}

}
