package com.jandilog.team;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
// (추방·아이디 초대·초대 철회·삭제·공개 설정·위임 / 팀 정보·팀원 목록·초대코드·보낸 초대). 참가·탈퇴·글 권한에는 관리자 예외가 없다.
// 관리자 권한으로 한 추방·팀 삭제는 admin_action_log에 남고(팀장이 직접 한 것은 남지 않는다), 나머지는 서버 로그만 남는다
class TeamAdminAccessIntegrationTest extends TeamIntegrationTest {

	private static final LocalDate WEEK_1 = LocalDate.of(2026, 9, 7);
	private static final LocalDate WEEK_2 = LocalDate.of(2026, 9, 14);
	private static final long MISSING_ID = 987_654_321L;

	@Autowired
	private PlatformTransactionManager txManager;

	// 관리자가 쓸 수 있는 팀 기능 열 개
	static Stream<String> adminOperations() {
		return Stream.of("team", "teamMembers", "teamInviteCode", "sentTeamInvitations", "updateTeam", "inviteToTeam",
				"cancelTeamInvitation", "kickTeamMember", "transferTeamLeadership", "deleteTeam");
	}

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
			case "team" -> new Call(TeamGql.TEAM, Map.of("id", id));
			case "teamMembers" -> new Call(TeamGql.TEAM_MEMBERS, Map.of("id", id));
			case "teamInviteCode" -> new Call(TeamGql.INVITE_CODE, Map.of("id", id));
			case "sentTeamInvitations" -> new Call(TeamGql.SENT, Map.of("id", id));
			case "updateTeam" -> new Call(TeamGql.UPDATE, Map.of("id", id, "input", Map.of("isPublic", false)));
			case "inviteToTeam" -> new Call(TeamGql.INVITE, Map.of("id", id, "login", loginOf(invitee)));
			// 철회는 팀 id를 받지 않아서, 팀 A면 실제 초대를 없는 팀이면 없는 초대를 가리킨다
			case "cancelTeamInvitation" ->
					new Call(TeamGql.CANCEL, Map.of("id", Long.toString(teamId == teamA.id() ? invitationId : MISSING_ID)));
			case "kickTeamMember" -> new Call(TeamGql.KICK, Map.of("teamId", id, "memberId", Long.toString(target)));
			case "transferTeamLeadership" ->
					new Call(TeamGql.TRANSFER, Map.of("teamId", id, "newLeaderId", Long.toString(target)));
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
		assertThat(teamRow(teamA.id()).get("is_public")).isIn(true, 1);
		assertThat(isActiveMember(teamA.id(), mate)).isTrue();
		assertThat(activeMemberCount(teamA.id())).isEqualTo(2);
		assertThat(redis.hasKey(banKey(teamA.id(), mate))).isFalse();
		assertThat(invitationRows(teamA.id(), invitee)).extracting(r -> r.get("status")).containsExactly("PENDING");
	}

	private void assertTeamBUntouched() {
		assertThat(teamRow(teamB.id()).get("deleted_at")).isNull();
		assertThat(teamRow(teamB.id()).get("is_public")).isIn(false, 0);
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
	@MethodSource("adminOperations")
	void 팀원_다른_팀_팀장_비소속_일반_회원은_관리자_전용_범위의_기능을_쓸_수_없다(String operation) {
		// 팀원: 팀장 전용 기능 전부와 (팀원이어도) 비팀장이라 막힌다. 팀 정보·팀원 목록은 팀원이면 되므로 따로 확인한다
		if (!operation.equals("teamMembers") && !operation.equals("team")) {
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
	@MethodSource("adminOperations")
	void 없는_팀은_관리자에게도_없는_팀이다(String operation) {
		assertGraphQlError(run(admin, operation, MISSING_ID, mate), ErrorCode.NOT_FOUND);
		assertThat(logs.list).isEmpty();
	}

	@ParameterizedTest
	@MethodSource("adminOperations")
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
	@MethodSource("adminOperations")
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

	// ----- team·공개 설정·위임 -----

	@Test
	void 관리자는_소속이_아닌_팀의_기본_정보를_보고_참가_시각만_null이다() {
		for (CreatedTeam team : List.of(teamA, teamB)) {
			GraphQlResponse response = run(admin, "team", team.id(), mate);

			assertGraphQlOk(response);
			JsonNode info = response.data().path("team");
			assertThat(info.path("id").asText()).isEqualTo(Long.toString(team.id()));
			assertThat(info.path("isLeader").asBoolean()).isFalse();
			assertThat(info.path("memberCount").asInt()).isEqualTo(2);
			assertThat(info.path("leader").path("githubLogin").asText()).isNotBlank();
			assertThat(info.path("createdAt").asText()).isNotBlank();
			assertThat(info.path("joinedAt").isNull()).isTrue();
		}
		// 비공개 팀도 실제 이름이다
		assertThat(run(admin, "team", teamB.id(), mate).data().path("team").path("name").asText())
				.isEqualTo("관리자 확인 팀 B");
		// 읽기라 서버 로그도 DB 기록도 남지 않는다
		assertThat(logs.list).isEmpty();
		assertThat(adminLogs(admin)).isEmpty();
	}

	@Test
	void 팀원인_관리자의_team과_공개_설정_응답은_실제_참가_시각을_준다() {
		joinService.joinByCode(admin, teamA.inviteCode());

		JsonNode info = run(admin, "team", teamA.id(), mate).data().path("team");
		JsonNode updated = run(admin, "updateTeam", teamA.id(), mate).data().path("updateTeam");

		assertThat(info.path("isLeader").asBoolean()).isFalse();
		assertThat(info.path("joinedAt").asText()).isNotBlank();
		assertThat(updated.path("isLeader").asBoolean()).isFalse();
		assertThat(updated.path("joinedAt").asText()).isEqualTo(info.path("joinedAt").asText());
		assertThat(updated.path("isPublic").asBoolean()).isFalse();
	}

	@Test
	void 관리자가_공개_설정을_바꾸면_반영되고_응답은_비소속_관리자_기준이며_서버_로그만_남는다() {
		GraphQlResponse hide = run(admin, "updateTeam", teamA.id(), mate);

		assertGraphQlOk(hide);
		JsonNode updated = hide.data().path("updateTeam");
		assertThat(updated.path("isPublic").asBoolean()).isFalse();
		assertThat(updated.path("isLeader").asBoolean()).isFalse();
		assertThat(updated.path("memberCount").asInt()).isEqualTo(2);
		assertThat(updated.path("joinedAt").isNull()).isTrue();
		assertThat(teamRow(teamA.id()).get("is_public")).isIn(false, 0);

		GraphQlResponse show = gql(admin, TeamGql.UPDATE,
				Map.of("id", Long.toString(teamA.id()), "input", Map.of("isPublic", true)));
		assertGraphQlOk(show);
		assertThat(teamRow(teamA.id()).get("is_public")).isIn(true, 1);

		assertThat(logs.list).hasSize(2);
		assertThat(logs.list.get(0).getFormattedMessage()).contains("action=공개 설정 변경", "adminId=" + admin,
				"teamId=" + teamA.id());
		assertThat(adminLogs(admin)).isEmpty();
		assertThat(isActiveMember(teamA.id(), admin)).isFalse();
	}

	@Test
	void 관리자가_팀장을_위임하면_새_팀장은_현재_팀원이어야_하고_일반_위임과_같은_규칙이다() {
		assertGraphQlError(run(admin, "transferTeamLeadership", teamA.id(), leader), ErrorCode.INVALID_INPUT);
		assertGraphQlError(run(admin, "transferTeamLeadership", teamA.id(), outsider), ErrorCode.TEAM_MEMBER_NOT_FOUND);
		assertTeamAUntouched();
		assertThat(logs.list).isEmpty();

		GraphQlResponse response = run(admin, "transferTeamLeadership", teamA.id(), mate);

		assertGraphQlOk(response);
		JsonNode moved = response.data().path("transferTeamLeadership");
		assertThat(moved.path("leader").path("id").asText()).isEqualTo(Long.toString(mate));
		assertThat(moved.path("isLeader").asBoolean()).isFalse();
		assertThat(moved.path("joinedAt").isNull()).isTrue();
		assertThat(((Number) teamRow(teamA.id()).get("leader_id")).longValue()).isEqualTo(mate);
		// 이전 팀장은 일반 팀원이 되고, 되돌릴 수 없다
		assertThat(isActiveMember(teamA.id(), leader)).isTrue();
		assertGraphQlError(run(leader, "teamInviteCode", teamA.id(), mate), ErrorCode.FORBIDDEN);
		assertGraphQlOk(run(mate, "teamInviteCode", teamA.id(), mate));

		assertThat(logs.list).hasSize(1);
		assertThat(logs.list.get(0).getFormattedMessage()).contains("action=팀장 위임", "adminId=" + admin,
				"teamId=" + teamA.id(), "targetId=" + mate);
		// 위임은 admin_action_log 대상이 아니다
		assertThat(adminLogs(admin)).isEmpty();
	}

	@Test
	void 관리자가_자기가_팀장인_팀의_공개_설정과_위임은_팀장으로서의_사용이라_로그가_없고_응답은_실제_값이다() {
		CreatedTeam own = newTeam(admin, "관리자 본인 팀", null, true);
		long ownMate = joinedMember(own);

		JsonNode updated = run(admin, "updateTeam", own.id(), ownMate).data().path("updateTeam");
		JsonNode moved = run(admin, "transferTeamLeadership", own.id(), ownMate).data().path("transferTeamLeadership");

		assertThat(updated.path("isLeader").asBoolean()).isTrue();
		assertThat(updated.path("joinedAt").asText()).isNotBlank();
		assertThat(moved.path("isLeader").asBoolean()).isFalse();
		assertThat(moved.path("joinedAt").asText()).isNotBlank();
		assertThat(logs.list).isEmpty();
	}

	// ----- admin_action_log: 관리자 권한의 추방·팀 삭제 -----

	private List<Map<String, Object>> adminLogs(long adminId) {
		return jdbc.queryForList("select * from admin_action_log where admin_id = ? order by id", adminId);
	}

	// 대상으로 본 KICK_MEMBER·DELETE_TEAM 기록 (누가 했든)
	private List<Map<String, Object>> teamActionLogs(String targetType, long targetId) {
		return jdbc.queryForList("select * from admin_action_log where action in ('KICK_MEMBER', 'DELETE_TEAM') "
				+ "and target_type = ? and target_id = ?", targetType, Long.toString(targetId));
	}

	private long membershipId(long teamId, long memberId) {
		return ((Number) membershipRows(teamId, memberId).get(0).get("id")).longValue();
	}

	@Test
	void 관리자가_추방하면_KICK_MEMBER가_끝난_소속_행을_대상으로_추방_시각과_함께_기록된다() {
		clock.fixAt(Instant.parse("2026-09-10T03:00:15Z"));

		assertGraphQlOk(run(admin, "kickTeamMember", teamA.id(), mate));

		List<Map<String, Object>> rows = adminLogs(admin);
		assertThat(rows).hasSize(1);
		Map<String, Object> row = rows.get(0);
		assertThat(row.get("action")).isEqualTo("KICK_MEMBER");
		assertThat(row.get("target_type")).isEqualTo("TEAM_MEMBER");
		assertThat(row.get("target_id")).isEqualTo(Long.toString(membershipId(teamA.id(), mate)));
		assertThat(row.get("reason")).isNull();
		assertThat(time(row.get("created_at"))).isEqualTo(LocalDateTime.of(2026, 9, 10, 12, 0, 15));
		// 기록의 대상 행이 실제로 이 추방이다
		assertThat(membershipRows(teamA.id(), mate).get(0).get("leave_type")).isEqualTo("KICKED");
		assertThat(time(membershipRows(teamA.id(), mate).get(0).get("left_at"))).isEqualTo(time(row.get("created_at")));
	}

	@Test
	void 관리자가_팀을_삭제하면_DELETE_TEAM이_팀을_대상으로_기록되고_같은_팀을_다시_지우면_기록이_늘지_않는다() {
		assertGraphQlOk(run(admin, "deleteTeam", teamA.id(), mate));
		assertGraphQlError(run(admin, "deleteTeam", teamA.id(), mate), ErrorCode.NOT_FOUND);

		List<Map<String, Object>> rows = adminLogs(admin);
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).get("action")).isEqualTo("DELETE_TEAM");
		assertThat(rows.get(0).get("target_type")).isEqualTo("TEAM");
		assertThat(rows.get(0).get("target_id")).isEqualTo(Long.toString(teamA.id()));
		assertThat(rows.get(0).get("reason")).isNull();
		assertThat(rows.get(0).get("created_at")).isNotNull();
	}

	@Test
	void 팀장이_직접_추방하고_삭제한_것은_기록하지_않는다() {
		assertGraphQlOk(run(leader, "kickTeamMember", teamA.id(), mate));
		assertGraphQlOk(run(otherLeader, "deleteTeam", teamB.id(), otherMate));

		assertThat(teamActionLogs("TEAM_MEMBER", membershipId(teamA.id(), mate))).isEmpty();
		assertThat(teamActionLogs("TEAM", teamB.id())).isEmpty();
		assertThat(adminLogs(leader)).isEmpty();
		assertThat(adminLogs(otherLeader)).isEmpty();
	}

	@Test
	void 관리자가_자기가_팀장인_팀에서_추방하고_삭제한_것도_팀장으로서의_사용이라_기록하지_않는다() {
		CreatedTeam own = newTeam(admin, "관리자 본인 팀", null, true);
		long ownMate = joinedMember(own);

		assertGraphQlOk(run(admin, "kickTeamMember", own.id(), ownMate));
		assertGraphQlOk(run(admin, "deleteTeam", own.id(), ownMate));

		assertThat(adminLogs(admin)).isEmpty();
		assertThat(logs.list).isEmpty();
	}

	@Test
	void 거절된_관리자_호출은_기록을_남기지_않는다() {
		// 팀장 추방(INVALID_INPUT), 팀원이 아닌 대상(TEAM_MEMBER_NOT_FOUND), 없는 팀(NOT_FOUND)
		assertGraphQlError(run(admin, "kickTeamMember", teamA.id(), leader), ErrorCode.INVALID_INPUT);
		assertGraphQlError(run(admin, "kickTeamMember", teamA.id(), outsider), ErrorCode.TEAM_MEMBER_NOT_FOUND);
		assertGraphQlError(run(admin, "kickTeamMember", MISSING_ID, mate), ErrorCode.NOT_FOUND);
		assertGraphQlError(run(admin, "deleteTeam", MISSING_ID, mate), ErrorCode.NOT_FOUND);
		// 일반 팀원·다른 팀 팀장의 시도
		assertGraphQlError(run(mate, "kickTeamMember", teamA.id(), mate), ErrorCode.FORBIDDEN);
		assertGraphQlError(run(otherLeader, "deleteTeam", teamA.id(), mate), ErrorCode.FORBIDDEN);

		assertThat(adminLogs(admin)).isEmpty();
		assertThat(teamActionLogs("TEAM", teamA.id())).isEmpty();
		assertThat(teamActionLogs("TEAM_MEMBER", membershipId(teamA.id(), mate))).isEmpty();
		assertTeamAUntouched();
	}

	@Test
	void 추방과_팀_삭제가_아닌_관리자_사용은_admin_action_log에_남기지_않는다() {
		assertGraphQlOk(run(admin, "team", teamA.id(), mate));
		assertGraphQlOk(run(admin, "teamMembers", teamA.id(), mate));
		assertGraphQlOk(run(admin, "teamInviteCode", teamA.id(), mate));
		assertGraphQlOk(run(admin, "sentTeamInvitations", teamA.id(), mate));
		assertGraphQlOk(run(admin, "updateTeam", teamA.id(), mate));
		assertGraphQlOk(run(admin, "cancelTeamInvitation", teamA.id(), mate));
		assertGraphQlOk(run(admin, "inviteToTeam", teamA.id(), mate));
		assertGraphQlOk(run(admin, "transferTeamLeadership", teamA.id(), mate));

		assertThat(adminLogs(admin)).isEmpty();
	}

	@Test
	void 관리자_추방이_들어_있던_트랜잭션이_롤백되면_기록과_추방과_차단_키가_모두_사라진다() {
		new TransactionTemplate(txManager).executeWithoutResult(status -> {
			leaveService.kick(admin, teamA.id(), mate);
			status.setRollbackOnly();
		});

		assertThat(adminLogs(admin)).isEmpty();
		assertThat(isActiveMember(teamA.id(), mate)).isTrue();
		assertThat(redis.hasKey(banKey(teamA.id(), mate))).isFalse();
		assertTeamAUntouched();
	}

	@Test
	void 관리자_팀_삭제가_들어_있던_트랜잭션이_롤백되면_기록과_삭제가_모두_사라진다() {
		new TransactionTemplate(txManager).executeWithoutResult(status -> {
			leaveService.delete(admin, teamA.id());
			status.setRollbackOnly();
		});

		assertThat(adminLogs(admin)).isEmpty();
		assertThat(teamActionLogs("TEAM", teamA.id())).isEmpty();
		assertTeamAUntouched();
	}
}
