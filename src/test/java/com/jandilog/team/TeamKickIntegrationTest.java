package com.jandilog.team;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.jandilog.common.exception.ErrorCode;
import com.jandilog.team.dto.TeamMemberResponse;
import com.jandilog.team.dto.TeamResponse;
import com.jandilog.team.support.TeamGql;
import com.jandilog.team.support.TeamIntegrationTest;
import com.jandilog.team.support.TeamWarningFixture.CategoryRow;
import com.jandilog.team.support.TeamWarningFixture.WarningRow;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;

// 팀원 추방과 1년 재참가 차단, 경고 카테고리 제거 (기능명세서 2장·6장, 화면설계서 TM-06 ⑤, E-12·E-13·E-22, DB명세서 2장·4-3)
class TeamKickIntegrationTest extends TeamIntegrationTest {

	private static final LocalDate WEEK_1 = LocalDate.of(2026, 9, 7);
	private static final LocalDate WEEK_2 = LocalDate.of(2026, 9, 14);
	private static final LocalDate WEEK_3 = LocalDate.of(2026, 9, 21);

	@Autowired
	private PlatformTransactionManager txManager;

	// ----- 소속·차단 키 -----

	@Test
	void 팀장이_추방하면_소속이_KICKED로_닫히고_그_사람은_팀에_접근하지_못한다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long kicked = joinedMember(team);
		long stays = joinedMember(team);

		leaveService.kick(leader, team.id(), kicked);

		List<Map<String, Object>> rows = membershipRows(team.id(), kicked);
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).get("left_at")).isNotNull();
		assertThat(rows.get(0).get("leave_type")).isEqualTo("KICKED");
		assertThat(isActiveMember(team.id(), stays)).isTrue();
		assertThat(activeMemberCount(team.id())).isEqualTo(2);
		assertThat(teamService.myTeams(kicked)).isEmpty();
		assertApiError(() -> teamService.get(kicked, team.id()), ErrorCode.FORBIDDEN);
		assertThat(allMembers(stays, team.id())).extracting(TeamMemberResponse::id)
				.containsExactly(leader, stays);
	}

	@Test
	void 추방하면_추방_시각을_값으로_1년_만료의_차단_키가_생긴다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long kicked = joinedMember(team);
		clock.fixAt(Instant.parse("2026-09-10T03:00:15Z"));

		leaveService.kick(leader, team.id(), kicked);

		String key = banKey(team.id(), kicked);
		assertThat(redis.hasKey(key)).isTrue();
		assertThat(redis.opsForValue().get(key)).isEqualTo("2026-09-10T12:00:15");
		Long ttlSeconds = redis.getExpire(key, TimeUnit.SECONDS);
		assertThat(ttlSeconds).isNotNull();
		assertThat(ttlSeconds).isLessThanOrEqualTo(Duration.ofDays(365).toSeconds());
		assertThat(ttlSeconds).isGreaterThan(Duration.ofDays(365).minusMinutes(10).toSeconds());
	}

	@Test
	void 추방이_들어_있던_트랜잭션이_롤백되면_차단_키도_지워져_추방하지_않은_사람이_막히지_않는다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long target = joinedMember(team);
		long warningId = warnings.warning(target, WEEK_1, team.id());

		new TransactionTemplate(txManager).executeWithoutResult(status -> {
			leaveService.kick(leader, team.id(), target);
			// 키는 DB 변경과 같은 트랜잭션 안에서 먼저 쓰인다
			assertThat(redis.hasKey(banKey(team.id(), target))).isTrue();
			status.setRollbackOnly();
		});

		assertThat(redis.hasKey(banKey(team.id(), target))).isFalse();
		assertThat(isActiveMember(team.id(), target)).isTrue();
		assertThat(membershipRows(team.id(), target)).hasSize(1);
		assertThat(warnings.warning(warningId).alive()).isTrue();
	}

	@Test
	void 팀장이_아니면_추방할_수_없다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long teammate = joinedMember(team);
		long target = joinedMember(team);
		long outsider = member();
		long admin = adminMember();

		assertApiError(() -> leaveService.kick(teammate, team.id(), target), ErrorCode.FORBIDDEN);
		assertApiError(() -> leaveService.kick(outsider, team.id(), target), ErrorCode.FORBIDDEN);
		assertApiError(() -> leaveService.kick(admin, team.id(), target), ErrorCode.FORBIDDEN);

		assertThat(isActiveMember(team.id(), target)).isTrue();
		assertThat(redis.hasKey(banKey(team.id(), target))).isFalse();
	}

	@Test
	void 팀장_본인은_추방할_수_없다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		joinedMember(team);

		assertApiError(() -> leaveService.kick(leader, team.id(), leader), ErrorCode.INVALID_INPUT);

		assertThat(isActiveMember(team.id(), leader)).isTrue();
		assertThat(teamService.get(leader, team.id()).isLeader()).isTrue();
		assertThat(redis.hasKey(banKey(team.id(), leader))).isFalse();
	}

	@Test
	void 팀원이_아닌_사람은_추방할_수_없고_차단_키도_만들지_않는다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long outsider = member();
		long selfLeft = joinedMember(team);
		leaveService.leave(selfLeft, team.id());
		long alreadyKicked = joinedMember(team);
		leaveService.kick(leader, team.id(), alreadyKicked);

		assertApiError(() -> leaveService.kick(leader, team.id(), outsider), ErrorCode.TEAM_MEMBER_NOT_FOUND);
		assertApiError(() -> leaveService.kick(leader, team.id(), selfLeft), ErrorCode.TEAM_MEMBER_NOT_FOUND);
		assertApiError(() -> leaveService.kick(leader, team.id(), alreadyKicked), ErrorCode.TEAM_MEMBER_NOT_FOUND);

		assertThat(redis.hasKey(banKey(team.id(), outsider))).isFalse();
		assertThat(redis.hasKey(banKey(team.id(), selfLeft))).isFalse();
	}

	@Test
	void 없는_팀과_삭제된_팀에서는_추방할_수_없다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long target = joinedMember(team);

		assertApiError(() -> leaveService.kick(leader, 987_654_321L, target), ErrorCode.NOT_FOUND);
		leaveService.delete(leader, team.id());
		assertApiError(() -> leaveService.kick(leader, team.id(), target), ErrorCode.NOT_FOUND);
	}

	// ----- 초대코드 재참가 차단 (E-12) -----

	@Test
	void 추방된_사람은_그_팀_초대코드로_다시_참가할_수_없다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long kicked = joinedMember(team);
		leaveService.kick(leader, team.id(), kicked);

		assertApiError(() -> joinService.joinByCode(kicked, team.inviteCode()), ErrorCode.TEAM_JOIN_BLOCKED);
		assertApiError(() -> joinService.joinByCode(kicked, team.inviteCode().toLowerCase()), ErrorCode.TEAM_JOIN_BLOCKED);

		assertThat(membershipRows(team.id(), kicked)).hasSize(1);
		assertThat(isActiveMember(team.id(), kicked)).isFalse();
	}

	@Test
	void 차단은_추방당한_그_팀과_그_사람에게만_걸린다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		CreatedTeam otherTeam = newTeam(member());
		long kicked = joinedMember(team);
		leaveService.kick(leader, team.id(), kicked);
		long newcomer = member();

		joinService.joinByCode(kicked, otherTeam.inviteCode());
		joinService.joinByCode(newcomer, team.inviteCode());

		assertThat(isActiveMember(otherTeam.id(), kicked)).isTrue();
		assertThat(isActiveMember(team.id(), newcomer)).isTrue();
	}

	@Test
	void 차단_키가_만료되면_다시_참가할_수_있다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long kicked = joinedMember(team);
		leaveService.kick(leader, team.id(), kicked);
		// 1년이 지나 키가 사라진 상태
		redis.delete(banKey(team.id(), kicked));

		joinService.joinByCode(kicked, team.inviteCode());

		assertThat(isActiveMember(team.id(), kicked)).isTrue();
		assertThat(membershipRows(team.id(), kicked)).hasSize(2);
	}

	@Test
	void 아이디_초대는_차단과_무관해서_추방자도_수락하면_참가하고_차단_키는_그대로다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long kicked = joinedMember(team);
		leaveService.kick(leader, team.id(), kicked);

		inviteAndAccept(leader, team, kicked);

		assertThat(isActiveMember(team.id(), kicked)).isTrue();
		assertThat(redis.hasKey(banKey(team.id(), kicked))).isTrue();
		assertThat(currentMembership(team.id(), kicked).get("joined_by")).isEqualTo("INVITATION");
	}

	@Test
	void 다시_추방하면_차단이_이어진다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long kicked = joinedMember(team);
		leaveService.kick(leader, team.id(), kicked);
		inviteAndAccept(leader, team, kicked);

		leaveService.kick(leader, team.id(), kicked);

		assertThat(membershipRows(team.id(), kicked)).extracting(r -> r.get("leave_type")).containsExactly("KICKED", "KICKED");
		assertApiError(() -> joinService.joinByCode(kicked, team.inviteCode()), ErrorCode.TEAM_JOIN_BLOCKED);
	}

	@Test
	void 차단_오류는_추방_사실과_남은_기간을_드러내지_않는다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long kicked = joinedMember(team);
		leaveService.kick(leader, team.id(), kicked);

		GraphQlResponse response = gql(kicked, TeamGql.JOIN, Map.of("code", team.inviteCode()));

		assertGraphQlError(response, ErrorCode.TEAM_JOIN_BLOCKED);
		assertThat(response.errorMessage()).isEqualTo("이 팀에는 참가할 수 없어요. 팀장에게 문의해 주세요.");
		assertThat(response.rawBody()).doesNotContain("추방", "1년", "365", LocalDate.now().toString(), "ban");
	}

	@Test
	void 추방된_사람이_다시_조회하면_권한_없음이다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long kicked = joinedMember(team);
		String teamId = Long.toString(team.id());
		assertGraphQlOk(gql(kicked, TeamGql.TEAM, Map.of("id", teamId)));

		leaveService.kick(leader, team.id(), kicked);

		assertGraphQlError(gql(kicked, TeamGql.TEAM, Map.of("id", teamId)), ErrorCode.FORBIDDEN);
		assertGraphQlError(gql(kicked, TeamGql.TEAM_MEMBERS, Map.of("id", teamId)), ErrorCode.FORBIDDEN);
		assertThat(gql(kicked, TeamGql.MY_TEAMS).data().path("myTeams")).isEmpty();
	}

	@Test
	void kickTeamMember는_GraphQL로도_추방하고_팀장_본인과_비팀장을_막는다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long teammate = joinedMember(team);
		long target = joinedMember(team);
		String teamId = Long.toString(team.id());

		assertGraphQlError(gql(teammate, TeamGql.KICK, Map.of("teamId", teamId, "memberId", Long.toString(target))),
				ErrorCode.FORBIDDEN);
		assertGraphQlError(gql(leader, TeamGql.KICK, Map.of("teamId", teamId, "memberId", Long.toString(leader))),
				ErrorCode.INVALID_INPUT);
		GraphQlResponse ok = gql(leader, TeamGql.KICK, Map.of("teamId", teamId, "memberId", Long.toString(target)));

		assertGraphQlOk(ok);
		assertThat(ok.data().path("kickTeamMember").asBoolean()).isTrue();
		assertThat(isActiveMember(team.id(), target)).isFalse();
		assertThat(redis.hasKey(banKey(team.id(), target))).isTrue();
	}

	// ----- 경고 카테고리 (E-22) -----

	@Test
	void 그_팀만_붙은_경고는_추방하면_KICKED로_소프트_삭제된다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long kicked = joinedMember(team);
		long warningId = warnings.warning(kicked, WEEK_1, team.id());

		leaveService.kick(leader, team.id(), kicked);

		WarningRow warning = warnings.warning(warningId);
		assertThat(warning.alive()).isFalse();
		assertThat(warning.deleteReason()).isEqualTo("KICKED");
		assertThat(warnings.category(warningId, team.id()).removed()).isTrue();
		assertThat(warnings.aliveCount(kicked)).isZero();
		// 행은 지우지 않는다 (소프트 삭제)
		assertThat(warnings.categories(warningId)).hasSize(1);
	}

	@Test
	void 다른_팀_카테고리가_남은_경고는_살아_있고_그_팀_카테고리만_빠진다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		CreatedTeam otherTeam = newTeam(member());
		long kicked = joinedMember(team);
		joinService.joinByCode(kicked, otherTeam.inviteCode());
		long warningId = warnings.warning(kicked, WEEK_1, team.id(), otherTeam.id());

		leaveService.kick(leader, team.id(), kicked);

		assertThat(warnings.warning(warningId).alive()).isTrue();
		assertThat(warnings.warning(warningId).deleteReason()).isNull();
		assertThat(warnings.category(warningId, team.id()).removed()).isTrue();
		assertThat(warnings.category(warningId, otherTeam.id()).removed()).isFalse();
		assertThat(warnings.aliveCount(kicked)).isEqualTo(1);
	}

	@Test
	void 남은_카테고리가_차례로_모두_빠지면_마지막_추방에서_경고가_삭제된다() {
		long leaderA = member();
		long leaderB = member();
		CreatedTeam teamA = newTeam(leaderA);
		CreatedTeam teamB = newTeam(leaderB);
		long kicked = joinedMember(teamA);
		joinService.joinByCode(kicked, teamB.inviteCode());
		long warningId = warnings.warning(kicked, WEEK_1, teamA.id(), teamB.id());

		leaveService.kick(leaderA, teamA.id(), kicked);
		assertThat(warnings.warning(warningId).alive()).isTrue();
		leaveService.kick(leaderB, teamB.id(), kicked);

		WarningRow warning = warnings.warning(warningId);
		assertThat(warning.alive()).isFalse();
		assertThat(warning.deleteReason()).isEqualTo("KICKED");
		assertThat(warnings.categories(warningId)).allMatch(CategoryRow::removed);
	}

	@Test
	void 한_번에_여러_경고가_있으면_경고마다_남은_카테고리를_따로_센다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		CreatedTeam otherTeam = newTeam(member());
		long kicked = joinedMember(team);
		joinService.joinByCode(kicked, otherTeam.inviteCode());
		long onlyThis = warnings.warning(kicked, WEEK_1, team.id());
		long both = warnings.warning(kicked, WEEK_2, team.id(), otherTeam.id());
		long onlyOther = warnings.warning(kicked, WEEK_3, otherTeam.id());

		leaveService.kick(leader, team.id(), kicked);

		assertThat(warnings.warning(onlyThis).alive()).isFalse();
		assertThat(warnings.warning(both).alive()).isTrue();
		assertThat(warnings.warning(onlyOther).alive()).isTrue();
		assertThat(warnings.category(onlyOther, otherTeam.id()).removed()).isFalse();
		assertThat(warnings.aliveCount(kicked)).isEqualTo(2);
	}

	@Test
	void 다른_회원의_경고는_추방의_영향을_받지_않는다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long kicked = joinedMember(team);
		long stays = joinedMember(team);
		long staysWarning = warnings.warning(stays, WEEK_1, team.id());
		long leaderWarning = warnings.warning(leader, WEEK_1, team.id());

		leaveService.kick(leader, team.id(), kicked);

		assertThat(warnings.warning(staysWarning).alive()).isTrue();
		assertThat(warnings.category(staysWarning, team.id()).removed()).isFalse();
		assertThat(warnings.warning(leaderWarning).alive()).isTrue();
	}

	@Test
	void 다른_팀에서만_받은_경고는_이_팀_추방과_무관하다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		CreatedTeam otherTeam = newTeam(member());
		long kicked = joinedMember(team);
		joinService.joinByCode(kicked, otherTeam.inviteCode());
		long warningId = warnings.warning(kicked, WEEK_1, otherTeam.id());

		leaveService.kick(leader, team.id(), kicked);

		assertThat(warnings.warning(warningId).alive()).isTrue();
		assertThat(warnings.category(warningId, otherTeam.id()).removed()).isFalse();
	}

	@Test
	void 경고가_없는_회원도_문제없이_추방된다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long kicked = joinedMember(team);

		leaveService.kick(leader, team.id(), kicked);

		assertThat(isActiveMember(team.id(), kicked)).isFalse();
		assertThat(warnings.aliveCount(kicked)).isZero();
	}

	// 자진 탈퇴와 달리 추방은 카테고리를 빼므로, 같은 경고를 두 경로로 비교한다
	@Test
	void 자진_탈퇴는_경고를_그대로_두고_추방은_카테고리를_뺀다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long leaver = joinedMember(team);
		long kicked = joinedMember(team);
		long leaverWarning = warnings.warning(leaver, WEEK_1, team.id());
		long kickedWarning = warnings.warning(kicked, WEEK_1, team.id());

		leaveService.leave(leaver, team.id());
		leaveService.kick(leader, team.id(), kicked);

		assertThat(warnings.warning(leaverWarning).alive()).isTrue();
		assertThat(warnings.category(leaverWarning, team.id()).removed()).isFalse();
		assertThat(warnings.warning(kickedWarning).alive()).isFalse();
	}

	@Test
	void 추방한_회원의_팀_응답은_남은_팀원_수를_반영한다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long kicked = joinedMember(team);
		joinedMember(team);

		leaveService.kick(leader, team.id(), kicked);

		TeamResponse response = teamService.get(leader, team.id());
		assertThat(response.memberCount()).isEqualTo(2);
	}

}
