package com.jandilog.team.board;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import com.fasterxml.jackson.databind.JsonNode;
import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.member.domain.MemberStatus;
import com.jandilog.post.domain.Post;
import com.jandilog.post.domain.PostType;
import com.jandilog.team.dto.UpdateTeamInput;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;

// 팀 현황판(TM-05)·내 팀 목록 이번 주 요약의 접근 제어 경계 (기능명세서 2장, 화면설계서 E-01·E-02·E-03·E-09·E-53, EX-TM05-01).
// 보는 사람 상태(비로그인·승인 대기·거절)와 소속 상태(비소속·추방·탈퇴·팀 삭제)를 나눠서 본다
class TeamBoardAccessIntegrationTest extends TeamBoardTestBase {

	private static final String TEAM_SUMMARY_QUERY = """
			query($id: ID!) { team(id: $id) { id weekSummary { passCount failCount notYetCount } } }
			""";

	private long leader;
	private long mate;
	private long outsider;
	private TeamRef publicTeam;
	private String postTitle;

	@BeforeEach
	void setUpScenario() {
		at("2026-12-09T12:07:13");
		leader = member();
		mate = member();
		outsider = member();
		publicTeam = createTeam(leader, true);
		join(mate, publicTeam);
		settleFirstWeek(leader, mate, outsider);
		postTitle = "접근제어-" + members.tag();
		postAt("2026-12-09T12:08:14", mate, PostType.DEVLOG, postTitle, true, publicTeam.id());
	}

	// ----- 보는 사람의 계정 상태 -----

	@Test
	void 비로그인은_현황판을_볼_수_없다() {
		GraphQlResponse response = boardResponse(null, Long.toString(publicTeam.id()));

		assertDenied(response, ErrorCode.UNAUTHENTICATED);
	}

	@Test
	void 승인_대기_계정은_팀_id를_맞게_넣어도_현황판을_볼_수_없다() {
		GraphQlResponse response = boardResponse(pending(), publicTeam.id());

		assertDenied(response, ErrorCode.ACCOUNT_PENDING);
	}

	@Test
	void 거절_계정은_현황판을_볼_수_없다() {
		GraphQlResponse response = boardResponse(rejected(), publicTeam.id());

		assertDenied(response, ErrorCode.ACCOUNT_REJECTED);
	}

	@Test
	void 팀원이던_회원이_승인_대기나_거절로_바뀌면_바로_막히고_다시_승인되면_다시_본다() {
		members.setStatus(mate, MemberStatus.PENDING);
		assertDenied(boardResponse(mate, publicTeam.id()), ErrorCode.ACCOUNT_PENDING);

		members.setStatus(mate, MemberStatus.REJECTED);
		assertDenied(boardResponse(mate, publicTeam.id()), ErrorCode.ACCOUNT_REJECTED);

		// 소속 행은 그대로라 승인 상태가 돌아오면 같은 토큰으로 다시 열린다
		members.setStatus(mate, MemberStatus.ACTIVE);
		assertThat(memberIdsOf(board(mate, publicTeam.id()))).contains(mate);
	}

	@Test
	void 승인_대기와_거절_계정은_내_팀_목록과_팀_조회의_이번_주_요약도_받지_못한다() {
		long pendingId = pending();
		long rejectedId = rejected();

		assertDenied(graphQl.post(null, MY_TEAMS_QUERY), ErrorCode.UNAUTHENTICATED);
		assertDenied(graphQl.post(bearerFor(pendingId), MY_TEAMS_QUERY), ErrorCode.ACCOUNT_PENDING);
		assertDenied(graphQl.post(bearerFor(rejectedId), MY_TEAMS_QUERY), ErrorCode.ACCOUNT_REJECTED);
		Map<String, Object> id = Map.of("id", Long.toString(publicTeam.id()));
		assertDenied(graphQl.post(null, TEAM_SUMMARY_QUERY, id), ErrorCode.UNAUTHENTICATED);
		assertDenied(graphQl.post(bearerFor(pendingId), TEAM_SUMMARY_QUERY, id), ErrorCode.ACCOUNT_PENDING);
		assertDenied(graphQl.post(bearerFor(rejectedId), TEAM_SUMMARY_QUERY, id), ErrorCode.ACCOUNT_REJECTED);
	}

	// ----- 비소속·관리자 -----

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void 비소속_회원은_공개_팀이든_비공개_팀이든_현황판을_볼_수_없고_팀_내용이_새지_않는다(boolean isPublic) {
		TeamRef team = isPublic ? publicTeam : createTeam(leader, false);
		if (!isPublic) {
			join(mate, team);
			postAt("2026-12-09T12:09:15", mate, PostType.DEVLOG, postTitle + "-비공개", true, team.id());
		}

		GraphQlResponse response = boardResponse(outsider, team.id());

		assertDenied(response, ErrorCode.FORBIDDEN);
		assertThat(response.rawBody()).doesNotContain(postTitle).doesNotContain(loginOf(leader))
				.doesNotContain(loginOf(mate));
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void 관리자도_소속이_아니면_공개든_비공개든_현황판을_볼_수_없다(boolean isPublic) {
		TeamRef team = isPublic ? publicTeam : createTeam(leader, false);
		long admin = admin();

		assertDenied(boardResponse(admin, team.id()), ErrorCode.FORBIDDEN);
		assertDenied(graphQl.post(bearerFor(admin), TEAM_SUMMARY_QUERY, Map.of("id", Long.toString(team.id()))),
				ErrorCode.FORBIDDEN);
	}

	@Test
	void 관리자도_팀원이면_일반_팀원처럼_현황판을_본다() {
		long admin = admin();
		join(admin, publicTeam);

		JsonNode board = board(admin, publicTeam.id());

		assertThat(memberIdsOf(board)).containsExactly(leader, mate, admin);
		assertThat(rowOf(board, admin).path("isMe").asBoolean()).isTrue();
	}

	@Test
	void 한_팀의_팀원이어도_소속이_아닌_다른_팀의_현황판은_막힌다() {
		long otherLeader = member();
		TeamRef other = createTeam(otherLeader, true);
		settleFirstWeek(otherLeader);

		assertThat(memberIdsOf(board(mate, publicTeam.id()))).contains(mate);
		assertDenied(boardResponse(mate, other.id()), ErrorCode.FORBIDDEN);
		assertThat(memberIdsOf(board(otherLeader, other.id()))).containsExactly(otherLeader);
	}

	@Test
	void 비소속_회원은_팀_조회로도_이번_주_요약을_받지_못한다() {
		Map<String, Object> id = Map.of("id", Long.toString(publicTeam.id()));

		assertDenied(graphQl.post(bearerFor(outsider), TEAM_SUMMARY_QUERY, id), ErrorCode.FORBIDDEN);
		GraphQlResponse member = graphQl.post(bearerFor(mate), TEAM_SUMMARY_QUERY, id);
		assertThat(member.hasErrors()).as(member.rawBody()).isFalse();
		assertThat(member.data().path("team").path("weekSummary").isObject()).isTrue();
	}

	@Test
	void 팀장이_공개_설정을_바꿔도_비소속은_계속_막히고_팀원은_계속_본다() {
		teamService.update(leader, publicTeam.id(), new UpdateTeamInput(false));
		assertDenied(boardResponse(outsider, publicTeam.id()), ErrorCode.FORBIDDEN);
		assertThat(memberIdsOf(board(mate, publicTeam.id()))).containsExactly(leader, mate);

		teamService.update(leader, publicTeam.id(), new UpdateTeamInput(true));
		assertDenied(boardResponse(outsider, publicTeam.id()), ErrorCode.FORBIDDEN);
		assertThat(memberIdsOf(board(mate, publicTeam.id()))).containsExactly(leader, mate);
	}

	// ----- 추방·탈퇴 -----

	@Test
	void 추방된_회원은_바로_막히고_남은_팀원의_현황판에서도_빠진다() {
		leaveService.kick(leader, publicTeam.id(), mate);

		assertDenied(boardResponse(mate, publicTeam.id()), ErrorCode.FORBIDDEN);
		JsonNode afterKick = board(leader, publicTeam.id());
		assertThat(memberIdsOf(afterKick)).containsExactly(leader);
		// 추방자가 쓴 글은 이 팀에 연결된 채 남는다
		assertThat(titlesOf(afterKick)).containsExactly(postTitle);
		assertThat(myTeams(mate)).isEmpty();
	}

	@Test
	void 자진_탈퇴한_회원은_막히고_초대코드로_다시_참가하면_다시_본다() {
		leaveService.leave(mate, publicTeam.id());

		assertDenied(boardResponse(mate, publicTeam.id()), ErrorCode.FORBIDDEN);
		assertThat(memberIdsOf(board(leader, publicTeam.id()))).containsExactly(leader);

		join(mate, publicTeam);

		assertThat(memberIdsOf(board(mate, publicTeam.id()))).containsExactly(leader, mate);
		assertThat(rowOf(board(mate, publicTeam.id()), mate).path("isMe").asBoolean()).isTrue();
	}

	@Test
	void 추방된_회원은_초대코드로는_돌아오지_못하고_아이디_초대로_돌아오면_다시_본다() {
		leaveService.kick(leader, publicTeam.id(), mate);

		assertThatThrownBy(() -> join(mate, publicTeam)).isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.TEAM_JOIN_BLOCKED);
		assertDenied(boardResponse(mate, publicTeam.id()), ErrorCode.FORBIDDEN);

		long invitationId = invitationService.invite(leader, publicTeam.id(), loginOf(mate)).id();
		invitationService.accept(mate, invitationId);

		assertThat(memberIdsOf(board(mate, publicTeam.id()))).containsExactly(leader, mate);
	}

	// ----- 팀 삭제·없는 팀 -----

	@Test
	void 팀이_삭제되면_팀장과_전_팀원과_비소속_모두_없는_팀으로_본다() {
		long kicked = member();
		join(kicked, publicTeam);
		leaveService.kick(leader, publicTeam.id(), kicked);
		long admin = admin();

		leaveService.delete(leader, publicTeam.id());

		for (long viewer : new long[] {leader, mate, kicked, outsider, admin}) {
			assertDenied(boardResponse(viewer, publicTeam.id()), ErrorCode.NOT_FOUND);
		}
		// 삭제된 팀은 내 팀 목록에서도 사라진다
		assertThat(myTeams(leader)).isEmpty();
		assertThat(myTeams(mate)).isEmpty();
		// 글은 남는다
		assertThat(mongo.count(Query.query(Criteria.where("title").is(postTitle)), Post.class)).isEqualTo(1);
	}

	@ParameterizedTest
	@ValueSource(strings = {"999999999999", "0", "-3", "abc", "", "  ", "1.5", "12abc", "99999999999999999999999"})
	void 없는_팀_id와_숫자가_아닌_id는_모두_없는_팀이다(String teamId) {
		GraphQlResponse response = boardResponse(bearerFor(leader), teamId);

		assertDenied(response, ErrorCode.NOT_FOUND);
	}

}
