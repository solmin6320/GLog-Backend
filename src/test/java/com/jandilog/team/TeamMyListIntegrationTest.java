package com.jandilog.team;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.jandilog.team.support.TeamGql;
import com.jandilog.team.support.TeamIntegrationTest;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;

// 팀을 만든 직후 내 팀 목록(myTeams)에 보이는지 (화면설계서 TM-01, 기능명세서 2장).
// 화면이 부르는 모양대로 GraphQL 요청 두 번(createTeam → myTeams, 같은 토큰)으로 확인한다
class TeamMyListIntegrationTest extends TeamIntegrationTest {

	private static final String SUMMARY = "weekSummary { weekStart passCount failCount notYetCount }";
	private static final String MY_TEAMS_WITH_SUMMARY = "{ myTeams { " + TeamGql.TEAM_FIELDS + " " + SUMMARY + " } }";

	// GraphQL로 팀을 만들고 id를 돌려준다
	private long create(long creator, String name, Boolean isPublic) {
		Map<String, Object> input = isPublic == null ? Map.of("name", name) : Map.of("name", name, "isPublic", isPublic);
		GraphQlResponse created = gql(creator, TeamGql.CREATE, Map.of("input", input));
		assertGraphQlOk(created);
		long teamId = created.data().path("createTeam").path("team").path("id").asLong();
		trackTeam(teamId);
		return teamId;
	}

	private static List<Long> ids(JsonNode myTeams) {
		List<Long> ids = new ArrayList<>();
		myTeams.forEach(team -> ids.add(team.path("id").asLong()));
		return ids;
	}

	@Test
	void 팀을_만들면_바로_내_팀_목록에_팀장_팀으로_나온다() {
		long creator = member();

		long teamId = create(creator, "목록-" + members.tag(), true);

		GraphQlResponse response = gql(creator, TeamGql.MY_TEAMS);
		assertGraphQlOk(response);
		JsonNode teams = response.data().path("myTeams");
		assertThat(ids(teams)).containsExactly(teamId);
		JsonNode team = teams.get(0);
		assertThat(team.path("isLeader").asBoolean()).isTrue();
		assertThat(team.path("memberCount").asInt()).isEqualTo(1);
		assertThat(team.path("leader").path("id").asLong()).isEqualTo(creator);
		assertThat(team.path("joinedAt").asText()).isNotEmpty();
	}

	@Test
	void 이번_주_요약을_같이_불러도_방금_만든_팀이_목록에_나온다() {
		long creator = member();

		long teamId = create(creator, "요약목록-" + members.tag(), true);

		GraphQlResponse response = gql(creator, MY_TEAMS_WITH_SUMMARY);
		assertGraphQlOk(response);
		assertThat(ids(response.data().path("myTeams"))).containsExactly(teamId);
		assertThat(response.data().path("myTeams").get(0).path("weekSummary").path("weekStart").asText()).isNotEmpty();
	}

	@Test
	void 비공개로_만든_팀도_내_팀_목록에는_실제_이름으로_나온다() {
		long creator = member();
		String name = "비공개목록-" + members.tag();

		long teamId = create(creator, name, false);

		JsonNode teams = gql(creator, MY_TEAMS_WITH_SUMMARY).data().path("myTeams");
		assertThat(ids(teams)).containsExactly(teamId);
		assertThat(teams.get(0).path("name").asText()).isEqualTo(name);
		assertThat(teams.get(0).path("isPublic").asBoolean()).isFalse();
	}

	@Test
	void 다른_회원의_목록에는_안_나오고_초대코드로_참가하면_나온다() {
		long creator = member();
		long other = member();
		CreatedTeam team = newTeam(creator);

		assertThat(ids(gql(other, TeamGql.MY_TEAMS).data().path("myTeams"))).isEmpty();
		assertThat(ids(gql(creator, TeamGql.MY_TEAMS).data().path("myTeams"))).containsExactly(team.id());

		joinService.joinByCode(other, team.inviteCode());

		assertThat(ids(gql(other, TeamGql.MY_TEAMS).data().path("myTeams"))).containsExactly(team.id());
		assertThat(gql(other, TeamGql.MY_TEAMS).data().path("myTeams").get(0).path("isLeader").asBoolean()).isFalse();
		assertThat(gql(creator, TeamGql.MY_TEAMS).data().path("myTeams").get(0).path("memberCount").asInt()).isEqualTo(2);
	}

	@Test
	void 연속으로_만든_팀은_모두_만든_순서대로_나온다() {
		long creator = member();

		long first = create(creator, "첫째-" + members.tag(), true);
		long second = create(creator, "둘째-" + members.tag(), false);
		long third = create(creator, "셋째-" + members.tag(), null);

		JsonNode teams = gql(creator, MY_TEAMS_WITH_SUMMARY).data().path("myTeams");
		assertThat(ids(teams)).containsExactly(first, second, third);
	}

}
