package com.jandilog.team;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import com.jandilog.common.exception.ErrorCode;
import com.jandilog.team.dto.UpdateTeamInput;
import com.jandilog.team.support.TeamGql;
import com.jandilog.team.support.TeamIntegrationTest;
import com.jandilog.team.support.TeamWarningFixture.WarningRow;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;

// 팀 삭제 (기능명세서 2장 "팀 삭제"·6장, 화면설계서 TM-06 ⑨, E-21·E-22, DB명세서 1-2·4-4)
class TeamDeleteIntegrationTest extends TeamIntegrationTest {

	private static final LocalDate WEEK_1 = LocalDate.of(2026, 9, 7);
	private static final LocalDate WEEK_2 = LocalDate.of(2026, 9, 14);

	private static final String POST = "query($id: ID!) { post(id: $id) { id teamId teamName } }";

	private Document mongoDocument(String postId) {
		return mongo.getCollection("posts").find(new Document("_id", new ObjectId(postId))).first();
	}

	private Map<String, Object> indexRow(String postId) {
		return jdbc.queryForMap("select team_id, deleted_at from post_index where mongo_post_id = ?", postId);
	}

	// ----- 팀·소속 -----

	@Test
	void 팀장이_삭제하면_deleted_at만_찍고_팀_행은_남는다() {
		long leader = member();
		CreatedTeam team = newTeam(leader, "지울 팀", "소개", true);

		leaveService.delete(leader, team.id());

		Map<String, Object> row = teamRow(team.id());
		assertThat(row.get("deleted_at")).isNotNull();
		assertThat(row.get("name")).isEqualTo("지울 팀");
		assertThat(row.get("invite_code")).isEqualTo(team.inviteCode());
	}

	@Test
	void 삭제하면_현재_팀원의_소속이_TEAM_DELETED로_닫히고_이미_나간_이력은_그대로다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long active = joinedMember(team);
		long selfLeft = joinedMember(team);
		leaveService.leave(selfLeft, team.id());
		long kicked = joinedMember(team);
		leaveService.kick(leader, team.id(), kicked);

		leaveService.delete(leader, team.id());

		assertThat(currentLeaveType(team.id(), leader)).isEqualTo("TEAM_DELETED");
		assertThat(currentLeaveType(team.id(), active)).isEqualTo("TEAM_DELETED");
		assertThat(membershipRows(team.id(), selfLeft).get(0).get("leave_type")).isEqualTo("SELF");
		assertThat(membershipRows(team.id(), kicked).get(0).get("leave_type")).isEqualTo("KICKED");
		assertThat(activeMemberCount(team.id())).isZero();
		assertThat(teamService.myTeams(active)).isEmpty();
		assertThat(teamService.myTeams(leader)).isEmpty();
	}

	private Object currentLeaveType(long teamId, long memberId) {
		return membershipRows(teamId, memberId).get(0).get("leave_type");
	}

	@Test
	void 팀장이_아니면_삭제할_수_없다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long teammate = joinedMember(team);
		long outsider = member();
		long admin = adminMember();

		assertApiError(() -> leaveService.delete(teammate, team.id()), ErrorCode.FORBIDDEN);
		assertApiError(() -> leaveService.delete(outsider, team.id()), ErrorCode.FORBIDDEN);
		assertApiError(() -> leaveService.delete(admin, team.id()), ErrorCode.FORBIDDEN);

		assertThat(teamRow(team.id()).get("deleted_at")).isNull();
		assertThat(activeMemberCount(team.id())).isEqualTo(2);
	}

	@Test
	void 삭제된_팀은_조회도_변경도_없는_팀으로_취급한다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long teammate = joinedMember(team);
		long invitee = member();
		leaveService.delete(leader, team.id());

		for (long viewer : new long[] {leader, teammate}) {
			assertApiError(() -> teamService.get(viewer, team.id()), ErrorCode.NOT_FOUND);
			assertApiError(() -> teamService.members(viewer, team.id()), ErrorCode.NOT_FOUND);
			assertApiError(() -> teamService.inviteCode(viewer, team.id()), ErrorCode.NOT_FOUND);
			assertApiError(() -> invitationService.sent(viewer, team.id()), ErrorCode.NOT_FOUND);
			assertApiError(() -> teamService.update(viewer, team.id(), new UpdateTeamInput("이름", null, null)),
					ErrorCode.NOT_FOUND);
			assertApiError(() -> invitationService.invite(viewer, team.id(), loginOf(invitee)), ErrorCode.NOT_FOUND);
			assertApiError(() -> leaveService.kick(viewer, team.id(), teammate), ErrorCode.NOT_FOUND);
			assertApiError(() -> leaveService.leave(viewer, team.id()), ErrorCode.NOT_FOUND);
			assertApiError(() -> leaveService.transferLeadership(viewer, team.id(), teammate), ErrorCode.NOT_FOUND);
			assertApiError(() -> leaveService.delete(viewer, team.id()), ErrorCode.NOT_FOUND);
		}
		// 삭제된 팀의 초대코드는 없는 코드와 같다
		assertApiError(() -> joinService.joinByCode(invitee, team.inviteCode()), ErrorCode.TEAM_CODE_NOT_FOUND);
	}

	@Test
	void 삭제한_뒤_같은_이름으로_새_팀을_만들_수_있고_초대코드는_새로_발급된다() {
		long leader = member();
		CreatedTeam old = newTeam(leader, "같은 이름", null, true);
		leaveService.delete(leader, old.id());

		CreatedTeam fresh = newTeam(leader, "같은 이름", null, true);

		assertThat(fresh.id()).isNotEqualTo(old.id());
		assertThat(fresh.inviteCode()).isNotEqualTo(old.inviteCode());
		assertThat(teamService.myTeams(leader)).extracting(t -> t.id()).containsExactly(fresh.id());
	}

	// ----- 게시글 연결 (E-21, DB명세서 4-4) -----

	@Test
	void 팀을_지워도_글은_남고_팀_연결만_Mongo와_post_index_양쪽에서_끊긴다() {
		long leader = member();
		long teammate = member();
		CreatedTeam team = newTeam(leader);
		CreatedTeam otherTeam = newTeam(member());
		joinService.joinByCode(teammate, team.inviteCode());
		joinService.joinByCode(teammate, otherTeam.inviteCode());
		String leaderPost = newPost(leader, team);
		String teammatePost = newPost(teammate, team);
		String deletedPost = newPost(teammate, team);
		postService.delete(teammate, deletedPost);
		String otherTeamPost = newPost(teammate, otherTeam);
		String unlinkedPost = newPost(leader, null);
		assertThat(mongoDocument(leaderPost).get("teamId")).isEqualTo(team.id());
		assertThat(indexRow(leaderPost).get("team_id")).isEqualTo(team.id());

		leaveService.delete(leader, team.id());

		for (String linked : List.of(leaderPost, teammatePost, deletedPost)) {
			// Mongo
			assertThat(mongoDocument(linked)).as("글이 남아 있다").isNotNull();
			assertThat(mongoDocument(linked).get("teamId")).as("Mongo posts.teamId").isNull();
			// MariaDB
			assertThat(indexRow(linked).get("team_id")).as("post_index.team_id").isNull();
		}
		// 삭제 표시는 그대로, 글 자체는 지워지지 않는다
		assertThat(mongoDocument(deletedPost).get("deletedAt")).isNotNull();
		assertThat(mongoDocument(leaderPost).get("deletedAt")).isNull();
		assertThat(indexRow(leaderPost).get("deleted_at")).isNull();
		assertThat(indexRow(deletedPost).get("deleted_at")).isNotNull();
		// 다른 팀 글과 팀 없는 글은 그대로
		assertThat(mongoDocument(otherTeamPost).get("teamId")).isEqualTo(otherTeam.id());
		assertThat(indexRow(otherTeamPost).get("team_id")).isEqualTo(otherTeam.id());
		assertThat(mongoDocument(unlinkedPost).get("teamId")).isNull();
		assertThat(postService.get(leaderPost).teamId()).isNull();
	}

	@Test
	void 팀을_지우면_글에_붙던_팀_이름이_사라진다() {
		long leader = member();
		CreatedTeam team = newTeam(leader, "이름이 사라질 팀", null, true);
		String postId = newPost(leader, team);
		GraphQlResponse before = gql(leader, POST, Map.of("id", postId));
		assertGraphQlOk(before);
		assertThat(before.data().path("post").path("teamName").asText()).isEqualTo("이름이 사라질 팀");

		leaveService.delete(leader, team.id());

		GraphQlResponse after = gql(leader, POST, Map.of("id", postId));
		assertGraphQlOk(after);
		assertThat(after.data().path("post").path("teamId").isNull()).isTrue();
		assertThat(after.data().path("post").path("teamName").isNull()).isTrue();
		assertThat(nameService.visibleNames(List.of(team.id()))).isEmpty();
	}

	// ----- 경고 카테고리 (E-22) -----

	@Test
	void 삭제하면_그_팀_카테고리가_모든_경고에서_빠지고_남은_카테고리가_없으면_삭제_처리된다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		CreatedTeam otherTeam = newTeam(member());
		long current = joinedMember(team);
		long both = joinedMember(team);
		joinService.joinByCode(both, otherTeam.inviteCode());
		long selfLeft = joinedMember(team);
		long kicked = joinedMember(team);
		long currentWarning = warnings.warning(current, WEEK_1, team.id());
		long bothShared = warnings.warning(both, WEEK_1, team.id(), otherTeam.id());
		long bothOther = warnings.warning(both, WEEK_2, otherTeam.id());
		long selfLeftWarning = warnings.warning(selfLeft, WEEK_1, team.id());
		long kickedWarning = warnings.warning(kicked, WEEK_1, team.id());
		long leaderWarning = warnings.warning(leader, WEEK_1, team.id());
		leaveService.leave(selfLeft, team.id());
		leaveService.kick(leader, team.id(), kicked);
		WarningRow kickedBefore = warnings.warning(kickedWarning);
		assertThat(kickedBefore.deleteReason()).isEqualTo("KICKED");

		leaveService.delete(leader, team.id());

		// 카테고리가 이 팀뿐이던 경고는 삭제 표시 (현재 팀원·팀장·자진 탈퇴해 경고만 남은 사람 모두)
		for (long id : new long[] {currentWarning, selfLeftWarning, leaderWarning}) {
			WarningRow warning = warnings.warning(id);
			assertThat(warning.alive()).isFalse();
			assertThat(warning.deleteReason()).isEqualTo("TEAM_DELETED");
			assertThat(warnings.category(id, team.id()).removed()).isTrue();
		}
		// 다른 팀 카테고리가 남은 경고는 살아 있고 이 팀 카테고리만 빠진다
		assertThat(warnings.warning(bothShared).alive()).isTrue();
		assertThat(warnings.category(bothShared, team.id()).removed()).isTrue();
		assertThat(warnings.category(bothShared, otherTeam.id()).removed()).isFalse();
		// 다른 팀 경고는 그대로
		assertThat(warnings.warning(bothOther).alive()).isTrue();
		assertThat(warnings.category(bothOther, otherTeam.id()).removed()).isFalse();
		// 먼저 추방으로 삭제된 경고의 삭제 사유는 덮어쓰지 않는다
		WarningRow kickedAfter = warnings.warning(kickedWarning);
		assertThat(kickedAfter.deleteReason()).isEqualTo("KICKED");
		assertThat(kickedAfter.deletedAt()).isEqualTo(kickedBefore.deletedAt());
		assertThat(warnings.aliveCount(both)).isEqualTo(2);
		assertThat(warnings.aliveCount(current)).isZero();
	}

	@Test
	void 팀_삭제와_다른_팀_삭제로_카테고리가_모두_빠진_경고는_삭제_표시된다() {
		long leaderA = member();
		long leaderB = member();
		CreatedTeam teamA = newTeam(leaderA);
		CreatedTeam teamB = newTeam(leaderB);
		long member = joinedMember(teamA);
		joinService.joinByCode(member, teamB.inviteCode());
		long warningId = warnings.warning(member, WEEK_1, teamA.id(), teamB.id());

		leaveService.delete(leaderA, teamA.id());
		assertThat(warnings.warning(warningId).alive()).isTrue();
		leaveService.delete(leaderB, teamB.id());

		WarningRow warning = warnings.warning(warningId);
		assertThat(warning.alive()).isFalse();
		assertThat(warning.deleteReason()).isEqualTo("TEAM_DELETED");
		// 행은 남아 있어 프로필이 "삭제된 팀"을 표시할 수 있다
		assertThat(warnings.categories(warningId)).hasSize(2);
		assertThat(teamRow(teamA.id())).isNotEmpty();
	}

	// ----- GraphQL -----

	@Test
	void deleteTeam은_GraphQL로도_삭제하고_이후_조회는_없는_팀이다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long teammate = joinedMember(team);
		String teamId = Long.toString(team.id());

		assertGraphQlError(gql(teammate, TeamGql.DELETE, Map.of("teamId", teamId)), ErrorCode.FORBIDDEN);
		GraphQlResponse deleted = gql(leader, TeamGql.DELETE, Map.of("teamId", teamId));

		assertGraphQlOk(deleted);
		assertThat(deleted.data().path("deleteTeam").asBoolean()).isTrue();
		assertGraphQlError(gql(leader, TeamGql.TEAM, Map.of("id", teamId)), ErrorCode.NOT_FOUND);
		assertGraphQlError(gql(teammate, TeamGql.TEAM, Map.of("id", teamId)), ErrorCode.NOT_FOUND);
		assertGraphQlError(gql(leader, TeamGql.DELETE, Map.of("teamId", teamId)), ErrorCode.NOT_FOUND);
		assertGraphQlError(gql(member(), TeamGql.JOIN, Map.of("code", team.inviteCode())), ErrorCode.TEAM_CODE_NOT_FOUND);
		assertThat(gql(leader, TeamGql.MY_TEAMS).data().path("myTeams")).isEmpty();
	}

}
