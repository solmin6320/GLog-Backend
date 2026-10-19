package com.jandilog.team;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.member.domain.MemberRole;
import com.jandilog.member.domain.MemberStatus;
import com.jandilog.post.domain.PostType;
import com.jandilog.post.dto.CreatePostInput;
import com.jandilog.post.dto.PostSectionsInput;
import com.jandilog.post.dto.UpdatePostInput;
import com.jandilog.team.service.TeamAccessService;
import com.jandilog.team.support.TeamGql;
import com.jandilog.team.support.TeamIntegrationTest;
import com.jandilog.team.support.TeamWarningFixture.WarningRow;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

// 팀 권한 모델: 팀장은 자기 팀에서만, 시스템 관리자는 모든 팀에서 팀장 전용 기능과 팀 조회를 쓴다
// (추방·아이디 초대·초대 철회·삭제 / 팀원 목록·초대코드·보낸 초대). 참가·탈퇴·글 권한에는 관리자 예외가 없다
class TeamAdminAccessIntegrationTest extends TeamIntegrationTest {

	private static final LocalDate WEEK_1 = LocalDate.of(2026, 9, 7);
	private static final LocalDate WEEK_2 = LocalDate.of(2026, 9, 14);
	private static final long MISSING_ID = 987_654_321L;

	// 관리자가 쓸 수 있는 팀 기능 일곱 개
	private static final String[] ADMIN_OPERATIONS = {"teamMembers", "teamInviteCode", "sentTeamInvitations",
			"inviteToTeam", "cancelTeamInvitation", "kickTeamMember", "deleteTeam"};

	private record Call(String query, Map<String, Object> variables) {
	}

	private long leader;
	private long mate;
	private long otherLeader;
	private long otherMate;
	private long outsider;
	private long admin;
	private long invitee;
	private CreatedTeam teamA;
	private CreatedTeam teamB;
	private long invitationId;
	private ListAppender<ILoggingEvent> logs;

	@BeforeEach
	void setUpTeams() {
		leader = member();
		mate = member();
		otherLeader = member();
		otherMate = member();
		outsider = member();
		admin = adminMember();
		invitee = member();
		teamA = newTeam(leader, "관리자 확인 팀 A", null, true);
		teamB = newTeam(otherLeader, "관리자 확인 팀 B", null, false);
		joinService.joinByCode(mate, teamA.inviteCode());
		joinService.joinByCode(otherMate, teamB.inviteCode());
		invitationId = invitationService.invite(leader, teamA.id(), loginOf(invitee)).id();

		logs = new ListAppender<>();
		logs.start();
		((Logger) LoggerFactory.getLogger(TeamAccessService.class)).addAppender(logs);
	}

	@AfterEach
	void detachLogs() {
		((Logger) LoggerFactory.getLogger(TeamAccessService.class)).detachAppender(logs);
	}

	// ----- 호출 도우미 -----

	private Call call(String operation, long teamId, long target) {
		String id = Long.toString(teamId);
		return switch (operation) {
			case "teamMembers" -> new Call(TeamGql.TEAM_MEMBERS, Map.of("id", id));
			case "teamInviteCode" -> new Call(TeamGql.INVITE_CODE, Map.of("id", id));
			case "sentTeamInvitations" -> new Call(TeamGql.SENT, Map.of("id", id));
			case "inviteToTeam" -> new Call(TeamGql.INVITE, Map.of("id", id, "login", loginOf(invitee)));
			// 철회는 팀 id를 받지 않아서, 팀 A면 실제 초대를 없는 팀이면 없는 초대를 가리킨다
			case "cancelTeamInvitation" ->
					new Call(TeamGql.CANCEL, Map.of("id", Long.toString(teamId == teamA.id() ? invitationId : MISSING_ID)));
			case "kickTeamMember" -> new Call(TeamGql.KICK, Map.of("teamId", id, "memberId", Long.toString(target)));
			case "deleteTeam" -> new Call(TeamGql.DELETE, Map.of("teamId", id));
			default -> throw new IllegalArgumentException(operation);
		};
	}

	private GraphQlResponse run(long caller, String operation, long teamId, long target) {
		Call call = call(operation, teamId, target);
		return gql(caller, call.query(), call.variables());
	}

	// 팀 A가 호출 전과 같은 상태인가 (거부된 호출은 아무것도 바꾸지 못한다)
	private void assertTeamAUntouched() {
		assertThat(teamRow(teamA.id()).get("deleted_at")).isNull();
		assertThat(((Number) teamRow(teamA.id()).get("leader_id")).longValue()).isEqualTo(leader);
		assertThat(isActiveMember(teamA.id(), mate)).isTrue();
		assertThat(activeMemberCount(teamA.id())).isEqualTo(2);
		assertThat(redis.hasKey(banKey(teamA.id(), mate))).isFalse();
		assertThat(invitationRows(teamA.id(), invitee)).extracting(r -> r.get("status")).containsExactly("PENDING");
	}

	private void assertTeamBUntouched() {
		assertThat(teamRow(teamB.id()).get("deleted_at")).isNull();
		assertThat(isActiveMember(teamB.id(), otherMate)).isTrue();
		assertThat(activeMemberCount(teamB.id())).isEqualTo(2);
		assertThat(redis.hasKey(banKey(teamB.id(), otherMate))).isFalse();
	}

	// ----- 팀장의 범위: 자기 팀 안에서만 -----

	@Test
	void 다른_팀의_팀장은_이_팀의_팀원을_추방할_수_없다() {
		assertGraphQlError(run(otherLeader, "kickTeamMember", teamA.id(), mate), ErrorCode.FORBIDDEN);
		assertApiError(() -> leaveService.kick(otherLeader, teamA.id(), mate), ErrorCode.FORBIDDEN);
		assertGraphQlError(run(leader, "kickTeamMember", teamB.id(), otherMate), ErrorCode.FORBIDDEN);

		assertTeamAUntouched();
		assertTeamBUntouched();
	}

	@ParameterizedTest
	@ValueSource(strings = {"teamMembers", "teamInviteCode", "sentTeamInvitations", "inviteToTeam",
			"cancelTeamInvitation", "kickTeamMember", "deleteTeam"})
	void 팀원_다른_팀_팀장_비소속_일반_회원은_관리자_전용_범위의_기능을_쓸_수_없다(String operation) {
		// 팀원: 팀장 전용 기능 전부와 (팀원이어도) 비팀장이라 막힌다. 팀원 목록은 팀원이면 되므로 따로 확인한다
		if (!operation.equals("teamMembers")) {
			assertGraphQlError(run(mate, operation, teamA.id(), mate), ErrorCode.FORBIDDEN);
		}
		assertGraphQlError(run(otherLeader, operation, teamA.id(), mate), ErrorCode.FORBIDDEN);
		assertGraphQlError(run(otherMate, operation, teamA.id(), mate), ErrorCode.FORBIDDEN);
		assertGraphQlError(run(outsider, operation, teamA.id(), mate), ErrorCode.FORBIDDEN);

		assertTeamAUntouched();
		assertThat(logs.list).isEmpty();
	}

	// ----- 관리자: 비소속이어도 모든 팀에서 -----

	@Test
	void 관리자는_소속이_아닌_팀에서도_팀원을_추방하고_일반_추방과_같은_후속_처리가_된다() {
		// 같은 상황을 팀 A는 관리자가, 팀 B는 팀장이 추방해서 결과가 같은지 비교한다
		long onlyA = warnings.warning(mate, WEEK_1, teamA.id());
		long bothA = warnings.warning(mate, WEEK_2, teamA.id(), teamB.id());
		long onlyB = warnings.warning(otherMate, WEEK_1, teamB.id());
		long bothB = warnings.warning(otherMate, WEEK_2, teamB.id(), teamA.id());

		GraphQlResponse byAdmin = run(admin, "kickTeamMember", teamA.id(), mate);
		GraphQlResponse byLeader = run(otherLeader, "kickTeamMember", teamB.id(), otherMate);

		assertGraphQlOk(byAdmin);
		assertGraphQlOk(byLeader);
		assertThat(byAdmin.data().path("kickTeamMember").asBoolean()).isTrue();
		assertKicked(teamA.id(), mate, onlyA, bothA, teamB.id());
		assertKicked(teamB.id(), otherMate, onlyB, bothB, teamA.id());
		// 관리자가 팀원이 되지는 않는다
		assertThat(isActiveMember(teamA.id(), admin)).isFalse();
		assertThat(teamService.myTeams(admin)).isEmpty();
	}

	// 추방 후속 처리: 소속 KICKED, 1년 차단 키, 그 팀만 붙은 경고는 삭제, 다른 팀이 남은 경고는 카테고리만 제거
	private void assertKicked(long teamId, long target, long onlyThisTeamWarning, long bothTeamsWarning, long otherTeamId) {
		List<Map<String, Object>> rows = membershipRows(teamId, target);
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).get("left_at")).isNotNull();
		assertThat(rows.get(0).get("leave_type")).isEqualTo("KICKED");

		String key = banKey(teamId, target);
		assertThat(redis.hasKey(key)).isTrue();
		Long ttlSeconds = redis.getExpire(key, TimeUnit.SECONDS);
		assertThat(ttlSeconds).isNotNull().isGreaterThan(Duration.ofDays(365).minusMinutes(10).toSeconds())
				.isLessThanOrEqualTo(Duration.ofDays(365).toSeconds());
		assertApiError(() -> joinService.joinByCode(target, codeOf(teamId)), ErrorCode.TEAM_JOIN_BLOCKED);

		WarningRow only = warnings.warning(onlyThisTeamWarning);
		assertThat(only.alive()).isFalse();
		assertThat(only.deleteReason()).isEqualTo("KICKED");
		assertThat(warnings.category(onlyThisTeamWarning, teamId).removed()).isTrue();

		WarningRow both = warnings.warning(bothTeamsWarning);
		assertThat(both.alive()).isTrue();
		assertThat(warnings.category(bothTeamsWarning, teamId).removed()).isTrue();
		assertThat(warnings.category(bothTeamsWarning, otherTeamId).removed()).isFalse();
		assertThat(warnings.aliveCount(target)).isEqualTo(1);
	}

	private String codeOf(long teamId) {
		return teamId == teamA.id() ? teamA.inviteCode() : teamB.inviteCode();
	}

	@Test
	void 관리자가_추방해도_아이디_초대로는_다시_돌아올_수_있고_경고_카테고리가_복원된다() {
		long warning = warnings.warning(mate, WEEK_1, teamA.id());
		assertGraphQlOk(run(admin, "kickTeamMember", teamA.id(), mate));
		assertThat(warnings.warning(warning).alive()).isFalse();

		inviteAndAccept(leader, teamA, mate);

		assertThat(isActiveMember(teamA.id(), mate)).isTrue();
		assertThat(warnings.warning(warning).alive()).isTrue();
		assertThat(warnings.category(warning, teamA.id()).removed()).isFalse();
	}

	@Test
	void 관리자가_팀장을_추방하려_하면_거부되고_아무것도_바뀌지_않는다() {
		warnings.warning(leader, WEEK_1, teamA.id());

		assertGraphQlError(run(admin, "kickTeamMember", teamA.id(), leader), ErrorCode.INVALID_INPUT);
		assertApiError(() -> leaveService.kick(admin, teamA.id(), leader), ErrorCode.INVALID_INPUT);

		assertTeamAUntouched();
		assertThat(isActiveMember(teamA.id(), leader)).isTrue();
		assertThat(redis.hasKey(banKey(teamA.id(), leader))).isFalse();
		assertThat(warnings.aliveCount(leader)).isEqualTo(1);
		assertThat(logs.list).isEmpty();
	}

	@Test
	void 팀장을_빼려면_위임한_뒤_관리자가_이전_팀장을_추방한다() {
		leaveService.transferLeadership(leader, teamA.id(), mate);

		assertGraphQlOk(run(admin, "kickTeamMember", teamA.id(), leader));

		assertThat(isActiveMember(teamA.id(), leader)).isFalse();
		assertThat(redis.hasKey(banKey(teamA.id(), leader))).isTrue();
		// 새 팀장은 그대로
		assertThat(((Number) teamRow(teamA.id()).get("leader_id")).longValue()).isEqualTo(mate);
		assertApiError(() -> leaveService.kick(admin, teamA.id(), mate), ErrorCode.INVALID_INPUT);
	}

	@Test
	void 관리자는_소속이_아니어도_공개든_비공개든_팀원_목록_초대코드_보낸_초대를_본다() {
		for (CreatedTeam team : List.of(teamA, teamB)) {
			String id = Long.toString(team.id());

			GraphQlResponse members = gql(admin, TeamGql.TEAM_MEMBERS, Map.of("id", id));
			assertGraphQlOk(members);
			JsonNode items = members.data().path("teamMembers").path("items");
			assertThat(items).hasSize(2);
			assertThat(items.get(0).path("isLeader").asBoolean()).isTrue();
			assertThat(items.get(1).path("isLeader").asBoolean()).isFalse();

			GraphQlResponse code = gql(admin, TeamGql.INVITE_CODE, Map.of("id", id));
			assertGraphQlOk(code);
			assertThat(code.data().path("teamInviteCode").asText()).isEqualTo(team.inviteCode());

			assertGraphQlOk(gql(admin, TeamGql.SENT, Map.of("id", id)));
		}
		GraphQlResponse sent = gql(admin, TeamGql.SENT, Map.of("id", Long.toString(teamA.id())));
		assertThat(sent.data().path("sentTeamInvitations").path("items")).hasSize(1);
		assertThat(sent.data().path("sentTeamInvitations").path("items").get(0).path("invitee").path("githubLogin").asText())
				.isEqualTo(loginOf(invitee));
	}

	@Test
	void 관리자가_아이디로_초대하면_받은_초대에_뜨고_수락하면_참가하며_같은_검증을_받는다() {
		long another = member();

		GraphQlResponse response = gql(admin, TeamGql.INVITE,
				Map.of("id", Long.toString(teamA.id()), "login", loginOf(another)));

		assertGraphQlOk(response);
		long sentId = Long.parseLong(response.data().path("inviteToTeam").path("id").asText());
		assertThat(allReceived(another)).hasSize(1);
		assertThat(invitationRows(teamA.id(), another)).extracting(r -> r.get("status")).containsExactly("PENDING");
		// 일반 초대와 같은 규칙: 이미 팀원, 대기 중인 초대 중복, 없는 아이디
		assertGraphQlError(gql(admin, TeamGql.INVITE, Map.of("id", Long.toString(teamA.id()), "login", loginOf(mate))),
				ErrorCode.INVITEE_ALREADY_MEMBER);
		assertGraphQlError(gql(admin, TeamGql.INVITE, Map.of("id", Long.toString(teamA.id()), "login", loginOf(another))),
				ErrorCode.INVITATION_DUPLICATE);
		assertGraphQlError(gql(admin, TeamGql.INVITE, Map.of("id", Long.toString(teamA.id()), "login", "no-such-user-zz")),
				ErrorCode.INVITEE_NOT_FOUND);

		invitationService.accept(another, sentId);

		assertThat(isActiveMember(teamA.id(), another)).isTrue();
		assertThat(isActiveMember(teamA.id(), admin)).isFalse();
	}

	@Test
	void 관리자가_보낸_초대를_철회하면_초대가_지워지고_이미_응답한_초대는_철회할_수_없다() {
		assertGraphQlOk(run(admin, "cancelTeamInvitation", teamA.id(), mate));

		assertThat(invitationRows(teamA.id(), invitee)).isEmpty();
		assertThat(allReceived(invitee)).isEmpty();
		// 같은 초대를 다시 철회하면 이미 없는 초대다
		assertGraphQlError(run(admin, "cancelTeamInvitation", teamA.id(), mate), ErrorCode.NOT_FOUND);
	}

	@Test
	void 관리자가_팀을_삭제하면_일반_삭제와_같이_소속_경고_카테고리_글_연결이_정리된다() {
		long onlyA = warnings.warning(mate, WEEK_1, teamA.id());
		long both = warnings.warning(mate, WEEK_2, teamA.id(), teamB.id());
		String post = newPost(mate, teamA);
		long selfLeft = joinedMember(teamA);
		leaveService.leave(selfLeft, teamA.id());

		assertGraphQlOk(run(admin, "deleteTeam", teamA.id(), mate));

		assertThat(teamRow(teamA.id()).get("deleted_at")).isNotNull();
		assertThat(activeMemberCount(teamA.id())).isZero();
		assertThat(membershipRows(teamA.id(), leader).get(0).get("leave_type")).isEqualTo("TEAM_DELETED");
		assertThat(membershipRows(teamA.id(), mate).get(0).get("leave_type")).isEqualTo("TEAM_DELETED");
		assertThat(membershipRows(teamA.id(), selfLeft).get(0).get("leave_type")).isEqualTo("SELF");
		assertThat(warnings.warning(onlyA).alive()).isFalse();
		assertThat(warnings.warning(both).alive()).isTrue();
		assertThat(warnings.category(both, teamA.id()).removed()).isTrue();
		assertThat(warnings.category(both, teamB.id()).removed()).isFalse();
		assertThat(postService.get(post).teamId()).isNull();
		assertThat(teamService.myTeams(leader)).isEmpty();
		assertThat(teamService.myTeams(admin)).isEmpty();
	}

	@Test
	void 관리자는_비공개_팀에도_같은_권한을_쓴다() {
		assertGraphQlOk(run(admin, "kickTeamMember", teamB.id(), otherMate));
		assertGraphQlOk(run(admin, "deleteTeam", teamB.id(), otherMate));

		assertThat(isActiveMember(teamB.id(), otherMate)).isFalse();
		assertThat(teamRow(teamB.id()).get("deleted_at")).isNotNull();
	}

	// ----- 관리자 예외가 없는 곳 -----

	@Test
	void 참가_초대_수락_거절_탈퇴_내_팀_목록에는_관리자_예외가_없다() {
		// 팀 기능을 써도 소속이 생기지 않고, 소속이 아니면 탈퇴할 수 없다
		assertGraphQlOk(run(admin, "kickTeamMember", teamA.id(), mate));
		assertThat(gql(admin, TeamGql.MY_TEAMS).data().path("myTeams")).isEmpty();
		assertGraphQlError(gql(admin, TeamGql.LEAVE, Map.of("teamId", Long.toString(teamA.id()))), ErrorCode.FORBIDDEN);
		// 남에게 온 초대는 수락도 거절도 못 한다
		assertGraphQlError(gql(admin, TeamGql.ACCEPT, Map.of("id", Long.toString(invitationId))), ErrorCode.NOT_FOUND);
		assertGraphQlError(gql(admin, TeamGql.DECLINE, Map.of("id", Long.toString(invitationId))), ErrorCode.NOT_FOUND);
		assertThat(invitationRows(teamA.id(), invitee)).extracting(r -> r.get("status")).containsExactly("PENDING");
		// 초대코드 참가는 일반 회원과 같다: 참가하면 팀원이 되고, 일반 추방을 당하면 1년 차단된다
		assertGraphQlOk(gql(admin, TeamGql.JOIN, Map.of("code", teamA.inviteCode())));
		assertThat(isActiveMember(teamA.id(), admin)).isTrue();
		leaveService.kick(leader, teamA.id(), admin);
		assertGraphQlError(gql(admin, TeamGql.JOIN, Map.of("code", teamA.inviteCode())), ErrorCode.TEAM_JOIN_BLOCKED);
	}

	@Test
	void 관리자도_팀원이_아니면_글을_팀에_연결하거나_팀장처럼_남의_글을_고치지_못한다() {
		String matePost = newPost(mate, teamA);
		CreatePostInput withTeam = new CreatePostInput(PostType.DEVLOG, "관리자 글", new PostSectionsInput(null, null, null, "한 일", "배운 점"),
				List.of(), List.of(), Long.toString(teamA.id()));
		UpdatePostInput edit = new UpdatePostInput("바꾼 제목", new PostSectionsInput(null, null, null, "한 일", "배운 점"),
				List.of(), List.of(), Long.toString(teamA.id()));

		assertApiError(() -> postService.create(admin, withTeam), ErrorCode.POST_TEAM_INVALID);
		assertApiError(() -> postService.update(admin, matePost, edit), ErrorCode.FORBIDDEN);
		assertApiError(() -> postService.delete(admin, matePost), ErrorCode.FORBIDDEN);
		// 팀장은 자기 팀 글을 계속 고칠 수 있다
		assertThat(postService.update(leader, matePost, edit).title()).isEqualTo("바꾼 제목");
	}

	// ----- 없는 팀·삭제된 팀 -----

	@ParameterizedTest
	@ValueSource(strings = {"teamMembers", "teamInviteCode", "sentTeamInvitations", "inviteToTeam", "cancelTeamInvitation",
			"kickTeamMember", "deleteTeam"})
	void 없는_팀은_관리자에게도_없는_팀이다(String operation) {
		assertGraphQlError(run(admin, operation, MISSING_ID, mate), ErrorCode.NOT_FOUND);
		assertThat(logs.list).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = {"teamMembers", "teamInviteCode", "sentTeamInvitations", "inviteToTeam", "cancelTeamInvitation",
			"kickTeamMember", "deleteTeam"})
	void 삭제된_팀은_관리자에게도_없는_팀이다(String operation) {
		leaveService.delete(leader, teamA.id());

		assertGraphQlError(run(admin, operation, teamA.id(), mate), ErrorCode.NOT_FOUND);
		assertThat(logs.list).isEmpty();
	}

	@Test
	void 숫자가_아닌_팀_id도_관리자에게는_없는_팀이다() {
		assertGraphQlError(gql(admin, TeamGql.TEAM_MEMBERS, Map.of("id", "abc")), ErrorCode.NOT_FOUND);
		assertGraphQlError(gql(admin, TeamGql.DELETE, Map.of("teamId", "0")), ErrorCode.NOT_FOUND);
	}

	// ----- 관리자 판별은 서버가 요청마다 DB로 -----

	@Test
	void 관리자가_일반_회원으로_바뀌면_다음_요청부터_예외가_사라진다() {
		assertGraphQlOk(run(admin, "teamInviteCode", teamA.id(), mate));

		members.setRole(admin, MemberRole.MEMBER);

		assertGraphQlError(run(admin, "teamInviteCode", teamA.id(), mate), ErrorCode.FORBIDDEN);
		assertGraphQlError(run(admin, "kickTeamMember", teamA.id(), mate), ErrorCode.FORBIDDEN);
		assertTeamAUntouched();
	}

	@Test
	void 관리자_계정이_ACTIVE가_아니면_서비스도_관리자로_보지_않는다() {
		members.setStatus(admin, MemberStatus.PENDING);

		assertApiError(() -> leaveService.kick(admin, teamA.id(), mate), ErrorCode.FORBIDDEN);
		assertApiError(() -> teamService.inviteCode(admin, teamA.id()), ErrorCode.FORBIDDEN);
		assertTeamAUntouched();
	}

	@ParameterizedTest
	@ValueSource(strings = {"teamMembers", "teamInviteCode", "sentTeamInvitations", "inviteToTeam",
			"cancelTeamInvitation", "kickTeamMember", "deleteTeam"})
	void 역할이_관리자여도_승인_대기나_거절_계정은_팀_기능을_쓸_수_없다(String operation) {
		members.setStatus(admin, MemberStatus.PENDING);
		assertGraphQlError(run(admin, operation, teamA.id(), mate), ErrorCode.ACCOUNT_PENDING);

		members.setStatus(admin, MemberStatus.REJECTED);
		assertGraphQlError(run(admin, operation, teamA.id(), mate), ErrorCode.ACCOUNT_REJECTED);

		assertTeamAUntouched();
		assertThat(logs.list).isEmpty();
	}

	// ----- 서버 로그 -----

	@Test
	void 관리자가_쓴_팀_기능은_관리자_팀_대상_id만_서버_로그에_남고_초대코드는_남지_않는다() {
		assertGraphQlOk(run(admin, "kickTeamMember", teamA.id(), mate));
		assertGraphQlOk(run(admin, "teamInviteCode", teamB.id(), otherMate));
		assertGraphQlOk(run(admin, "teamMembers", teamB.id(), otherMate));

		List<String> messages = logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
		assertThat(messages).hasSize(2);
		assertThat(messages.get(0)).contains("action=추방", "adminId=" + admin, "teamId=" + teamA.id(), "targetId=" + mate);
		assertThat(messages.get(1)).contains("action=초대코드 확인", "adminId=" + admin, "teamId=" + teamB.id());
		assertThat(messages).noneMatch(m -> m.contains(teamA.inviteCode()) || m.contains(teamB.inviteCode()));
		assertThat(logs.list).allMatch(e -> e.getLevel() == Level.INFO);
	}

	@Test
	void 팀장이_자기_팀에서_쓴_기능은_관리자_로그가_남지_않는다() {
		assertGraphQlOk(run(leader, "teamInviteCode", teamA.id(), mate));
		assertGraphQlOk(run(leader, "kickTeamMember", teamA.id(), mate));

		assertThat(logs.list).isEmpty();
	}

	@Test
	void 관리자가_자기가_팀장인_팀에서_쓰면_팀장으로서의_사용이라_관리자_로그가_남지_않는다() {
		CreatedTeam own = newTeam(admin, "관리자 본인 팀", null, true);
		long ownMate = joinedMember(own);

		assertGraphQlOk(run(admin, "kickTeamMember", own.id(), ownMate));

		assertThat(logs.list).isEmpty();
		assertThat(isActiveMember(own.id(), ownMate)).isFalse();
	}
}
