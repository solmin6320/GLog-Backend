package com.jandilog.team;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.team.dto.CreateTeamInput;
import com.jandilog.team.dto.CreateTeamPayload;
import com.jandilog.team.dto.TeamMemberResponse;
import com.jandilog.team.dto.TeamResponse;
import com.jandilog.team.dto.UpdateTeamInput;
import com.jandilog.team.support.TeamGql;
import com.jandilog.team.support.TeamIntegrationTest;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;

// 팀 생성과 초대코드 (기능명세서 2장 "팀 생성", 화면설계서 TM-02, DB명세서 1-2·1-3, Q-01)
class TeamCreateIntegrationTest extends TeamIntegrationTest {

	private static final String CODE_FORMAT = "[A-HJ-NP-Z2-9]{10}";

	@Test
	void 팀을_만들면_만든_사람이_팀장이자_첫_팀원이다() {
		long leader = member();

		CreateTeamPayload payload = teamService.create(leader, new CreateTeamInput("잔디 팀", "소개", true));
		trackTeam(payload.team().id());
		long teamId = payload.team().id();

		Map<String, Object> row = teamRow(teamId);
		assertThat(((Number) row.get("leader_id")).longValue()).isEqualTo(leader);
		assertThat(row.get("name")).isEqualTo("잔디 팀");
		assertThat(row.get("description")).isEqualTo("소개");
		assertThat(row.get("deleted_at")).isNull();
		assertThat(row.get("created_at")).isNotNull();
		// 팀장도 소속 행이 있어야 판정 소속과 팀원 수에 잡힌다
		assertThat(isActiveMember(teamId, leader)).isTrue();
		assertThat(activeMemberCount(teamId)).isEqualTo(1);
		TeamResponse team = payload.team();
		assertThat(team.isLeader()).isTrue();
		assertThat(team.memberCount()).isEqualTo(1);
		assertThat(team.leader().id()).isEqualTo(leader);
		assertThat(team.joinedAt()).isNotNull();
		assertThat(team.createdAt()).isNotNull();
	}

	@Test
	void 초대코드는_생성_때_한_번_발급되고_평문으로_저장된다() {
		long leader = member();

		CreatedTeam team = newTeam(leader);

		assertThat(team.inviteCode()).matches(CODE_FORMAT);
		assertThat(teamRow(team.id()).get("invite_code")).isEqualTo(team.inviteCode());
	}

	@Test
	void 팀마다_초대코드가_다르다() {
		long leader = member();
		Set<String> codes = new HashSet<>();

		for (int i = 0; i < 15; i++) {
			codes.add(newTeam(leader).inviteCode());
		}

		assertThat(codes).hasSize(15);
	}

	@Test
	void 팀장은_초대코드를_언제든_다시_확인하고_코드는_바뀌지_않는다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);

		String first = teamService.inviteCode(leader, team.id());
		// 참가자·정보 수정이 있어도 처음 발급된 코드 그대로다 (재발급 없음)
		joinedMember(team);
		teamService.update(leader, team.id(), new UpdateTeamInput("새 이름", null, false));
		String second = teamService.inviteCode(leader, team.id());

		assertThat(first).isEqualTo(team.inviteCode());
		assertThat(second).isEqualTo(team.inviteCode());
		assertThat(teamRow(team.id()).get("invite_code")).isEqualTo(team.inviteCode());
	}

	@Test
	void 팀장의_소속_행에도_사용한_초대코드가_남는다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);

		assertThat(currentMembership(team.id(), leader).get("used_invite_code")).isEqualTo(team.inviteCode());
	}

	@Test
	void 공개_설정을_생략하면_공개로_만들어진다() {
		long leader = member();

		CreateTeamPayload payload = teamService.create(leader, new CreateTeamInput("기본 공개", null, null));
		trackTeam(payload.team().id());

		assertThat(payload.team().isPublic()).isTrue();
		assertThat(teamRow(payload.team().id()).get("is_public")).isIn(true, 1);
	}

	@Test
	void 비공개로_만들면_비공개로_저장된다() {
		long leader = member();

		CreatedTeam team = newTeam(leader, "비공개 팀", null, false);

		assertThat(teamRow(team.id()).get("is_public")).isIn(false, 0);
		// 소속 팀원이 보는 화면이라 비공개여도 실제 이름이다
		assertThat(teamService.get(leader, team.id()).name()).isEqualTo("비공개 팀");
		assertThat(teamService.get(leader, team.id()).isPublic()).isFalse();
	}

	@Test
	void 팀_이름_앞뒤_공백은_지우고_소개가_비면_null로_저장한다() {
		long leader = member();

		CreatedTeam team = newTeam(leader, "  공백 팀  ", "   ", true);

		assertThat(teamRow(team.id()).get("name")).isEqualTo("공백 팀");
		assertThat(teamRow(team.id()).get("description")).isNull();
	}

	@Test
	void 팀_이름이_비었으면_만들지_않는다() {
		long leader = member();

		assertApiError(() -> teamService.create(leader, new CreateTeamInput("   ", null, true)), ErrorCode.TEAM_NAME_REQUIRED);
		assertApiError(() -> teamService.create(leader, new CreateTeamInput(null, null, true)), ErrorCode.TEAM_NAME_REQUIRED);

		assertThat(teamsLedBy(leader)).isZero();
	}

	@Test
	void 팀_이름이_20자를_넘으면_만들지_않는다() {
		long leader = member();

		CreatedTeam exact = newTeam(leader, "가".repeat(20), null, true);
		assertApiError(() -> teamService.create(leader, new CreateTeamInput("가".repeat(21), null, true)),
				ErrorCode.INVALID_INPUT);

		assertThat(teamRow(exact.id()).get("name")).isEqualTo("가".repeat(20));
		assertThat(teamsLedBy(leader)).isEqualTo(1);
	}

	@Test
	void 팀_소개가_100자를_넘으면_만들지_않는다() {
		long leader = member();

		assertApiError(() -> teamService.create(leader, new CreateTeamInput("소개 초과", "소".repeat(101), true)),
				ErrorCode.INVALID_INPUT);

		assertThat(teamsLedBy(leader)).isZero();
		CreatedTeam ok = newTeam(leader, "소개 경계", "소".repeat(100), true);
		assertThat(teamRow(ok.id()).get("description")).isEqualTo("소".repeat(100));
	}

	@Test
	void 팀을_만들어도_처음_참가한_시각은_계정에_한_번만_기록된다() {
		long leader = member();
		clock.fixAt(Instant.parse("2026-09-01T00:00:00Z"));

		newTeam(leader);
		LocalDateTime first = firstTeamJoinedAt(leader);
		clock.fixAt(Instant.parse("2026-09-20T00:00:00Z"));
		newTeam(leader);

		assertThat(first).isEqualTo(LocalDateTime.of(2026, 9, 1, 9, 0, 0));
		assertThat(firstTeamJoinedAt(leader)).isEqualTo(first);
	}

	@Test
	void 한_사람이_여러_팀을_만들고_참가해도_제한이_없고_참가한_순서로_나온다() {
		long leader = member();
		long other = member();
		clock.fixAt(Instant.parse("2026-09-01T00:00:00Z"));
		CreatedTeam mine1 = newTeam(leader, "내 팀 1", null, true);
		clock.fixAt(Instant.parse("2026-09-02T00:00:00Z"));
		CreatedTeam theirs = newTeam(other, "남의 팀", null, true);
		clock.fixAt(Instant.parse("2026-09-03T00:00:00Z"));
		joinService.joinByCode(leader, theirs.inviteCode());
		clock.fixAt(Instant.parse("2026-09-04T00:00:00Z"));
		CreatedTeam mine2 = newTeam(leader, "내 팀 2", null, false);
		clock.fixAt(Instant.parse("2026-09-05T00:00:00Z"));
		CreatedTeam mine3 = newTeam(leader, "내 팀 3", null, true);

		List<TeamResponse> teams = teamService.myTeams(leader);

		assertThat(teams).extracting(TeamResponse::id).containsExactly(mine1.id(), theirs.id(), mine2.id(), mine3.id());
		assertThat(teams).extracting(TeamResponse::isLeader).containsExactly(true, false, true, true);
		// 비공개 팀도 본인에게는 실제 이름
		assertThat(teams).extracting(TeamResponse::name).containsExactly("내 팀 1", "남의 팀", "내 팀 2", "내 팀 3");
		assertThat(teams.get(1).memberCount()).isEqualTo(2);
	}

	@Test
	void 팀이_없는_회원의_내_팀_목록은_비어_있다() {
		assertThat(teamService.myTeams(member())).isEmpty();
	}

	@Test
	void 팀원_목록은_팀장이_맨_앞이고_나머지는_참가한_순서다() {
		long leader = member();
		clock.fixAt(Instant.parse("2026-09-01T00:00:00Z"));
		CreatedTeam team = newTeam(leader);
		clock.fixAt(Instant.parse("2026-09-02T00:00:00Z"));
		long second = joinedMember(team);
		clock.fixAt(Instant.parse("2026-09-03T00:00:00Z"));
		long third = joinedMember(team);

		List<TeamMemberResponse> members = teamService.members(second, team.id());

		assertThat(members).extracting(TeamMemberResponse::id).containsExactly(leader, second, third);
		assertThat(members).extracting(TeamMemberResponse::isLeader).containsExactly(true, false, false);
	}

	// ----- GraphQL -----

	@Test
	void createTeam은_팀과_초대코드를_돌려준다() {
		long leader = member();

		GraphQlResponse response = gql(leader, TeamGql.CREATE,
				Map.of("input", Map.of("name", "그래프큐엘 팀", "description", "설명")));

		assertGraphQlOk(response);
		JsonNode payload = response.data().path("createTeam");
		long teamId = Long.parseLong(payload.path("team").path("id").asText());
		trackTeam(teamId);
		assertThat(payload.path("inviteCode").asText()).matches(CODE_FORMAT);
		JsonNode team = payload.path("team");
		assertThat(team.path("name").asText()).isEqualTo("그래프큐엘 팀");
		assertThat(team.path("description").asText()).isEqualTo("설명");
		// 공개 여부를 안 보내면 공개
		assertThat(team.path("isPublic").asBoolean()).isTrue();
		assertThat(team.path("isLeader").asBoolean()).isTrue();
		assertThat(team.path("memberCount").asInt()).isEqualTo(1);
		assertThat(team.path("leader").path("id").asText()).isEqualTo(Long.toString(leader));
		assertThat(team.path("joinedAt").asText()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}");
		assertThat(teamRow(teamId).get("invite_code")).isEqualTo(payload.path("inviteCode").asText());
	}

	@Test
	void createTeam에_비공개를_보내면_비공개로_만들어진다() {
		long leader = member();

		GraphQlResponse response = gql(leader, TeamGql.CREATE,
				Map.of("input", Map.of("name", "비공개 요청", "isPublic", false)));

		assertGraphQlOk(response);
		long teamId = Long.parseLong(response.data().path("createTeam").path("team").path("id").asText());
		trackTeam(teamId);
		assertThat(response.data().path("createTeam").path("team").path("isPublic").asBoolean()).isFalse();
		assertThat(teamRow(teamId).get("is_public")).isIn(false, 0);
	}

	@Test
	void createTeam의_이름_검증_오류는_오류_코드로_내려간다() {
		long leader = member();

		assertGraphQlError(gql(leader, TeamGql.CREATE, Map.of("input", Map.of("name", " "))), ErrorCode.TEAM_NAME_REQUIRED);
		assertGraphQlError(gql(leader, TeamGql.CREATE, Map.of("input", Map.of("name", "가".repeat(21)))),
				ErrorCode.INVALID_INPUT);

		assertThat(teamsLedBy(leader)).isZero();
	}

	@Test
	void teamInviteCode는_팀장이_만든_코드를_그대로_돌려준다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);

		GraphQlResponse response = gql(leader, TeamGql.INVITE_CODE, Map.of("id", Long.toString(team.id())));

		assertGraphQlOk(response);
		assertThat(response.data().path("teamInviteCode").asText()).isEqualTo(team.inviteCode());
	}

	private long teamsLedBy(long leaderId) {
		Long count = jdbc.queryForObject("select count(*) from team where leader_id = ?", Long.class, leaderId);
		return count == null ? 0 : count;
	}

}
