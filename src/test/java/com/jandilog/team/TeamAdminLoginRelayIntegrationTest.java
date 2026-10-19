package com.jandilog.team;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import com.jandilog.common.exception.ErrorCode;
import com.jandilog.common.security.OAuthLoginSuccessHandler;
import com.jandilog.member.domain.MemberRole;
import com.jandilog.member.domain.MemberStatus;
import com.jandilog.team.support.TeamGql;
import com.jandilog.team.support.TeamIntegrationTest;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;
import com.jandilog.testsupport.auth.OAuthLoginDriver;
import com.jandilog.testsupport.auth.OAuthLoginDriver.LoginResult;

// 첫 관리자 승격(ADMIN_GITHUB_IDS 로그인) → 코드 교환으로 받은 토큰 → 다른 팀 추방·삭제 → admin_action_log 기록까지 이어서 확인한다.
// 승격 규칙 자체는 AdminBootstrapIntegrationTest, 팀 기능별 권한은 TeamAdminAccessIntegrationTest가 맡는다
@TestPropertySource(properties = "ADMIN_GITHUB_IDS=7100000010001, 7100000010002, 7100000010003, 7100000010004")
class TeamAdminLoginRelayIntegrationTest extends TeamIntegrationTest {

	// 실제 GitHub id와 겹치지 않는 큰 값. 다른 승격 시험(7100000000001 대)과도 겹치지 않는다
	private static final long LISTED_1 = 7_100_000_010_001L;
	private static final long LISTED_2 = 7_100_000_010_002L;
	private static final long LISTED_3 = 7_100_000_010_003L;
	private static final long LISTED_4 = 7_100_000_010_004L;
	private static final LocalDateTime EARLIER = LocalDateTime.of(2026, 1, 2, 3, 4, 5);
	private static final String EXCHANGE = "mutation($code: String!) { exchangeAuthCode(code: $code) "
			+ "{ accessToken me { id status role } } }";

	@Autowired
	private OAuthLoginSuccessHandler successHandler;

	private OAuthLoginDriver driver;
	private long leader;
	private long mate;
	private long otherLeader;
	private long otherMate;
	private CreatedTeam teamA;
	private CreatedTeam teamB;

	@BeforeEach
	void setUpLogin() {
		driver = new OAuthLoginDriver(successHandler);
		// 이전 실행의 흔적을 지우고 다시 추적한다
		for (long id : List.of(LISTED_1, LISTED_2, LISTED_3, LISTED_4)) {
			members.trackGithubId(id);
		}
		members.cleanup();
		for (long id : List.of(LISTED_1, LISTED_2, LISTED_3, LISTED_4)) {
			members.trackGithubId(id);
		}

		leader = member();
		mate = member();
		otherLeader = member();
		otherMate = member();
		teamA = newTeam(leader, "승격 확인 팀 A", null, true);
		teamB = newTeam(otherLeader, "승격 확인 팀 B", null, false);
		joinService.joinByCode(mate, teamA.inviteCode());
		joinService.joinByCode(otherMate, teamB.inviteCode());
	}

	// ----- 도우미 -----

	// GitHub 로그인 성공 → 일회용 코드 → JWT 교환까지 한 번에. 토큰만 돌려준다
	private String loginForToken(long githubId, String login) {
		LoginResult result = driver.login(githubId, login, "닉네임");
		assertThat(result.error()).isNull();
		assertThat(result.code()).isNotBlank();
		GraphQlResponse exchanged = graphQl.post(null, EXCHANGE, Map.of("code", result.code()));
		assertGraphQlOk(exchanged);
		return exchanged.data().path("exchangeAuthCode").path("accessToken").asText();
	}

	private long memberIdOf(long githubId) {
		return members.findByGithubId(githubId).orElseThrow().id();
	}

	private GraphQlResponse call(String token, String query, Map<String, Object> variables) {
		return graphQl.post(token, query, variables);
	}

	private GraphQlResponse inviteCode(String token, CreatedTeam team) {
		return call(token, TeamGql.INVITE_CODE, Map.of("id", Long.toString(team.id())));
	}

	private GraphQlResponse kick(String token, CreatedTeam team, long target) {
		return call(token, TeamGql.KICK, Map.of("teamId", Long.toString(team.id()), "memberId", Long.toString(target)));
	}

	private GraphQlResponse delete(String token, CreatedTeam team) {
		return call(token, TeamGql.DELETE, Map.of("teamId", Long.toString(team.id())));
	}

	private List<Map<String, Object>> adminLogs(long adminId) {
		return jdbc.queryForList("select * from admin_action_log where admin_id = ? order by id", adminId);
	}

	private long membershipId(long teamId, long memberId) {
		return ((Number) membershipRows(teamId, memberId).get(0).get("id")).longValue();
	}

	private void assertTeamsUntouched() {
		for (CreatedTeam team : List.of(teamA, teamB)) {
			assertThat(teamRow(team.id()).get("deleted_at")).isNull();
			assertThat(activeMemberCount(team.id())).isEqualTo(2);
		}
		assertThat(redis.hasKey(banKey(teamA.id(), mate))).isFalse();
	}

	// ----- 이어달리기 -----

	@Test
	void 목록의_새_계정이_로그인해_받은_토큰으로_바로_다른_팀을_추방하고_삭제하며_기록은_그_회원_것이다() {
		String firstToken = loginForToken(LISTED_1, "relay-admin-1");
		String secondToken = loginForToken(LISTED_2, "relay-admin-2");
		long first = memberIdOf(LISTED_1);
		long second = memberIdOf(LISTED_2);
		assertThat(members.find(first).orElseThrow().role()).isEqualTo(MemberRole.ADMIN);
		assertThat(members.find(first).orElseThrow().status()).isEqualTo(MemberStatus.ACTIVE);

		// 어느 팀의 팀원도 아닌 채로 첫 요청부터 쓸 수 있다
		GraphQlResponse info = call(firstToken, TeamGql.TEAM, Map.of("id", Long.toString(teamB.id())));
		assertGraphQlOk(info);
		assertThat(info.data().path("team").path("name").asText()).isEqualTo("승격 확인 팀 B");
		assertThat(info.data().path("team").path("isLeader").asBoolean()).isFalse();
		assertThat(info.data().path("team").get("joinedAt").isNull()).isTrue();
		assertThat(call(firstToken, TeamGql.MY_TEAMS, Map.of()).data().path("myTeams")).isEmpty();

		GraphQlResponse kicked = kick(firstToken, teamA, mate);
		assertGraphQlOk(kicked);
		assertThat(kicked.data().path("kickTeamMember").asBoolean()).isTrue();
		assertGraphQlOk(delete(secondToken, teamB));

		assertThat(isActiveMember(teamA.id(), mate)).isFalse();
		assertThat(redis.hasKey(banKey(teamA.id(), mate))).isTrue();
		assertThat(teamRow(teamB.id()).get("deleted_at")).isNotNull();
		List<Map<String, Object>> firstLogs = adminLogs(first);
		assertThat(firstLogs).hasSize(1);
		assertThat(firstLogs.get(0).get("action")).isEqualTo("KICK_MEMBER");
		assertThat(firstLogs.get(0).get("target_type")).isEqualTo("TEAM_MEMBER");
		assertThat(firstLogs.get(0).get("target_id")).isEqualTo(Long.toString(membershipId(teamA.id(), mate)));
		List<Map<String, Object>> secondLogs = adminLogs(second);
		assertThat(secondLogs).hasSize(1);
		assertThat(secondLogs.get(0).get("action")).isEqualTo("DELETE_TEAM");
		assertThat(secondLogs.get(0).get("target_id")).isEqualTo(Long.toString(teamB.id()));
		// 승격만으로 팀원이 되지는 않는다
		assertThat(isActiveMember(teamA.id(), first)).isFalse();
		assertThat(isActiveMember(teamB.id(), second)).isFalse();
	}

	@ParameterizedTest
	@CsvSource({"PENDING, ACCOUNT_PENDING", "REJECTED, ACCOUNT_REJECTED"})
	void 승인_대기나_거절이던_목록_계정은_로그인_전_토큰이_막히고_승격되면_같은_토큰이_통과한다(MemberStatus status,
			ErrorCode beforeLogin) {
		long id = members.insertWithGithubId(LISTED_3, status, MemberRole.MEMBER, "before-login", "이전 이름", EARLIER, null);
		String token = bearerFor(id);

		assertGraphQlError(inviteCode(token, teamA), beforeLogin);
		assertGraphQlError(kick(token, teamA, mate), beforeLogin);
		assertTeamsUntouched();

		driver.login(LISTED_3, "after-login", "홍길동");

		assertThat(members.find(id).orElseThrow().status()).isEqualTo(MemberStatus.ACTIVE);
		assertThat(members.find(id).orElseThrow().role()).isEqualTo(MemberRole.ADMIN);
		assertGraphQlOk(inviteCode(token, teamA));
		assertGraphQlOk(kick(token, teamA, mate));
		assertThat(isActiveMember(teamA.id(), mate)).isFalse();
		assertThat(adminLogs(id)).extracting(r -> r.get("action")).containsExactly("KICK_MEMBER");
	}

	@Test
	void 승격된_관리자를_DB에서_내리면_같은_토큰이_바로_거부되고_다시_로그인하면_관리자로_돌아온다() {
		String oldToken = loginForToken(LISTED_4, "relay-admin-4");
		long id = memberIdOf(LISTED_4);
		assertGraphQlOk(inviteCode(oldToken, teamA));

		members.setRole(id, MemberRole.MEMBER);

		// 토큰은 그대로 유효하고 로그인 상태(me)도 유지되지만 관리자 권한만 사라진다
		GraphQlResponse me = call(oldToken, "{ me { id status role } }", Map.of());
		assertGraphQlOk(me);
		assertThat(me.data().path("me").path("role").asText()).isEqualTo("MEMBER");
		assertGraphQlError(inviteCode(oldToken, teamA), ErrorCode.FORBIDDEN);
		assertGraphQlError(kick(oldToken, teamA, mate), ErrorCode.FORBIDDEN);
		assertGraphQlError(delete(oldToken, teamB), ErrorCode.FORBIDDEN);
		assertTeamsUntouched();
		assertThat(adminLogs(id)).isEmpty();

		// 목록에 아직 있는 계정이라 다음 로그인에서 다시 승격되고, 그때는 예전 토큰도 다시 통과한다
		String newToken = loginForToken(LISTED_4, "relay-admin-4");

		assertThat(members.find(id).orElseThrow().role()).isEqualTo(MemberRole.ADMIN);
		assertGraphQlOk(inviteCode(oldToken, teamA));
		assertGraphQlOk(kick(newToken, teamA, mate));
		assertThat(adminLogs(id)).extracting(r -> r.get("action")).containsExactly("KICK_MEMBER");
	}

	@Test
	void 목록에_없는_승인된_회원은_로그인해도_관리자_권한이_생기지_않는다() {
		long id = members.active();
		String token = loginForToken(members.find(id).orElseThrow().githubId(), "plain-member");

		assertThat(members.find(id).orElseThrow().role()).isEqualTo(MemberRole.MEMBER);
		assertGraphQlError(inviteCode(token, teamA), ErrorCode.FORBIDDEN);
		assertGraphQlError(kick(token, teamA, mate), ErrorCode.FORBIDDEN);
		assertGraphQlError(delete(token, teamB), ErrorCode.FORBIDDEN);
		assertGraphQlError(call(token, TeamGql.TEAM, Map.of("id", Long.toString(teamB.id()))), ErrorCode.FORBIDDEN);
		assertTeamsUntouched();
		assertThat(adminLogs(id)).isEmpty();
	}

}
