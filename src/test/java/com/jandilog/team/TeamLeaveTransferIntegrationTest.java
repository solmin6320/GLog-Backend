package com.jandilog.team;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.jandilog.common.exception.ErrorCode;
import com.jandilog.team.dto.SentInvitationResponse;
import com.jandilog.team.dto.TeamMemberResponse;
import com.jandilog.team.dto.TeamResponse;
import com.jandilog.team.dto.UpdateTeamInput;
import com.jandilog.team.support.TeamGql;
import com.jandilog.team.support.TeamIntegrationTest;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;

// 자진 탈퇴와 팀장 위임 (기능명세서 2장·6장, 화면설계서 TM-05 ④·TM-06 ⑥, E-19·E-20)
class TeamLeaveTransferIntegrationTest extends TeamIntegrationTest {

	private static final LocalDate WEEK_1 = LocalDate.of(2026, 9, 7);

	// ----- 자진 탈퇴 -----

	@Test
	void 팀원이_나가면_SELF로_소속이_닫히고_더_이상_팀에_접근하지_못한다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long leaver = joinedMember(team);
		long stays = joinedMember(team);

		leaveService.leave(leaver, team.id());

		Map<String, Object> row = membershipRows(team.id(), leaver).get(0);
		assertThat(row.get("left_at")).isNotNull();
		assertThat(row.get("leave_type")).isEqualTo("SELF");
		assertThat(activeMemberCount(team.id())).isEqualTo(2);
		assertThat(isActiveMember(team.id(), stays)).isTrue();
		assertThat(teamService.myTeams(leaver)).isEmpty();
		assertApiError(() -> teamService.get(leaver, team.id()), ErrorCode.FORBIDDEN);
	}

	@Test
	void 자진_탈퇴는_경고와_팀_카테고리를_그대로_둔다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long leaver = joinedMember(team);
		long warningId = warnings.warning(leaver, WEEK_1, team.id());

		leaveService.leave(leaver, team.id());

		assertThat(warnings.warning(warningId).alive()).isTrue();
		assertThat(warnings.category(warningId, team.id()).removed()).isFalse();
		assertThat(warnings.aliveCount(leaver)).isEqualTo(1);
	}

	@Test
	void 자진_탈퇴는_차단_키를_만들지_않아_초대코드로_다시_들어올_수_있다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long leaver = joinedMember(team);

		leaveService.leave(leaver, team.id());

		assertThat(redis.hasKey(banKey(team.id(), leaver))).isFalse();
		joinService.joinByCode(leaver, team.inviteCode());
		assertThat(isActiveMember(team.id(), leaver)).isTrue();
	}

	@Test
	void 한_팀에서_나가도_다른_팀_소속은_유지된다() {
		CreatedTeam teamA = newTeam(member());
		CreatedTeam teamB = newTeam(member());
		long member = joinedMember(teamA);
		joinService.joinByCode(member, teamB.inviteCode());

		leaveService.leave(member, teamA.id());

		assertThat(teamService.myTeams(member)).extracting(TeamResponse::id).containsExactly(teamB.id());
	}

	@Test
	void 팀원이_아닌_사람과_이미_나간_사람은_나갈_수_없다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long outsider = member();
		long leaver = joinedMember(team);
		leaveService.leave(leaver, team.id());

		assertApiError(() -> leaveService.leave(outsider, team.id()), ErrorCode.FORBIDDEN);
		assertApiError(() -> leaveService.leave(leaver, team.id()), ErrorCode.FORBIDDEN);
		assertApiError(() -> leaveService.leave(leader, 987_654_321L), ErrorCode.NOT_FOUND);
	}

	// ----- 팀장은 나갈 수 없다 (E-19, E-20) -----

	@Test
	void 팀장은_팀원이_남아_있으면_위임_없이_나갈_수_없다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		joinedMember(team);

		assertApiError(() -> leaveService.leave(leader, team.id()), ErrorCode.LEADER_MUST_TRANSFER);

		assertThat(isActiveMember(team.id(), leader)).isTrue();
		assertThat(teamService.get(leader, team.id()).isLeader()).isTrue();
		assertThat(teamRow(team.id()).get("deleted_at")).isNull();
	}

	@Test
	void 혼자_있는_팀의_팀장은_나갈_수_없고_팀_삭제만_안내한다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);

		assertApiError(() -> leaveService.leave(leader, team.id()), ErrorCode.LEADER_LAST_MEMBER);

		assertThat(isActiveMember(team.id(), leader)).isTrue();
		assertThat(teamRow(team.id()).get("deleted_at")).isNull();
		// 삭제는 가능하다
		leaveService.delete(leader, team.id());
		assertThat(teamRow(team.id()).get("deleted_at")).isNotNull();
	}

	@Test
	void 팀원이_모두_나가고_혼자_남은_팀장도_E_20으로_안내한다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long teammate = joinedMember(team);
		leaveService.leave(teammate, team.id());

		assertApiError(() -> leaveService.leave(leader, team.id()), ErrorCode.LEADER_LAST_MEMBER);
	}

	@Test
	void 위임한_뒤에는_이전_팀장이_일반_팀원으로_나갈_수_있다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long successor = joinedMember(team);
		long warningId = warnings.warning(leader, WEEK_1, team.id());
		leaveService.transferLeadership(leader, team.id(), successor);

		leaveService.leave(leader, team.id());

		assertThat(membershipRows(team.id(), leader).get(0).get("leave_type")).isEqualTo("SELF");
		assertThat(teamService.get(successor, team.id()).memberCount()).isEqualTo(1);
		assertThat(warnings.warning(warningId).alive()).isTrue();
	}

	// ----- 팀장 위임 -----

	@Test
	void 위임하면_팀장이_바뀌고_이전_팀장은_일반_팀원이_된다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long successor = joinedMember(team);
		clock.fixAt(Instant.parse("2026-09-01T00:00:00Z"));

		TeamResponse response = leaveService.transferLeadership(leader, team.id(), successor);

		assertThat(((Number) teamRow(team.id()).get("leader_id")).longValue()).isEqualTo(successor);
		assertThat(response.leader().id()).isEqualTo(successor);
		assertThat(response.isLeader()).isFalse();
		assertThat(isActiveMember(team.id(), leader)).isTrue();
		assertThat(teamService.get(successor, team.id()).isLeader()).isTrue();
		assertThat(teamService.get(leader, team.id()).isLeader()).isFalse();
		assertThat(teamService.members(successor, team.id())).extracting(TeamMemberResponse::id)
				.containsExactly(successor, leader);
		assertThat(teamService.members(successor, team.id())).extracting(TeamMemberResponse::isLeader)
				.containsExactly(true, false);
	}

	@Test
	void 위임하면_이전_팀장은_팀장_권한을_모두_잃고_새_팀장이_갖는다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long successor = joinedMember(team);
		long target = joinedMember(team);
		long invitee = member();
		leaveService.transferLeadership(leader, team.id(), successor);

		assertApiError(() -> teamService.inviteCode(leader, team.id()), ErrorCode.FORBIDDEN);
		assertApiError(() -> teamService.update(leader, team.id(), new UpdateTeamInput("이전 팀장 수정", null, null)),
				ErrorCode.FORBIDDEN);
		assertApiError(() -> invitationService.invite(leader, team.id(), loginOf(invitee)), ErrorCode.FORBIDDEN);
		assertApiError(() -> invitationService.sent(leader, team.id()), ErrorCode.FORBIDDEN);
		assertApiError(() -> leaveService.kick(leader, team.id(), target), ErrorCode.FORBIDDEN);
		assertApiError(() -> leaveService.delete(leader, team.id()), ErrorCode.FORBIDDEN);
		// 되돌릴 방법이 없다: 이전 팀장이 다시 자기를 지정할 수 없다
		assertApiError(() -> leaveService.transferLeadership(leader, team.id(), leader), ErrorCode.FORBIDDEN);
		assertApiError(() -> leaveService.transferLeadership(leader, team.id(), successor), ErrorCode.FORBIDDEN);

		assertThat(teamService.inviteCode(successor, team.id())).isEqualTo(team.inviteCode());
		assertThat(teamService.update(successor, team.id(), new UpdateTeamInput("새 팀장 수정", null, null)).name())
				.isEqualTo("새 팀장 수정");
		SentInvitationResponse sent = invitationService.invite(successor, team.id(), loginOf(invitee));
		assertThat(invitationService.sent(successor, team.id())).extracting(SentInvitationResponse::id).containsExactly(sent.id());
		leaveService.kick(successor, team.id(), target);
		assertThat(teamRow(team.id()).get("name")).isEqualTo("새 팀장 수정");
		assertThat(isActiveMember(team.id(), target)).isFalse();
	}

	@Test
	void 위임_대상은_현재_팀원이어야_한다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long outsider = member();
		long selfLeft = joinedMember(team);
		leaveService.leave(selfLeft, team.id());
		long kicked = joinedMember(team);
		leaveService.kick(leader, team.id(), kicked);

		assertApiError(() -> leaveService.transferLeadership(leader, team.id(), outsider), ErrorCode.TEAM_MEMBER_NOT_FOUND);
		assertApiError(() -> leaveService.transferLeadership(leader, team.id(), selfLeft), ErrorCode.TEAM_MEMBER_NOT_FOUND);
		assertApiError(() -> leaveService.transferLeadership(leader, team.id(), kicked), ErrorCode.TEAM_MEMBER_NOT_FOUND);
		assertApiError(() -> leaveService.transferLeadership(leader, team.id(), 987_654_321L), ErrorCode.TEAM_MEMBER_NOT_FOUND);

		assertThat(teamService.get(leader, team.id()).isLeader()).isTrue();
	}

	@Test
	void 자기_자신에게는_위임할_수_없다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		joinedMember(team);

		assertApiError(() -> leaveService.transferLeadership(leader, team.id(), leader), ErrorCode.INVALID_INPUT);

		assertThat(teamService.get(leader, team.id()).isLeader()).isTrue();
	}

	@Test
	void 팀장이_아니면_위임할_수_없다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long teammate = joinedMember(team);
		long other = joinedMember(team);
		long outsider = member();
		long admin = adminMember();

		assertApiError(() -> leaveService.transferLeadership(teammate, team.id(), other), ErrorCode.FORBIDDEN);
		assertApiError(() -> leaveService.transferLeadership(teammate, team.id(), teammate), ErrorCode.FORBIDDEN);
		assertApiError(() -> leaveService.transferLeadership(outsider, team.id(), other), ErrorCode.FORBIDDEN);
		assertApiError(() -> leaveService.transferLeadership(admin, team.id(), other), ErrorCode.FORBIDDEN);

		assertThat(teamService.get(leader, team.id()).isLeader()).isTrue();
	}

	@Test
	void 위임받은_팀장은_추방_대상이_될_수_없고_이전_팀장은_추방할_수_없다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long successor = joinedMember(team);
		leaveService.transferLeadership(leader, team.id(), successor);

		assertApiError(() -> leaveService.kick(leader, team.id(), successor), ErrorCode.FORBIDDEN);
		assertApiError(() -> leaveService.kick(successor, team.id(), successor), ErrorCode.INVALID_INPUT);
		// 새 팀장은 이전 팀장을 일반 팀원으로 추방할 수 있다
		leaveService.kick(successor, team.id(), leader);
		assertThat(isActiveMember(team.id(), leader)).isFalse();
	}

	@Test
	void 위임_뒤_새_팀장도_위임해서_이어질_수_있다() {
		long first = member();
		CreatedTeam team = newTeam(first);
		long second = joinedMember(team);
		long third = joinedMember(team);

		leaveService.transferLeadership(first, team.id(), second);
		leaveService.transferLeadership(second, team.id(), third);

		assertThat(((Number) teamRow(team.id()).get("leader_id")).longValue()).isEqualTo(third);
		assertThat(teamService.members(first, team.id())).extracting(TeamMemberResponse::id).first().isEqualTo(third);
	}

	// ----- GraphQL -----

	@Test
	void transferTeamLeadership과_leaveTeam은_GraphQL로도_동작한다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long successor = joinedMember(team);
		String teamId = Long.toString(team.id());

		assertGraphQlError(gql(leader, TeamGql.LEAVE, Map.of("teamId", teamId)), ErrorCode.LEADER_MUST_TRANSFER);
		GraphQlResponse transferred = gql(leader, TeamGql.TRANSFER,
				Map.of("teamId", teamId, "newLeaderId", Long.toString(successor)));
		assertGraphQlOk(transferred);
		assertThat(transferred.data().path("transferTeamLeadership").path("isLeader").asBoolean()).isFalse();
		assertThat(transferred.data().path("transferTeamLeadership").path("leader").path("id").asText())
				.isEqualTo(Long.toString(successor));
		GraphQlResponse left = gql(leader, TeamGql.LEAVE, Map.of("teamId", teamId));

		assertGraphQlOk(left);
		assertThat(left.data().path("leaveTeam").asBoolean()).isTrue();
		assertThat(isActiveMember(team.id(), leader)).isFalse();
		assertGraphQlError(gql(successor, TeamGql.LEAVE, Map.of("teamId", teamId)), ErrorCode.LEADER_LAST_MEMBER);
	}

	@Test
	void 혼자_있는_팀장의_나가기_오류_문구는_삭제_확인_질문이다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);

		GraphQlResponse response = gql(leader, TeamGql.LEAVE, Map.of("teamId", Long.toString(team.id())));

		assertGraphQlError(response, ErrorCode.LEADER_LAST_MEMBER);
		assertThat(response.errorMessage()).isEqualTo("혼자 있는 팀이에요. 나가면 팀이 삭제돼요. 삭제할까요?");
	}

	@Test
	void 팀장이_나갈_수_없다는_안내_문구는_위임을_먼저_하라고_알려준다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		joinedMember(team);

		GraphQlResponse response = gql(leader, TeamGql.LEAVE, Map.of("teamId", Long.toString(team.id())));

		assertThat(response.errorMessage()).isEqualTo("팀장은 다른 팀원에게 팀장을 넘긴 뒤에 나갈 수 있어요.");
	}

}
