package com.jandilog.team;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.jandilog.common.exception.ErrorCode;
import com.jandilog.member.domain.MemberStatus;
import com.jandilog.team.dto.SentInvitationResponse;
import com.jandilog.team.support.TeamGql;
import com.jandilog.team.support.TeamIntegrationTest;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;

// 팀 접근 제어 (기능명세서 2장, 화면설계서 TM-05 접근 권한·TM-06, E-01·E-02·E-09·E-53, EX-TM05-01).
// 승인 상태별 호출 가능 여부, 비소속·관리자의 팀 정보 접근 차단, 팀장 전용, 없는/삭제된 팀을 실제 HTTP /graphql로 확인한다
class TeamAccessControlIntegrationTest extends TeamIntegrationTest {

	private enum Kind {
		ANONYMOUS, PENDING, REJECTED
	}

	// 모든 팀 루트 필드 (team.graphqls의 Query 6개 + Mutation 11개)
	private static final List<String> ALL_OPERATIONS = List.of("myTeams", "receivedTeamInvitations", "team", "teamMembers",
			"teamInviteCode", "sentTeamInvitations", "createTeam", "updateTeam", "joinTeamByCode", "inviteToTeam",
			"cancelTeamInvitation", "acceptTeamInvitation", "declineTeamInvitation", "kickTeamMember", "leaveTeam",
			"transferTeamLeadership", "deleteTeam");

	private record Call(String query, Map<String, Object> variables) {
	}

	private long leader;
	private long teammate;
	private long outsider;
	private long admin;
	private long invitee;
	private CreatedTeam team;
	private CreatedTeam privateTeam;
	private long invitationId;

	@BeforeEach
	void setUpTeam() {
		leader = member();
		teammate = member();
		outsider = member();
		admin = adminMember();
		invitee = member();
		team = newTeam(leader, "접근 팀", null, true);
		privateTeam = newTeam(leader, "비공개 접근 팀", null, false);
		joinService.joinByCode(teammate, team.inviteCode());
		SentInvitationResponse sent = invitationService.invite(leader, team.id(), loginOf(invitee));
		invitationId = sent.id();
	}

	private Call call(String operation, long teamId) {
		String id = Long.toString(teamId);
		return switch (operation) {
			case "myTeams" -> new Call(TeamGql.MY_TEAMS, Map.of());
			case "receivedTeamInvitations" -> new Call(TeamGql.RECEIVED, Map.of());
			case "team" -> new Call(TeamGql.TEAM, Map.of("id", id));
			case "teamMembers" -> new Call(TeamGql.TEAM_MEMBERS, Map.of("id", id));
			case "teamInviteCode" -> new Call(TeamGql.INVITE_CODE, Map.of("id", id));
			case "sentTeamInvitations" -> new Call(TeamGql.SENT, Map.of("id", id));
			case "createTeam" -> new Call(TeamGql.CREATE, Map.of("input", Map.of("name", "접근 거부 확인")));
			case "updateTeam" -> new Call(TeamGql.UPDATE, Map.of("id", id, "input", Map.of("name", "바뀌면 안 되는 이름")));
			case "joinTeamByCode" -> new Call(TeamGql.JOIN, Map.of("code", team.inviteCode()));
			case "inviteToTeam" -> new Call(TeamGql.INVITE, Map.of("id", id, "login", loginOf(invitee)));
			case "cancelTeamInvitation" -> new Call(TeamGql.CANCEL, Map.of("id", Long.toString(invitationId)));
			case "acceptTeamInvitation" -> new Call(TeamGql.ACCEPT, Map.of("id", Long.toString(invitationId)));
			case "declineTeamInvitation" -> new Call(TeamGql.DECLINE, Map.of("id", Long.toString(invitationId)));
			case "kickTeamMember" -> new Call(TeamGql.KICK, Map.of("teamId", id, "memberId", Long.toString(teammate)));
			case "leaveTeam" -> new Call(TeamGql.LEAVE, Map.of("teamId", id));
			case "transferTeamLeadership" ->
					new Call(TeamGql.TRANSFER, Map.of("teamId", id, "newLeaderId", Long.toString(teammate)));
			case "deleteTeam" -> new Call(TeamGql.DELETE, Map.of("teamId", id));
			default -> throw new IllegalArgumentException(operation);
		};
	}

	private GraphQlResponse run(Long memberId, String operation) {
		Call call = call(operation, team.id());
		return memberId == null ? graphQl.post(null, call.query(), call.variables())
				: gql(memberId, call.query(), call.variables());
	}

	// ----- 승인 상태별 -----

	static Stream<Arguments> statusAndOperation() {
		return Stream.of(Kind.values()).flatMap(kind -> ALL_OPERATIONS.stream().map(op -> Arguments.of(kind, op)));
	}

	@ParameterizedTest(name = "{0} 계정은 {1}을 호출할 수 없다")
	@MethodSource("statusAndOperation")
	void 비로그인_승인대기_거절_계정은_모든_팀_기능을_각각의_오류로_거부당한다(Kind kind, String operation) {
		ErrorCode expected;
		GraphQlResponse response;
		switch (kind) {
			case ANONYMOUS -> {
				expected = ErrorCode.UNAUTHENTICATED;
				response = run(null, operation);
			}
			case PENDING -> {
				expected = ErrorCode.ACCOUNT_PENDING;
				response = run(pendingMember(), operation);
			}
			default -> {
				expected = ErrorCode.ACCOUNT_REJECTED;
				response = run(rejectedMember(), operation);
			}
		}

		assertGraphQlError(response, expected);
		assertThat(response.dataIsNull()).isTrue();
		// 거부된 호출은 아무것도 바꾸지 못한다
		assertThat(activeMemberCount(team.id())).isEqualTo(2);
		assertThat(teamRow(team.id()).get("name")).isEqualTo("접근 팀");
		assertThat(teamRow(team.id()).get("deleted_at")).isNull();
		assertThat(invitationRows(team.id(), invitee)).hasSize(1);
	}

	@Test
	void 승인_상태가_바뀌면_팀원이어도_다음_요청부터_접근할_수_없다() {
		String teamId = Long.toString(team.id());
		assertGraphQlOk(gql(teammate, TeamGql.TEAM, Map.of("id", teamId)));

		members.setStatus(teammate, MemberStatus.REJECTED);
		assertGraphQlError(gql(teammate, TeamGql.TEAM, Map.of("id", teamId)), ErrorCode.ACCOUNT_REJECTED);
		members.setStatus(teammate, MemberStatus.PENDING);
		assertGraphQlError(gql(teammate, TeamGql.TEAM, Map.of("id", teamId)), ErrorCode.ACCOUNT_PENDING);
		members.setStatus(teammate, MemberStatus.ACTIVE);

		assertGraphQlOk(gql(teammate, TeamGql.TEAM, Map.of("id", teamId)));
	}

	// ----- 팀원 전용 조회 -----

	@ParameterizedTest
	@ValueSource(strings = {"team", "teamMembers"})
	void 팀의_팀원과_팀장은_팀_정보를_조회한다(String operation) {
		assertGraphQlOk(run(leader, operation));
		assertGraphQlOk(run(teammate, operation));
	}

	@ParameterizedTest
	@ValueSource(strings = {"team", "teamMembers", "teamInviteCode", "sentTeamInvitations"})
	void 비소속_회원은_공개_팀이어도_팀_정보를_볼_수_없다(String operation) {
		assertGraphQlError(run(outsider, operation), ErrorCode.FORBIDDEN);
	}

	@ParameterizedTest
	@ValueSource(strings = {"team", "teamMembers", "teamInviteCode", "sentTeamInvitations"})
	void 관리자도_소속이_아니면_팀_정보를_볼_수_없다(String operation) {
		assertGraphQlError(run(admin, operation), ErrorCode.FORBIDDEN);
	}

	@Test
	void 비공개_팀도_비소속_회원과_관리자에게는_차단된다() {
		String id = Long.toString(privateTeam.id());

		assertGraphQlError(gql(outsider, TeamGql.TEAM, Map.of("id", id)), ErrorCode.FORBIDDEN);
		assertGraphQlError(gql(admin, TeamGql.TEAM, Map.of("id", id)), ErrorCode.FORBIDDEN);
		assertGraphQlError(gql(teammate, TeamGql.TEAM, Map.of("id", id)), ErrorCode.FORBIDDEN);
		assertGraphQlOk(gql(leader, TeamGql.TEAM, Map.of("id", id)));
	}

	@Test
	void 관리자도_팀원이_되면_일반_팀원_권한만_갖는다() {
		joinService.joinByCode(admin, team.inviteCode());

		assertGraphQlOk(run(admin, "team"));
		assertGraphQlOk(run(admin, "teamMembers"));
		assertGraphQlError(run(admin, "teamInviteCode"), ErrorCode.FORBIDDEN);
		assertGraphQlError(run(admin, "kickTeamMember"), ErrorCode.FORBIDDEN);
		assertGraphQlError(run(admin, "deleteTeam"), ErrorCode.FORBIDDEN);
	}

	@Test
	void 팀_접근_거부_문구는_권한_없음_안내다() {
		GraphQlResponse response = run(outsider, "team");

		assertGraphQlError(response, ErrorCode.FORBIDDEN);
		assertThat(response.errorMessage()).isEqualTo("이 페이지에 들어올 권한이 없어요.");
		assertThat(response.errorClassification()).isEqualTo("FORBIDDEN");
		// 응답에 팀 이름이 새지 않는다
		assertThat(response.rawBody()).doesNotContain("접근 팀");
	}

	// ----- 팀장 전용 -----

	@ParameterizedTest
	@ValueSource(strings = {"teamInviteCode", "sentTeamInvitations"})
	void 팀장만_초대코드와_보낸_초대를_조회한다(String operation) {
		assertGraphQlOk(run(leader, operation));
		assertGraphQlError(run(teammate, operation), ErrorCode.FORBIDDEN);
		assertGraphQlError(run(outsider, operation), ErrorCode.FORBIDDEN);
		assertGraphQlError(run(admin, operation), ErrorCode.FORBIDDEN);
	}

	@ParameterizedTest
	@ValueSource(strings = {"updateTeam", "inviteToTeam", "cancelTeamInvitation", "kickTeamMember",
			"transferTeamLeadership", "deleteTeam"})
	void 팀장이_아닌_팀원_비소속_관리자는_팀장_전용_변경을_할_수_없고_아무것도_바뀌지_않는다(String operation) {
		assertGraphQlError(run(teammate, operation), ErrorCode.FORBIDDEN);
		assertGraphQlError(run(outsider, operation), ErrorCode.FORBIDDEN);
		assertGraphQlError(run(admin, operation), ErrorCode.FORBIDDEN);

		assertThat(((Number) teamRow(team.id()).get("leader_id")).longValue()).isEqualTo(leader);
		assertThat(teamRow(team.id()).get("name")).isEqualTo("접근 팀");
		assertThat(teamRow(team.id()).get("deleted_at")).isNull();
		assertThat(isActiveMember(team.id(), teammate)).isTrue();
		assertThat(invitationRows(team.id(), invitee)).extracting(r -> r.get("status")).containsExactly("PENDING");
	}

	@Test
	void 팀원이_아닌_사람은_팀을_나갈_수_없다() {
		assertGraphQlError(run(outsider, "leaveTeam"), ErrorCode.FORBIDDEN);
		assertGraphQlError(run(admin, "leaveTeam"), ErrorCode.FORBIDDEN);
		assertThat(activeMemberCount(team.id())).isEqualTo(2);
	}

	// ----- 없는 팀 (E-53) -----

	@ParameterizedTest
	@ValueSource(strings = {"team", "teamMembers", "teamInviteCode", "sentTeamInvitations", "updateTeam", "inviteToTeam",
			"kickTeamMember", "leaveTeam", "transferTeamLeadership", "deleteTeam"})
	void 없는_팀_id는_팀장에게도_없는_팀이다(String operation) {
		Call call = call(operation, 987_654_321L);

		GraphQlResponse response = gql(leader, call.query(), call.variables());

		assertGraphQlError(response, ErrorCode.NOT_FOUND);
	}

	@ParameterizedTest
	@ValueSource(strings = {"abc", "0", "-3", "1.5", "99999999999999999999"})
	void 숫자가_아닌_팀_id도_없는_팀과_같게_처리한다(String rawId) {
		assertGraphQlError(gql(leader, TeamGql.TEAM, Map.of("id", rawId)), ErrorCode.NOT_FOUND);
		assertGraphQlError(gql(leader, TeamGql.TEAM_MEMBERS, Map.of("id", rawId)), ErrorCode.NOT_FOUND);
		assertGraphQlError(gql(leader, TeamGql.DELETE, Map.of("teamId", rawId)), ErrorCode.NOT_FOUND);
	}

	@Test
	void 비소속_회원에게도_없는_팀은_권한_오류가_아니라_없는_팀이다() {
		assertGraphQlError(gql(outsider, TeamGql.TEAM, Map.of("id", "987654321")), ErrorCode.NOT_FOUND);
	}

	// ----- 참가·초대 응답은 당사자 것만 -----

	@Test
	void 받은_초대와_내_팀_목록은_호출한_본인_것만_돌려준다() {
		assertThat(gql(invitee, TeamGql.RECEIVED).data().path("receivedTeamInvitations")).hasSize(1);
		assertThat(gql(outsider, TeamGql.RECEIVED).data().path("receivedTeamInvitations")).isEmpty();
		assertThat(gql(outsider, TeamGql.MY_TEAMS).data().path("myTeams")).isEmpty();
		assertThat(gql(teammate, TeamGql.MY_TEAMS).data().path("myTeams")).hasSize(1);
		assertThat(gql(leader, TeamGql.MY_TEAMS).data().path("myTeams")).hasSize(2);
	}

}
