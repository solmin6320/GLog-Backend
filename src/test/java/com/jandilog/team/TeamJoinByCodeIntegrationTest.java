package com.jandilog.team;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.team.dto.TeamResponse;
import com.jandilog.team.support.TeamGql;
import com.jandilog.team.support.TeamIntegrationTest;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;

// 초대코드 참가 (기능명세서 2장 "초대코드 참가", 화면설계서 TM-03, E-10·E-11·E-12, DB명세서 1-1·1-3)
class TeamJoinByCodeIntegrationTest extends TeamIntegrationTest {

	@Test
	void 초대코드로_참가하면_소속이_생기고_팀_정보를_받는다() {
		long leader = member();
		CreatedTeam team = newTeam(leader, "참가 팀", "소개", false);
		long joiner = member();

		TeamResponse response = joinService.joinByCode(joiner, team.inviteCode());

		assertThat(response.id()).isEqualTo(team.id());
		assertThat(response.name()).isEqualTo("참가 팀");
		assertThat(response.isLeader()).isFalse();
		assertThat(response.memberCount()).isEqualTo(2);
		assertThat(response.leader().id()).isEqualTo(leader);
		assertThat(isActiveMember(team.id(), joiner)).isTrue();
		assertThat(teamService.myTeams(joiner)).extracting(TeamResponse::id).containsExactly(team.id());
	}

	@Test
	void 참가에_성공하면_사용한_초대코드를_소속_행에_저장한다() {
		CreatedTeam team = newTeam(member());
		long joiner = member();

		joinService.joinByCode(joiner, team.inviteCode());

		Map<String, Object> row = currentMembership(team.id(), joiner);
		assertThat(row.get("used_invite_code")).isEqualTo(team.inviteCode());
		assertThat(row.get("joined_by")).isEqualTo("INVITE_CODE");
		assertThat(row.get("left_at")).isNull();
		assertThat(row.get("leave_type")).isNull();
	}

	@ParameterizedTest
	@ValueSource(strings = {"lower", "Mixed", "  spaced  ", "UPPER"})
	void 초대코드는_대소문자와_앞뒤_공백을_구분하지_않는다(String style) {
		CreatedTeam team = newTeam(member());
		long joiner = member();
		String typed = switch (style) {
			case "lower" -> team.inviteCode().toLowerCase();
			case "Mixed" -> team.inviteCode().substring(0, 5) + team.inviteCode().substring(5).toLowerCase();
			case "  spaced  " -> "  " + team.inviteCode().toLowerCase() + "  ";
			default -> team.inviteCode();
		};

		joinService.joinByCode(joiner, typed);

		assertThat(isActiveMember(team.id(), joiner)).isTrue();
		// 저장하는 것은 입력 문자열이 아니라 발급된 코드다
		assertThat(currentMembership(team.id(), joiner).get("used_invite_code")).isEqualTo(team.inviteCode());
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = {"", "   ", "0000000000", "NOSUCHCODE-1", "ABC"})
	void 없는_초대코드는_참가할_수_없다(String code) {
		long joiner = member();

		assertApiError(() -> joinService.joinByCode(joiner, code), ErrorCode.TEAM_CODE_NOT_FOUND);

		assertThat(teamService.myTeams(joiner)).isEmpty();
	}

	@Test
	void 이미_참가한_팀의_코드를_다시_입력하면_중복_소속을_만들지_않는다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long joiner = joinedMember(team);

		assertApiError(() -> joinService.joinByCode(joiner, team.inviteCode()), ErrorCode.TEAM_ALREADY_JOINED);
		assertApiError(() -> joinService.joinByCode(leader, team.inviteCode()), ErrorCode.TEAM_ALREADY_JOINED);

		assertThat(membershipRows(team.id(), joiner)).hasSize(1);
		assertThat(membershipRows(team.id(), leader)).hasSize(1);
		assertThat(activeMemberCount(team.id())).isEqualTo(2);
	}

	@Test
	void 검사_순서는_없는_코드_이미_참가_추방_차단_순이라_참가한_사람은_차단_키가_있어도_이미_참가로_안내한다() {
		CreatedTeam team = newTeam(member());
		long joiner = joinedMember(team);
		redis.opsForValue().set(banKey(team.id(), joiner), "2026-01-01T00:00:00");

		assertApiError(() -> joinService.joinByCode(joiner, team.inviteCode()), ErrorCode.TEAM_ALREADY_JOINED);
	}

	@Test
	void 처음_참가한_시각은_계정_생애_한_번만_기록하고_다시_들어와도_바꾸지_않는다() {
		CreatedTeam teamA = newTeam(member());
		CreatedTeam teamB = newTeam(member());
		long joiner = member();
		assertThat(firstTeamJoinedAt(joiner)).isNull();

		clock.fixAt(Instant.parse("2026-09-01T00:00:00Z"));
		joinService.joinByCode(joiner, teamA.inviteCode());
		LocalDateTime first = firstTeamJoinedAt(joiner);
		clock.fixAt(Instant.parse("2026-09-08T00:00:00Z"));
		joinService.joinByCode(joiner, teamB.inviteCode());
		clock.fixAt(Instant.parse("2026-09-10T00:00:00Z"));
		leaveService.leave(joiner, teamA.id());
		clock.fixAt(Instant.parse("2026-09-15T00:00:00Z"));
		joinService.joinByCode(joiner, teamA.inviteCode());

		assertThat(first).isEqualTo(LocalDateTime.of(2026, 9, 1, 9, 0, 0));
		assertThat(firstTeamJoinedAt(joiner)).isEqualTo(first);
	}

	@Test
	void 자진_탈퇴한_사람은_초대코드로_다시_참가할_수_있고_소속_이력이_쌓인다() {
		CreatedTeam team = newTeam(member());
		long joiner = joinedMember(team);
		clock.fixAt(Instant.parse("2026-09-10T00:00:00Z"));
		leaveService.leave(joiner, team.id());
		clock.fixAt(Instant.parse("2026-09-11T00:00:00Z"));

		joinService.joinByCode(joiner, team.inviteCode());

		List<Map<String, Object>> rows = membershipRows(team.id(), joiner);
		assertThat(rows).hasSize(2);
		assertThat(rows.get(0).get("leave_type")).isEqualTo("SELF");
		assertThat(rows.get(0).get("left_at")).isNotNull();
		assertThat(rows.get(1).get("left_at")).isNull();
		assertThat(redis.hasKey(banKey(team.id(), joiner))).isFalse();
	}

	@Test
	void 같은_초에_나갔다_다시_들어와도_소속_이력이_충돌하지_않고_재참가가_한_초_뒤로_기록된다() {
		CreatedTeam team = newTeam(member());
		long joiner = member();
		clock.fixAt(Instant.parse("2026-09-10T00:00:00Z"));
		joinService.joinByCode(joiner, team.inviteCode());
		leaveService.leave(joiner, team.id());

		joinService.joinByCode(joiner, team.inviteCode());

		List<Map<String, Object>> rows = membershipRows(team.id(), joiner);
		assertThat(rows).hasSize(2);
		LocalDateTime first = time(rows.get(0).get("joined_at"));
		LocalDateTime second = time(rows.get(1).get("joined_at"));
		assertThat(second).isEqualTo(first.plusSeconds(1));
		assertThat(isActiveMember(team.id(), joiner)).isTrue();
	}

	@Test
	void 참가하면_팀원_수와_팀원_목록에_반영된다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long first = joinedMember(team);
		long second = joinedMember(team);

		assertThat(teamService.get(leader, team.id()).memberCount()).isEqualTo(3);
		assertThat(teamService.members(first, team.id())).extracting(m -> m.id()).containsExactlyInAnyOrder(leader, first, second);
	}

	// ----- GraphQL -----

	@Test
	void joinTeamByCode는_소문자_코드도_받아_팀을_돌려준다() {
		CreatedTeam team = newTeam(member(), "그래프큐엘 참가", null, true);
		long joiner = member();

		GraphQlResponse response = gql(joiner, TeamGql.JOIN, Map.of("code", team.inviteCode().toLowerCase()));

		assertGraphQlOk(response);
		JsonNode joined = response.data().path("joinTeamByCode");
		assertThat(joined.path("id").asText()).isEqualTo(Long.toString(team.id()));
		assertThat(joined.path("name").asText()).isEqualTo("그래프큐엘 참가");
		assertThat(joined.path("isLeader").asBoolean()).isFalse();
		assertThat(joined.path("memberCount").asInt()).isEqualTo(2);
		assertThat(isActiveMember(team.id(), joiner)).isTrue();
	}

	@Test
	void joinTeamByCode의_E_10과_E_11은_각각_다른_오류_코드와_문구다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long joiner = joinedMember(team);

		assertGraphQlError(gql(joiner, TeamGql.JOIN, Map.of("code", "0000000000")), ErrorCode.TEAM_CODE_NOT_FOUND);
		assertGraphQlError(gql(joiner, TeamGql.JOIN, Map.of("code", team.inviteCode())), ErrorCode.TEAM_ALREADY_JOINED);
	}

}
