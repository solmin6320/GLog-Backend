package com.jandilog.team;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.post.dto.CursorPage;
import com.jandilog.team.dto.ReceivedInvitationResponse;
import com.jandilog.team.dto.SentInvitationResponse;
import com.jandilog.team.dto.TeamMemberResponse;
import com.jandilog.team.support.TeamGql;
import com.jandilog.team.support.TeamIntegrationTest;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;

// 팀원·받은 초대·보낸 초대 목록의 커서 기반 20개 {items, nextCursor} (기능명세서 3장 목록, 10장 페이지네이션, Q-06).
// 정확히 20개·21개·빈 목록·마지막 페이지의 nextCursor=null을 확인한다
class TeamListCursorIntegrationTest extends TeamIntegrationTest {

	private static final int PAGE = 20;

	// 팀장 한 명이 만든 팀에 count명이 차례로 참가한다. 참가한 순서대로 id를 돌려준다
	private List<Long> joinMembers(CreatedTeam team, int count) {
		List<Long> joined = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			joined.add(joinedMember(team));
		}
		return joined;
	}

	private static List<Long> ids(List<TeamMemberResponse> items) {
		return items.stream().map(TeamMemberResponse::id).toList();
	}

	// ----- 팀원 목록 -----

	@Test
	void 팀원이_팀장_한_명이면_한_건이고_다음_커서가_없다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);

		CursorPage<TeamMemberResponse> page = teamService.members(leader, team.id(), null);

		assertThat(ids(page.items())).containsExactly(leader);
		assertThat(page.nextCursor()).isNull();
	}

	@Test
	void 팀원이_정확히_20명이면_한_페이지에_다_담기고_다음_커서가_없다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		List<Long> joined = joinMembers(team, PAGE - 1);

		CursorPage<TeamMemberResponse> page = teamService.members(joined.get(0), team.id(), null);

		assertThat(page.items()).hasSize(PAGE);
		assertThat(page.nextCursor()).isNull();
		assertThat(ids(page.items())).startsWith(leader).containsAll(joined);
	}

	@Test
	void 팀원이_21명이면_20명_다음_커서_그리고_1명으로_나뉜다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		List<Long> joined = joinMembers(team, PAGE);

		CursorPage<TeamMemberResponse> first = teamService.members(leader, team.id(), null);
		assertThat(first.items()).hasSize(PAGE);
		assertThat(first.nextCursor()).isNotNull();
		// 팀장이 맨 앞, 이어서 참가한 순서
		assertThat(ids(first.items())).isEqualTo(prepend(leader, joined.subList(0, PAGE - 1)));

		CursorPage<TeamMemberResponse> second = teamService.members(leader, team.id(), first.nextCursor());
		assertThat(ids(second.items())).containsExactly(joined.get(PAGE - 1));
		assertThat(second.nextCursor()).isNull();
	}

	@Test
	void 팀원이_41명이면_20_20_1로_나뉘고_모두_한_번씩만_나온다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		List<Long> joined = joinMembers(team, PAGE * 2);

		CursorPage<TeamMemberResponse> first = teamService.members(leader, team.id(), null);
		CursorPage<TeamMemberResponse> second = teamService.members(leader, team.id(), first.nextCursor());
		CursorPage<TeamMemberResponse> third = teamService.members(leader, team.id(), second.nextCursor());

		assertThat(first.items()).hasSize(PAGE);
		assertThat(second.items()).hasSize(PAGE);
		assertThat(third.items()).hasSize(1);
		assertThat(first.nextCursor()).isNotNull();
		assertThat(second.nextCursor()).isNotNull();
		assertThat(third.nextCursor()).isNull();
		List<Long> all = new ArrayList<>();
		all.addAll(ids(first.items()));
		all.addAll(ids(second.items()));
		all.addAll(ids(third.items()));
		assertThat(all).isEqualTo(prepend(leader, joined));
	}

	@Test
	void 팀장을_위임해도_새_팀장은_첫_페이지_맨_앞에만_나오고_다음_페이지에_또_나오지_않는다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		List<Long> joined = joinMembers(team, PAGE);
		long successor = joined.get(PAGE - 1);
		leaveService.transferLeadership(leader, team.id(), successor);

		CursorPage<TeamMemberResponse> first = teamService.members(successor, team.id(), null);
		CursorPage<TeamMemberResponse> second = teamService.members(successor, team.id(), first.nextCursor());

		assertThat(first.items()).hasSize(PAGE);
		assertThat(first.items().get(0).id()).isEqualTo(successor);
		assertThat(first.items().get(0).isLeader()).isTrue();
		assertThat(first.nextCursor()).isNotNull();
		assertThat(ids(second.items())).hasSize(1);
		assertThat(second.nextCursor()).isNull();
		List<Long> all = new ArrayList<>(ids(first.items()));
		all.addAll(ids(second.items()));
		assertThat(all).doesNotHaveDuplicates().hasSize(PAGE + 1);
	}

	@Test
	void 페이지_사이에_팀원이_나가도_커서_다음부터_빠짐없이_이어진다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		List<Long> joined = joinMembers(team, PAGE + 1);
		CursorPage<TeamMemberResponse> first = teamService.members(leader, team.id(), null);

		leaveService.kick(leader, team.id(), joined.get(0));
		CursorPage<TeamMemberResponse> second = teamService.members(leader, team.id(), first.nextCursor());

		assertThat(ids(second.items())).containsExactly(joined.get(PAGE - 1), joined.get(PAGE));
		assertThat(second.nextCursor()).isNull();
	}

	@Test
	void 팀원_목록_커서는_그_팀의_팀원에게만_보인다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		joinMembers(team, PAGE);
		long outsider = member();
		String cursor = teamService.members(leader, team.id(), null).nextCursor();

		assertApiError(() -> teamService.members(outsider, team.id(), cursor), ErrorCode.FORBIDDEN);
	}

	// ----- 받은 초대 목록 -----

	@Test
	void 받은_초대가_없으면_빈_목록이고_다음_커서가_없다() {
		CursorPage<ReceivedInvitationResponse> page = invitationService.received(member(), null);

		assertThat(page.items()).isEmpty();
		assertThat(page.nextCursor()).isNull();
	}

	// 팀장 한 명이 팀을 count개 만들고 invitee를 모두에 초대한다. 초대 id를 보낸 순서대로, 팀 id와 함께 돌려준다
	private record Sent(List<Long> invitationIds, List<Long> teamIds, long leader) {
	}

	private Sent inviteToManyTeams(long invitee, int count) {
		long leader = member();
		List<Long> invitationIds = new ArrayList<>();
		List<Long> teamIds = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			CreatedTeam team = newTeam(leader);
			teamIds.add(team.id());
			invitationIds.add(invitationService.invite(leader, team.id(), loginOf(invitee)).id());
		}
		return new Sent(invitationIds, teamIds, leader);
	}

	private static List<Long> received(List<ReceivedInvitationResponse> items) {
		return items.stream().map(ReceivedInvitationResponse::id).toList();
	}

	@Test
	void 받은_초대가_정확히_20건이면_한_페이지에_다_담기고_다음_커서가_없다() {
		long invitee = member();
		Sent sent = inviteToManyTeams(invitee, PAGE);

		CursorPage<ReceivedInvitationResponse> page = invitationService.received(invitee, null);

		// 최신순이라 보낸 순서의 반대
		assertThat(received(page.items())).isEqualTo(reversed(sent.invitationIds()));
		assertThat(page.nextCursor()).isNull();
	}

	@Test
	void 받은_초대가_21건이면_20건_다음_커서_그리고_1건으로_나뉜다() {
		long invitee = member();
		Sent sent = inviteToManyTeams(invitee, PAGE + 1);
		List<Long> newestFirst = reversed(sent.invitationIds());

		CursorPage<ReceivedInvitationResponse> first = invitationService.received(invitee, null);
		assertThat(first.items()).hasSize(PAGE);
		assertThat(first.nextCursor()).isNotNull();
		assertThat(received(first.items())).isEqualTo(newestFirst.subList(0, PAGE));

		CursorPage<ReceivedInvitationResponse> second = invitationService.received(invitee, first.nextCursor());
		assertThat(received(second.items())).containsExactly(newestFirst.get(PAGE));
		assertThat(second.nextCursor()).isNull();
	}

	@Test
	void 삭제된_팀의_초대는_페이지를_채우지_않고_빠진다() {
		long invitee = member();
		Sent sent = inviteToManyTeams(invitee, PAGE + 2);
		// 가장 최근 초대와 가장 오래된 초대의 팀을 지운다: 남는 초대는 정확히 20건
		leaveService.delete(sent.leader(), sent.teamIds().get(PAGE + 1));
		leaveService.delete(sent.leader(), sent.teamIds().get(0));

		CursorPage<ReceivedInvitationResponse> page = invitationService.received(invitee, null);

		assertThat(page.items()).hasSize(PAGE);
		assertThat(page.nextCursor()).isNull();
		assertThat(received(page.items())).isEqualTo(reversed(sent.invitationIds().subList(1, PAGE + 1)));
	}

	@Test
	void 페이지_사이에_거절한_초대는_다음_페이지에서_빠진다() {
		long invitee = member();
		Sent sent = inviteToManyTeams(invitee, PAGE + 1);
		CursorPage<ReceivedInvitationResponse> first = invitationService.received(invitee, null);
		assertThat(first.nextCursor()).isNotNull();

		// 다음 페이지에 올 마지막 한 건(가장 오래된 초대)을 거절한다
		invitationService.decline(invitee, sent.invitationIds().get(0));
		CursorPage<ReceivedInvitationResponse> second = invitationService.received(invitee, first.nextCursor());

		assertThat(second.items()).isEmpty();
		assertThat(second.nextCursor()).isNull();
	}

	// ----- 보낸 초대 목록 -----

	// 한 팀에서 count명을 초대한다. 초대 id를 보낸 순서대로 돌려준다
	private List<Long> inviteMany(long leader, CreatedTeam team, int count) {
		List<Long> invitationIds = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			invitationIds.add(invitationService.invite(leader, team.id(), loginOf(member())).id());
		}
		return invitationIds;
	}

	private static List<Long> sent(List<SentInvitationResponse> items) {
		return items.stream().map(SentInvitationResponse::id).toList();
	}

	@Test
	void 보낸_초대가_없으면_빈_목록이고_다음_커서가_없다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);

		CursorPage<SentInvitationResponse> page = invitationService.sent(leader, team.id(), null);

		assertThat(page.items()).isEmpty();
		assertThat(page.nextCursor()).isNull();
	}

	@Test
	void 보낸_초대가_정확히_20건이면_한_페이지에_다_담기고_다음_커서가_없다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		List<Long> invitationIds = inviteMany(leader, team, PAGE);

		CursorPage<SentInvitationResponse> page = invitationService.sent(leader, team.id(), null);

		assertThat(sent(page.items())).isEqualTo(reversed(invitationIds));
		assertThat(page.nextCursor()).isNull();
	}

	@Test
	void 보낸_초대가_21건이면_20건_다음_커서_그리고_1건으로_나뉜다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		List<Long> newestFirst = reversed(inviteMany(leader, team, PAGE + 1));

		CursorPage<SentInvitationResponse> first = invitationService.sent(leader, team.id(), null);
		assertThat(first.items()).hasSize(PAGE);
		assertThat(first.nextCursor()).isNotNull();
		assertThat(sent(first.items())).isEqualTo(newestFirst.subList(0, PAGE));

		CursorPage<SentInvitationResponse> second = invitationService.sent(leader, team.id(), first.nextCursor());
		assertThat(sent(second.items())).containsExactly(newestFirst.get(PAGE));
		assertThat(second.nextCursor()).isNull();
	}

	@Test
	void 보낸_초대에는_대기_중인_초대만_세어서_21건째가_수락된_초대면_20건이_마지막이다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		List<Long> invitationIds = inviteMany(leader, team, PAGE);
		long accepted = member();
		long acceptedInvitation = invitationService.invite(leader, team.id(), loginOf(accepted)).id();
		invitationService.accept(accepted, acceptedInvitation);

		CursorPage<SentInvitationResponse> page = invitationService.sent(leader, team.id(), null);

		assertThat(sent(page.items())).isEqualTo(reversed(invitationIds));
		assertThat(page.nextCursor()).isNull();
	}

	@Test
	void 보낸_초대_목록은_팀장만_읽고_커서가_있어도_같다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long teammate = joinedMember(team);
		inviteMany(leader, team, PAGE + 1);
		String cursor = invitationService.sent(leader, team.id(), null).nextCursor();

		assertApiError(() -> invitationService.sent(teammate, team.id(), cursor), ErrorCode.FORBIDDEN);
	}

	// ----- GraphQL: {items, nextCursor}와 after -----

	@Test
	void teamMembers는_GraphQL로_items와_nextCursor를_주고_after로_이어_읽는다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		List<Long> joined = joinMembers(team, PAGE);
		String teamId = Long.toString(team.id());

		GraphQlResponse first = gql(leader, TeamGql.TEAM_MEMBERS, Map.of("id", teamId));
		assertGraphQlOk(first);
		JsonNode firstPage = first.data().path("teamMembers");
		assertThat(firstPage.path("items")).hasSize(PAGE);
		assertThat(firstPage.path("items").get(0).path("id").asText()).isEqualTo(Long.toString(leader));
		assertThat(firstPage.path("items").get(0).path("isLeader").asBoolean()).isTrue();
		assertThat(firstPage.path("nextCursor").isNull()).isFalse();

		GraphQlResponse second = gql(leader, TeamGql.TEAM_MEMBERS,
				Map.of("id", teamId, "after", firstPage.path("nextCursor").asText()));
		assertGraphQlOk(second);
		JsonNode secondPage = second.data().path("teamMembers");
		assertThat(secondPage.path("items")).hasSize(1);
		assertThat(secondPage.path("items").get(0).path("id").asText()).isEqualTo(Long.toString(joined.get(PAGE - 1)));
		assertThat(secondPage.path("nextCursor").isNull()).isTrue();
	}

	@Test
	void 받은_초대와_보낸_초대도_GraphQL로_items와_nextCursor를_준다() {
		long invitee = member();
		Sent sent = inviteToManyTeams(invitee, PAGE + 1);
		long leader = member();
		CreatedTeam team = newTeam(leader);
		inviteMany(leader, team, PAGE + 1);

		GraphQlResponse received = gql(invitee, TeamGql.RECEIVED);
		assertGraphQlOk(received);
		assertThat(received.data().path("receivedTeamInvitations").path("items")).hasSize(PAGE);
		String receivedCursor = received.data().path("receivedTeamInvitations").path("nextCursor").asText();
		GraphQlResponse receivedLast = gql(invitee, TeamGql.RECEIVED, Map.of("after", receivedCursor));
		assertThat(receivedLast.data().path("receivedTeamInvitations").path("items")).hasSize(1);
		assertThat(receivedLast.data().path("receivedTeamInvitations").path("items").get(0).path("id").asText())
				.isEqualTo(Long.toString(sent.invitationIds().get(0)));
		assertThat(receivedLast.data().path("receivedTeamInvitations").path("nextCursor").isNull()).isTrue();

		String teamId = Long.toString(team.id());
		GraphQlResponse sentFirst = gql(leader, TeamGql.SENT, Map.of("id", teamId));
		assertGraphQlOk(sentFirst);
		assertThat(sentFirst.data().path("sentTeamInvitations").path("items")).hasSize(PAGE);
		String sentCursor = sentFirst.data().path("sentTeamInvitations").path("nextCursor").asText();
		GraphQlResponse sentLast = gql(leader, TeamGql.SENT, Map.of("id", teamId, "after", sentCursor));
		assertThat(sentLast.data().path("sentTeamInvitations").path("items")).hasSize(1);
		assertThat(sentLast.data().path("sentTeamInvitations").path("nextCursor").isNull()).isTrue();
	}

	@Test
	void 읽을_수_없는_커서는_세_목록_모두_입력_오류다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		String teamId = Long.toString(team.id());

		assertGraphQlError(gql(leader, TeamGql.TEAM_MEMBERS, Map.of("id", teamId, "after", "엉터리")),
				ErrorCode.INVALID_INPUT);
		assertGraphQlError(gql(leader, TeamGql.RECEIVED, Map.of("after", "엉터리")), ErrorCode.INVALID_INPUT);
		assertGraphQlError(gql(leader, TeamGql.SENT, Map.of("id", teamId, "after", "엉터리")), ErrorCode.INVALID_INPUT);
	}

	private static List<Long> prepend(long first, List<Long> rest) {
		List<Long> all = new ArrayList<>();
		all.add(first);
		all.addAll(rest);
		return all;
	}

	private static List<Long> reversed(List<Long> list) {
		List<Long> copy = new ArrayList<>(list);
		Collections.reverse(copy);
		return copy;
	}

}
