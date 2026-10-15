package com.jandilog.team;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.team.dto.ReceivedInvitationResponse;
import com.jandilog.team.dto.TeamMemberResponse;
import com.jandilog.team.dto.TeamResponse;
import com.jandilog.team.dto.UpdateTeamInput;
import com.jandilog.team.support.TeamGql;
import com.jandilog.team.support.TeamIntegrationTest;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;

// 팀 정보 수정과 공개 설정에 따른 표시 (기능명세서 2장 "공개 설정" 표, 화면설계서 TM-06 ⑦, E-23)
class TeamVisibilityIntegrationTest extends TeamIntegrationTest {

	private static final String POST = "query($id: ID!) { post(id: $id) { id teamId teamName } }";

	// ----- 정보 수정 -----

	@Test
	void 보낸_항목만_바꾸고_나머지는_그대로_둔다() {
		long leader = member();
		CreatedTeam team = newTeam(leader, "원래 이름", "원래 소개", true);

		TeamResponse renamed = teamService.update(leader, team.id(), new UpdateTeamInput("바뀐 이름", null, null));
		assertThat(renamed.name()).isEqualTo("바뀐 이름");
		assertThat(renamed.description()).isEqualTo("원래 소개");
		assertThat(renamed.isPublic()).isTrue();

		TeamResponse described = teamService.update(leader, team.id(), new UpdateTeamInput(null, "바뀐 소개", null));
		assertThat(described.name()).isEqualTo("바뀐 이름");
		assertThat(described.description()).isEqualTo("바뀐 소개");

		TeamResponse hidden = teamService.update(leader, team.id(), new UpdateTeamInput(null, null, false));
		assertThat(hidden.isPublic()).isFalse();
		assertThat(hidden.name()).isEqualTo("바뀐 이름");
		assertThat(hidden.description()).isEqualTo("바뀐 소개");

		Map<String, Object> row = teamRow(team.id());
		assertThat(row.get("name")).isEqualTo("바뀐 이름");
		assertThat(row.get("description")).isEqualTo("바뀐 소개");
		assertThat(row.get("is_public")).isIn(false, 0);
	}

	@Test
	void 소개를_빈_문자열로_보내면_비워진다() {
		long leader = member();
		CreatedTeam team = newTeam(leader, "소개 지우기", "지울 소개", true);

		TeamResponse response = teamService.update(leader, team.id(), new UpdateTeamInput(null, "", null));

		assertThat(response.description()).isNull();
		assertThat(teamRow(team.id()).get("description")).isNull();
	}

	@Test
	void 잘못된_이름과_소개는_거부하고_아무것도_바꾸지_않는다() {
		long leader = member();
		CreatedTeam team = newTeam(leader, "그대로", "소개", true);

		assertApiError(() -> teamService.update(leader, team.id(), new UpdateTeamInput("   ", "새 소개", false)),
				ErrorCode.TEAM_NAME_REQUIRED);
		assertApiError(() -> teamService.update(leader, team.id(), new UpdateTeamInput("가".repeat(21), null, null)),
				ErrorCode.INVALID_INPUT);
		assertApiError(() -> teamService.update(leader, team.id(), new UpdateTeamInput(null, "소".repeat(101), null)),
				ErrorCode.INVALID_INPUT);

		Map<String, Object> row = teamRow(team.id());
		assertThat(row.get("name")).isEqualTo("그대로");
		assertThat(row.get("description")).isEqualTo("소개");
		assertThat(row.get("is_public")).isIn(true, 1);
	}

	@Test
	void 수정해도_초대코드와_팀장과_소속은_바뀌지_않는다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long teammate = joinedMember(team);

		teamService.update(leader, team.id(), new UpdateTeamInput("새 이름", "새 소개", false));

		Map<String, Object> row = teamRow(team.id());
		assertThat(row.get("invite_code")).isEqualTo(team.inviteCode());
		assertThat(((Number) row.get("leader_id")).longValue()).isEqualTo(leader);
		assertThat(activeMemberCount(team.id())).isEqualTo(2);
		assertThat(isActiveMember(team.id(), teammate)).isTrue();
	}

	@Test
	void updateTeam은_GraphQL로_보낸_항목만_바꾼다() {
		long leader = member();
		CreatedTeam team = newTeam(leader, "원래 이름", "원래 소개", true);
		String id = Long.toString(team.id());

		GraphQlResponse onlyPrivate = gql(leader, TeamGql.UPDATE, Map.of("id", id, "input", Map.of("isPublic", false)));
		assertGraphQlOk(onlyPrivate);
		assertThat(onlyPrivate.data().path("updateTeam").path("isPublic").asBoolean()).isFalse();
		assertThat(onlyPrivate.data().path("updateTeam").path("name").asText()).isEqualTo("원래 이름");
		assertThat(onlyPrivate.data().path("updateTeam").path("description").asText()).isEqualTo("원래 소개");

		GraphQlResponse cleared = gql(leader, TeamGql.UPDATE, Map.of("id", id, "input", Map.of("description", "")));
		assertGraphQlOk(cleared);
		assertThat(cleared.data().path("updateTeam").path("description").isNull()).isTrue();
		assertThat(cleared.data().path("updateTeam").path("isPublic").asBoolean()).isFalse();

		assertGraphQlError(gql(leader, TeamGql.UPDATE, Map.of("id", id, "input", Map.of("name", ""))),
				ErrorCode.TEAM_NAME_REQUIRED);
	}

	// ----- 소속 팀원에게는 항상 실제 이름 -----

	@Test
	void 비공개_팀도_소속_팀원과_초대받은_사람에게는_실제_이름이_보인다() {
		long leader = member();
		CreatedTeam team = newTeam(leader, "비공개여도 보이는 이름", null, false);
		long teammate = joinedMember(team);
		long invitee = member();
		invitationService.invite(leader, team.id(), loginOf(invitee));

		assertThat(teamService.get(teammate, team.id()).name()).isEqualTo("비공개여도 보이는 이름");
		assertThat(teamService.get(leader, team.id()).name()).isEqualTo("비공개여도 보이는 이름");
		assertThat(teamService.myTeams(teammate)).extracting(TeamResponse::name).containsExactly("비공개여도 보이는 이름");
		assertThat(teamService.members(teammate, team.id())).extracting(TeamMemberResponse::id).containsExactly(leader, teammate);
		assertThat(invitationService.received(invitee)).extracting(ReceivedInvitationResponse::teamName)
				.containsExactly("비공개여도 보이는 이름");
	}

	// ----- 글에 붙는 팀 이름 (E-23) -----

	@Test
	void 공개_팀만_글에_이름이_붙고_비공개와_삭제된_팀은_숨긴다() {
		long leader = member();
		CreatedTeam open = newTeam(leader, "공개 팀", null, true);
		CreatedTeam closed = newTeam(leader, "비공개 팀", null, false);
		CreatedTeam gone = newTeam(leader, "삭제될 팀", null, true);
		leaveService.delete(leader, gone.id());

		Map<Long, String> names = nameService.visibleNames(List.of(open.id(), closed.id(), gone.id(), 987_654_321L));

		assertThat(names).containsOnlyKeys(open.id()).containsEntry(open.id(), "공개 팀");
		assertThat(nameService.visibleNames(List.of())).isEmpty();
	}

	@Test
	void 공개_설정을_바꾸면_글의_팀_이름_노출이_바로_따라간다() {
		long leader = member();
		CreatedTeam team = newTeam(leader, "토글 팀", null, true);
		assertThat(nameService.visibleNames(List.of(team.id()))).containsEntry(team.id(), "토글 팀");

		teamService.update(leader, team.id(), new UpdateTeamInput(null, null, false));
		assertThat(nameService.visibleNames(List.of(team.id()))).isEmpty();

		teamService.update(leader, team.id(), new UpdateTeamInput("토글 팀 2", null, true));
		assertThat(nameService.visibleNames(List.of(team.id()))).containsEntry(team.id(), "토글 팀 2");
	}

	@Test
	void 글의_팀_이름은_열람자와_무관하게_공개_팀만_보이고_팀원에게도_비공개_팀은_숨긴다() {
		long leader = member();
		long outsider = member();
		long admin = adminMember();
		CreatedTeam open = newTeam(leader, "공개 팀", null, true);
		CreatedTeam closed = newTeam(leader, "비공개 팀", null, false);
		String openPost = newPost(leader, open);
		String closedPost = newPost(leader, closed);
		String plainPost = newPost(leader, null);

		for (long viewer : new long[] {leader, outsider, admin}) {
			assertThat(teamNameOf(viewer, openPost)).as("공개 팀 글 열람자 %d", viewer).isEqualTo("공개 팀");
			assertThat(teamNameOf(viewer, closedPost)).as("비공개 팀 글 열람자 %d", viewer).isNull();
			assertThat(teamNameOf(viewer, plainPost)).isNull();
		}
		// 비공개여도 팀 id 연결 자체는 글에 남는다 (이름만 숨김)
		GraphQlResponse closedResponse = gql(outsider, POST, Map.of("id", closedPost));
		assertThat(closedResponse.data().path("post").path("teamId").asText()).isEqualTo(Long.toString(closed.id()));
		assertThat(closedResponse.rawBody()).doesNotContain("비공개 팀");
	}

	@Test
	void 한_요청에_여러_글의_팀_이름을_섞어_물어도_각각_맞게_채워진다() {
		long leader = member();
		CreatedTeam open = newTeam(leader, "공개 팀", null, true);
		CreatedTeam closed = newTeam(leader, "비공개 팀", null, false);
		String openPost = newPost(leader, open);
		String closedPost = newPost(leader, closed);
		String plainPost = newPost(leader, null);
		String query = "{ a: post(id: \"" + openPost + "\") { teamName } b: post(id: \"" + closedPost
				+ "\") { teamName } c: post(id: \"" + plainPost + "\") { teamName } }";

		GraphQlResponse response = gql(member(), query);

		assertGraphQlOk(response);
		assertThat(response.data().path("a").path("teamName").asText()).isEqualTo("공개 팀");
		assertThat(response.data().path("b").path("teamName").isNull()).isTrue();
		assertThat(response.data().path("c").path("teamName").isNull()).isTrue();
	}

	private String teamNameOf(long viewer, String postId) {
		GraphQlResponse response = gql(viewer, POST, Map.of("id", postId));
		assertGraphQlOk(response);
		JsonNode name = response.data().path("post").path("teamName");
		return name.isNull() ? null : name.asText();
	}

}
