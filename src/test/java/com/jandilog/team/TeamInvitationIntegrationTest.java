package com.jandilog.team;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.team.dto.ReceivedInvitationResponse;
import com.jandilog.team.dto.SentInvitationResponse;
import com.jandilog.team.service.AcceptInvitationResult;
import com.jandilog.team.support.TeamGql;
import com.jandilog.team.support.TeamIntegrationTest;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;

// 아이디 초대 (기능명세서 2장 "아이디 초대", 화면설계서 TM-04·TM-06, E-13·E-15~E-18, DB명세서 1-4)
class TeamInvitationIntegrationTest extends TeamIntegrationTest {

	private long invitationCount(long teamId) {
		Long count = jdbc.queryForObject("select count(*) from team_invitation where team_id = ?", Long.class, teamId);
		return count == null ? 0 : count;
	}

	private String statusOf(long invitationId) {
		return jdbc.queryForObject("select status from team_invitation where id = ?", String.class, invitationId);
	}

	private boolean responded(long invitationId) {
		return jdbc.queryForObject("select responded_at from team_invitation where id = ?", java.sql.Timestamp.class,
				invitationId) != null;
	}

	private SentInvitationResponse invite(long leader, CreatedTeam team, long invitee) {
		return invitationService.invite(leader, team.id(), loginOf(invitee));
	}

	// ----- 초대 보내기 -----

	@Test
	void 팀장이_아이디로_초대하면_대기_초대가_만들어지고_상대의_받은_초대에_나타난다() {
		long leader = member();
		CreatedTeam team = newTeam(leader, "초대 팀", null, true);
		long invitee = member();

		SentInvitationResponse sent = invite(leader, team, invitee);

		List<Map<String, Object>> rows = invitationRows(team.id(), invitee);
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).get("status")).isEqualTo("PENDING");
		assertThat(rows.get(0).get("responded_at")).isNull();
		assertThat(sent.invitee().id()).isEqualTo(invitee);
		assertThat(invitationService.sent(leader, team.id())).extracting(SentInvitationResponse::id).containsExactly(sent.id());
		List<ReceivedInvitationResponse> received = invitationService.received(invitee);
		assertThat(received).hasSize(1);
		assertThat(received.get(0).id()).isEqualTo(sent.id());
		assertThat(received.get(0).teamId()).isEqualTo(team.id());
		assertThat(received.get(0).teamName()).isEqualTo("초대 팀");
		assertThat(received.get(0).inviter().id()).isEqualTo(leader);
		// 수락하기 전에는 팀원이 아니다
		assertThat(isActiveMember(team.id(), invitee)).isFalse();
	}

	@Test
	void 비공개_팀의_초대도_초대받은_당사자에게는_실제_팀_이름이_보인다() {
		long leader = member();
		CreatedTeam team = newTeam(leader, "숨은 팀", null, false);
		long invitee = member();

		invite(leader, team, invitee);

		assertThat(invitationService.received(invitee)).extracting(ReceivedInvitationResponse::teamName).containsExactly("숨은 팀");
	}

	@Test
	void 아이디는_대소문자를_구분하지_않고_찾는다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long invitee = memberWithLogin("MixedCase-" + members.tag());

		invitationService.invite(leader, team.id(), ("mixedcase-" + members.tag()).toLowerCase());
		assertApiError(() -> invitationService.invite(leader, team.id(), "MIXEDCASE-" + members.tag().toUpperCase()),
				ErrorCode.INVITATION_DUPLICATE);

		assertThat(invitationRows(team.id(), invitee)).hasSize(1);
	}

	@Test
	void 가입하지_않은_아이디는_초대할_수_없다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);

		assertApiError(() -> invitationService.invite(leader, team.id(), "nobody-" + members.tag()), ErrorCode.INVITEE_NOT_FOUND);

		assertThat(invitationCount(team.id())).isZero();
	}

	@Test
	void 승인_대기와_거절_계정은_가입한_회원이_아니므로_초대할_수_없다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long pending = pendingMember();
		long rejected = rejectedMember();

		assertApiError(() -> invitationService.invite(leader, team.id(), loginOf(pending)), ErrorCode.INVITEE_NOT_FOUND);
		assertApiError(() -> invitationService.invite(leader, team.id(), loginOf(rejected)), ErrorCode.INVITEE_NOT_FOUND);

		assertThat(invitationCount(team.id())).isZero();
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = {"", "   ", "bad login", "bad!login", "-leading", "한글아이디", "abcdefghijabcdefghijabcdefghijabcdefghij"})
	void 아이디_형식이_아니면_없는_회원으로_본다(String login) {
		long leader = member();
		CreatedTeam team = newTeam(leader);

		assertApiError(() -> invitationService.invite(leader, team.id(), login), ErrorCode.INVITEE_NOT_FOUND);

		assertThat(invitationCount(team.id())).isZero();
	}

	@Test
	void 이미_팀원인_사람은_초대할_수_없다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long teammate = joinedMember(team);

		assertApiError(() -> invite(leader, team, teammate), ErrorCode.INVITEE_ALREADY_MEMBER);
		assertApiError(() -> invite(leader, team, leader), ErrorCode.INVITEE_ALREADY_MEMBER);

		assertThat(invitationCount(team.id())).isZero();
	}

	@Test
	void 같은_사람에게_대기_중_초대가_있으면_하나_더_만들지_않는다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long invitee = member();
		invite(leader, team, invitee);

		assertApiError(() -> invite(leader, team, invitee), ErrorCode.INVITATION_DUPLICATE);

		assertThat(invitationRows(team.id(), invitee)).hasSize(1);
	}

	@Test
	void 다른_팀에서_받은_초대는_중복으로_보지_않는다() {
		long invitee = member();
		CreatedTeam teamA = newTeam(member());
		CreatedTeam teamB = newTeam(member());

		invitationService.invite(leaderOf(teamA), teamA.id(), loginOf(invitee));
		invitationService.invite(leaderOf(teamB), teamB.id(), loginOf(invitee));

		assertThat(invitationService.received(invitee)).hasSize(2);
	}

	private long leaderOf(CreatedTeam team) {
		return ((Number) teamRow(team.id()).get("leader_id")).longValue();
	}

	@Test
	void 거절한_뒤에는_다시_초대할_수_있다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long invitee = member();
		SentInvitationResponse first = invite(leader, team, invitee);
		invitationService.decline(invitee, first.id());

		SentInvitationResponse second = invite(leader, team, invitee);

		assertThat(second.id()).isNotEqualTo(first.id());
		assertThat(invitationRows(team.id(), invitee)).extracting(r -> r.get("status")).containsExactly("DECLINED", "PENDING");
	}

	@Test
	void 철회한_뒤에는_다시_초대할_수_있다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long invitee = member();
		invitationService.cancel(leader, invite(leader, team, invitee).id());

		invite(leader, team, invitee);

		assertThat(invitationRows(team.id(), invitee)).extracting(r -> r.get("status")).containsExactly("PENDING");
	}

	@Test
	void 자진_탈퇴한_사람은_다시_초대하고_수락하면_소속_이력이_이어서_쌓인다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long member = joinedMember(team);
		clock.fixAt(Instant.parse("2026-09-10T00:00:00Z"));
		leaveService.leave(member, team.id());
		clock.fixAt(Instant.parse("2026-09-11T00:00:00Z"));

		inviteAndAccept(leader, team, member);

		List<Map<String, Object>> rows = membershipRows(team.id(), member);
		assertThat(rows).hasSize(2);
		assertThat(rows.get(0).get("leave_type")).isEqualTo("SELF");
		assertThat(rows.get(1).get("joined_by")).isEqualTo("INVITATION");
		assertThat(rows.get(1).get("left_at")).isNull();
	}

	@Test
	void 추방한_사람도_팀장이_아이디로_다시_초대할_수_있다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long kicked = joinedMember(team);
		leaveService.kick(leader, team.id(), kicked);
		// 초대코드 차단 키가 살아 있어도 아이디 초대는 막지 않는다 (E-13)
		assertThat(redis.hasKey(banKey(team.id(), kicked))).isTrue();

		SentInvitationResponse sent = invite(leader, team, kicked);

		assertThat(statusOf(sent.id())).isEqualTo("PENDING");
		assertThat(invitationService.received(kicked)).extracting(ReceivedInvitationResponse::id).containsExactly(sent.id());
	}

	@Test
	void 팀장이_아니면_초대할_수_없다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long teammate = joinedMember(team);
		long outsider = member();
		long admin = adminMember();
		long invitee = member();

		assertApiError(() -> invite(teammate, team, invitee), ErrorCode.FORBIDDEN);
		assertApiError(() -> invite(outsider, team, invitee), ErrorCode.FORBIDDEN);
		assertApiError(() -> invite(admin, team, invitee), ErrorCode.FORBIDDEN);

		assertThat(invitationCount(team.id())).isZero();
	}

	@Test
	void 없는_팀이나_삭제된_팀에는_초대할_수_없다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long invitee = member();

		assertApiError(() -> invitationService.invite(leader, 987_654_321L, loginOf(invitee)), ErrorCode.NOT_FOUND);
		leaveService.delete(leader, team.id());
		assertApiError(() -> invite(leader, team, invitee), ErrorCode.NOT_FOUND);
	}

	// ----- 목록 -----

	@Test
	void 보낸_초대_목록은_대기_중인_초대만_최신순으로_팀장에게만_보인다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long a = member();
		long b = member();
		long c = member();
		long d = member();
		clock.fixAt(Instant.parse("2026-09-01T00:00:00Z"));
		SentInvitationResponse invA = invite(leader, team, a);
		clock.fixAt(Instant.parse("2026-09-02T00:00:00Z"));
		SentInvitationResponse invB = invite(leader, team, b);
		clock.fixAt(Instant.parse("2026-09-03T00:00:00Z"));
		SentInvitationResponse invC = invite(leader, team, c);
		clock.fixAt(Instant.parse("2026-09-04T00:00:00Z"));
		SentInvitationResponse invD = invite(leader, team, d);

		assertThat(invitationService.sent(leader, team.id())).extracting(SentInvitationResponse::id)
				.containsExactly(invD.id(), invC.id(), invB.id(), invA.id());
		invitationService.decline(a, invA.id());
		invitationService.cancel(leader, invB.id());
		invitationService.accept(c, invC.id());

		assertThat(invitationService.sent(leader, team.id())).extracting(SentInvitationResponse::id).containsExactly(invD.id());
		assertApiError(() -> invitationService.sent(c, team.id()), ErrorCode.FORBIDDEN);
		assertApiError(() -> invitationService.sent(member(), team.id()), ErrorCode.FORBIDDEN);
	}

	@Test
	void 받은_초대_목록은_최신순이고_삭제된_팀의_초대는_빠진다() {
		long invitee = member();
		long leader1 = member();
		long leader2 = member();
		long leader3 = member();
		CreatedTeam team1 = newTeam(leader1);
		CreatedTeam team2 = newTeam(leader2);
		CreatedTeam team3 = newTeam(leader3);
		clock.fixAt(Instant.parse("2026-09-01T00:00:00Z"));
		SentInvitationResponse inv1 = invite(leader1, team1, invitee);
		clock.fixAt(Instant.parse("2026-09-02T00:00:00Z"));
		invite(leader2, team2, invitee);
		clock.fixAt(Instant.parse("2026-09-03T00:00:00Z"));
		SentInvitationResponse inv3 = invite(leader3, team3, invitee);
		assertThat(invitationService.received(invitee)).hasSize(3);

		leaveService.delete(leader2, team2.id());

		assertThat(invitationService.received(invitee)).extracting(ReceivedInvitationResponse::id)
				.containsExactly(inv3.id(), inv1.id());
	}

	@Test
	void 초대한_사람으로_보이는_것은_지금의_팀장이다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long successor = joinedMember(team);
		long invitee = member();
		invite(leader, team, invitee);

		leaveService.transferLeadership(leader, team.id(), successor);

		assertThat(invitationService.received(invitee)).extracting(r -> r.inviter().id()).containsExactly(successor);
	}

	// ----- 철회 -----

	@Test
	void 철회하면_초대가_없어지고_상대는_수락할_수_없다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long invitee = member();
		SentInvitationResponse sent = invite(leader, team, invitee);

		invitationService.cancel(leader, sent.id());

		assertThat(invitationRows(team.id(), invitee)).isEmpty();
		assertThat(invitationService.received(invitee)).isEmpty();
		assertThat(invitationService.sent(leader, team.id())).isEmpty();
		assertApiError(() -> invitationService.accept(invitee, sent.id()), ErrorCode.NOT_FOUND);
		assertThat(isActiveMember(team.id(), invitee)).isFalse();
	}

	@Test
	void 팀장이_아니면_초대를_철회할_수_없다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long teammate = joinedMember(team);
		long invitee = member();
		SentInvitationResponse sent = invite(leader, team, invitee);

		assertApiError(() -> invitationService.cancel(teammate, sent.id()), ErrorCode.FORBIDDEN);
		assertApiError(() -> invitationService.cancel(invitee, sent.id()), ErrorCode.FORBIDDEN);
		assertApiError(() -> invitationService.cancel(adminMember(), sent.id()), ErrorCode.FORBIDDEN);

		assertThat(statusOf(sent.id())).isEqualTo("PENDING");
	}

	@Test
	void 이미_응답한_초대와_없는_초대는_철회할_수_없다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long accepter = member();
		long decliner = member();
		SentInvitationResponse accepted = invite(leader, team, accepter);
		invitationService.accept(accepter, accepted.id());
		SentInvitationResponse declined = invite(leader, team, decliner);
		invitationService.decline(decliner, declined.id());

		assertApiError(() -> invitationService.cancel(leader, accepted.id()), ErrorCode.NOT_FOUND);
		assertApiError(() -> invitationService.cancel(leader, declined.id()), ErrorCode.NOT_FOUND);
		assertApiError(() -> invitationService.cancel(leader, 987_654_321L), ErrorCode.NOT_FOUND);

		assertThat(statusOf(accepted.id())).isEqualTo("ACCEPTED");
		assertThat(statusOf(declined.id())).isEqualTo("DECLINED");
	}

	@Test
	void 팀장을_넘기면_새_팀장이_이전_팀장의_초대를_철회한다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long successor = joinedMember(team);
		long invitee = member();
		SentInvitationResponse sent = invite(leader, team, invitee);
		leaveService.transferLeadership(leader, team.id(), successor);

		assertApiError(() -> invitationService.cancel(leader, sent.id()), ErrorCode.FORBIDDEN);
		invitationService.cancel(successor, sent.id());

		assertThat(invitationRows(team.id(), invitee)).isEmpty();
	}

	// ----- 수락 -----

	@Test
	void 수락하면_바로_참가하고_아이디_초대로_들어온_것으로_기록된다() {
		long leader = member();
		CreatedTeam team = newTeam(leader, "수락 팀", null, true);
		long invitee = member();
		SentInvitationResponse sent = invite(leader, team, invitee);

		AcceptInvitationResult result = invitationService.accept(invitee, sent.id());

		assertThat(result.error()).isNull();
		assertThat(result.team().id()).isEqualTo(team.id());
		assertThat(result.team().name()).isEqualTo("수락 팀");
		assertThat(result.team().memberCount()).isEqualTo(2);
		assertThat(result.team().isLeader()).isFalse();
		Map<String, Object> membership = currentMembership(team.id(), invitee);
		assertThat(membership.get("joined_by")).isEqualTo("INVITATION");
		// 코드로 들어오지 않았으니 사용한 초대코드는 없다
		assertThat(membership.get("used_invite_code")).isNull();
		assertThat(statusOf(sent.id())).isEqualTo("ACCEPTED");
		assertThat(responded(sent.id())).isTrue();
		assertThat(firstTeamJoinedAt(invitee)).isNotNull();
		assertThat(invitationService.received(invitee)).isEmpty();
		assertThat(invitationService.sent(leader, team.id())).isEmpty();
	}

	@Test
	void 수락_시점에_이미_초대코드로_참가했으면_초대만_정리하고_중복_소속을_만들지_않는다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long invitee = member();
		SentInvitationResponse sent = invite(leader, team, invitee);
		joinService.joinByCode(invitee, team.inviteCode());

		AcceptInvitationResult result = invitationService.accept(invitee, sent.id());

		assertThat(result.error()).isEqualTo(ErrorCode.INVITATION_ALREADY_JOINED);
		assertThat(result.team()).isNull();
		assertThat(membershipRows(team.id(), invitee)).hasSize(1);
		assertThat(currentMembership(team.id(), invitee).get("joined_by")).isEqualTo("INVITE_CODE");
		assertThat(statusOf(sent.id())).isEqualTo("ACCEPTED");
		assertThat(invitationService.received(invitee)).isEmpty();
	}

	@Test
	void 수락_시점에_팀이_삭제됐으면_초대가_무효가_되고_참가하지_못한다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long invitee = member();
		SentInvitationResponse sent = invite(leader, team, invitee);
		leaveService.delete(leader, team.id());

		AcceptInvitationResult result = invitationService.accept(invitee, sent.id());

		assertThat(result.error()).isEqualTo(ErrorCode.TEAM_GONE);
		assertThat(result.team()).isNull();
		assertThat(statusOf(sent.id())).isEqualTo("DECLINED");
		assertThat(membershipRows(team.id(), invitee)).isEmpty();
	}

	@Test
	void 없는_초대는_수락도_거절도_할_수_없다() {
		long invitee = member();

		assertApiError(() -> invitationService.accept(invitee, 987_654_321L), ErrorCode.NOT_FOUND);
		assertApiError(() -> invitationService.decline(invitee, 987_654_321L), ErrorCode.NOT_FOUND);
	}

	// ----- 거절 -----

	@Test
	void 거절하면_초대가_닫히고_받은_초대_목록에서_사라진다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long invitee = member();
		SentInvitationResponse sent = invite(leader, team, invitee);

		invitationService.decline(invitee, sent.id());

		assertThat(statusOf(sent.id())).isEqualTo("DECLINED");
		assertThat(responded(sent.id())).isTrue();
		assertThat(invitationService.received(invitee)).isEmpty();
		assertThat(invitationService.sent(leader, team.id())).isEmpty();
		assertThat(isActiveMember(team.id(), invitee)).isFalse();
		// 이미 응답한 초대는 다시 응답할 수 없다
		assertApiError(() -> invitationService.accept(invitee, sent.id()), ErrorCode.NOT_FOUND);
		assertApiError(() -> invitationService.decline(invitee, sent.id()), ErrorCode.NOT_FOUND);
	}

	@Test
	void 남의_초대는_수락도_거절도_할_수_없다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long invitee = member();
		long stranger = member();
		SentInvitationResponse sent = invite(leader, team, invitee);

		assertApiError(() -> invitationService.accept(stranger, sent.id()), ErrorCode.NOT_FOUND);
		assertApiError(() -> invitationService.decline(stranger, sent.id()), ErrorCode.NOT_FOUND);
		assertApiError(() -> invitationService.accept(leader, sent.id()), ErrorCode.NOT_FOUND);

		assertThat(statusOf(sent.id())).isEqualTo("PENDING");
		assertThat(isActiveMember(team.id(), stranger)).isFalse();
	}

	// ----- GraphQL -----

	@Test
	void 초대_보내기에서_수락까지_GraphQL로_이어진다() {
		long leader = member();
		CreatedTeam team = newTeam(leader, "그래프큐엘 초대", null, true);
		long invitee = member();
		String teamId = Long.toString(team.id());

		GraphQlResponse invited = gql(leader, TeamGql.INVITE, Map.of("id", teamId, "login", loginOf(invitee)));
		assertGraphQlOk(invited);
		String invitationId = invited.data().path("inviteToTeam").path("id").asText();
		assertThat(invited.data().path("inviteToTeam").path("invitee").path("id").asText()).isEqualTo(Long.toString(invitee));

		JsonNode sent = gql(leader, TeamGql.SENT, Map.of("id", teamId)).data().path("sentTeamInvitations");
		assertThat(sent).hasSize(1);
		assertThat(sent.get(0).path("id").asText()).isEqualTo(invitationId);

		JsonNode received = gql(invitee, TeamGql.RECEIVED).data().path("receivedTeamInvitations");
		assertThat(received).hasSize(1);
		assertThat(received.get(0).path("teamId").asText()).isEqualTo(teamId);
		assertThat(received.get(0).path("teamName").asText()).isEqualTo("그래프큐엘 초대");
		assertThat(received.get(0).path("inviter").path("id").asText()).isEqualTo(Long.toString(leader));

		GraphQlResponse accepted = gql(invitee, TeamGql.ACCEPT, Map.of("id", invitationId));
		assertGraphQlOk(accepted);
		assertThat(accepted.data().path("acceptTeamInvitation").path("memberCount").asInt()).isEqualTo(2);
		assertThat(isActiveMember(team.id(), invitee)).isTrue();
	}

	@Test
	void 초대_오류는_각각_다른_오류_코드로_내려간다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long teammate = joinedMember(team);
		long invitee = member();
		String teamId = Long.toString(team.id());
		gql(leader, TeamGql.INVITE, Map.of("id", teamId, "login", loginOf(invitee)));

		assertGraphQlError(gql(leader, TeamGql.INVITE, Map.of("id", teamId, "login", "nobody-" + members.tag())),
				ErrorCode.INVITEE_NOT_FOUND);
		assertGraphQlError(gql(leader, TeamGql.INVITE, Map.of("id", teamId, "login", loginOf(teammate))),
				ErrorCode.INVITEE_ALREADY_MEMBER);
		assertGraphQlError(gql(leader, TeamGql.INVITE, Map.of("id", teamId, "login", loginOf(invitee))),
				ErrorCode.INVITATION_DUPLICATE);
		assertGraphQlError(gql(teammate, TeamGql.INVITE, Map.of("id", teamId, "login", loginOf(invitee))), ErrorCode.FORBIDDEN);
	}

	@Test
	void 팀이_삭제된_뒤_수락하면_오류를_내려주면서도_초대_정리는_커밋된다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long invitee = member();
		SentInvitationResponse sent = invite(leader, team, invitee);
		leaveService.delete(leader, team.id());

		GraphQlResponse response = gql(invitee, TeamGql.ACCEPT, Map.of("id", Long.toString(sent.id())));

		assertGraphQlError(response, ErrorCode.TEAM_GONE);
		assertThat(statusOf(sent.id())).isEqualTo("DECLINED");
		assertThat(membershipRows(team.id(), invitee)).isEmpty();
	}

	@Test
	void 이미_참가한_뒤_수락하면_오류를_내려주면서도_초대_정리는_커밋된다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long invitee = member();
		SentInvitationResponse sent = invite(leader, team, invitee);
		joinService.joinByCode(invitee, team.inviteCode());

		GraphQlResponse response = gql(invitee, TeamGql.ACCEPT, Map.of("id", Long.toString(sent.id())));

		assertGraphQlError(response, ErrorCode.INVITATION_ALREADY_JOINED);
		assertThat(statusOf(sent.id())).isEqualTo("ACCEPTED");
		assertThat(membershipRows(team.id(), invitee)).hasSize(1);
	}

	@Test
	void 거절과_철회는_GraphQL로도_동작한다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long decliner = member();
		long cancelled = member();
		SentInvitationResponse toDecline = invite(leader, team, decliner);
		SentInvitationResponse toCancel = invite(leader, team, cancelled);

		GraphQlResponse decline = gql(decliner, TeamGql.DECLINE, Map.of("id", Long.toString(toDecline.id())));
		GraphQlResponse cancel = gql(leader, TeamGql.CANCEL, Map.of("id", Long.toString(toCancel.id())));

		assertGraphQlOk(decline);
		assertThat(decline.data().path("declineTeamInvitation").asBoolean()).isTrue();
		assertGraphQlOk(cancel);
		assertThat(cancel.data().path("cancelTeamInvitation").asBoolean()).isTrue();
		assertThat(statusOf(toDecline.id())).isEqualTo("DECLINED");
		assertThat(invitationRows(team.id(), cancelled)).isEmpty();
	}

}
