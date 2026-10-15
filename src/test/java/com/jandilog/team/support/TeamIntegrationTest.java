package com.jandilog.team.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.member.domain.MemberRole;
import com.jandilog.member.domain.MemberStatus;
import com.jandilog.post.domain.Post;
import com.jandilog.post.domain.PostType;
import com.jandilog.post.dto.CreatePostInput;
import com.jandilog.post.dto.CursorPage;
import com.jandilog.post.dto.PostSectionsInput;
import com.jandilog.post.service.PostService;
import com.jandilog.team.dto.CreateTeamInput;
import com.jandilog.team.dto.CreateTeamPayload;
import com.jandilog.team.dto.ReceivedInvitationResponse;
import com.jandilog.team.dto.SentInvitationResponse;
import com.jandilog.team.dto.TeamMemberResponse;
import com.jandilog.team.service.TeamBanService;
import com.jandilog.team.service.TeamInvitationService;
import com.jandilog.team.service.TeamJoinService;
import com.jandilog.team.service.TeamLeaveService;
import com.jandilog.team.service.TeamMembershipService;
import com.jandilog.team.service.TeamNameService;
import com.jandilog.team.service.TeamService;
import com.jandilog.testsupport.auth.AuthIntegrationTest;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;

// 팀 통합 테스트 공통 바탕: 서비스 직접 호출 도우미, 팀 GraphQL 호출, 내가 만든 데이터만 지우는 정리.
// 팀·회원·경고·초대·글은 테스트가 만든 것만 추적해서 끝에 FK 순서대로 지운다 (공유 DB의 다른 데이터는 건드리지 않는다)
public abstract class TeamIntegrationTest extends AuthIntegrationTest {

	// 서비스가 돌려주는 팀 생성 결과를 id와 초대코드로만 들고 다닌다
	public record CreatedTeam(long id, String inviteCode) {
	}

	@Autowired
	protected TeamService teamService;
	@Autowired
	protected TeamJoinService joinService;
	@Autowired
	protected TeamInvitationService invitationService;
	@Autowired
	protected TeamLeaveService leaveService;
	@Autowired
	protected TeamBanService banService;
	@Autowired
	protected TeamNameService nameService;
	@Autowired
	protected TeamMembershipService membershipService;
	@Autowired
	protected MongoTemplate mongo;
	@Autowired
	protected PostService postService;

	protected TeamWarningFixture warnings;

	private final List<Long> teamIds = new ArrayList<>();
	private final List<Long> memberIds = new ArrayList<>();
	private final AtomicInteger sequence = new AtomicInteger();

	@BeforeEach
	protected void setUpTeamSupport() {
		warnings = new TeamWarningFixture(jdbc);
	}

	@AfterEach
	protected void cleanTeamData() {
		List<Long> members;
		List<Long> teams;
		synchronized (this) {
			members = List.copyOf(memberIds);
			teams = new ArrayList<>(teamIds);
			memberIds.clear();
			teamIds.clear();
		}
		if (members.isEmpty() && teams.isEmpty()) {
			return;
		}
		if (!members.isEmpty()) {
			// GraphQL로 만든 팀도 팀장 id로 찾아 함께 지운다
			teams.addAll(jdbc.queryForList("select id from team where leader_id in (" + in(members) + ")", Long.class));
		}
		Set<Long> uniqueTeams = Set.copyOf(teams);
		for (Long teamId : uniqueTeams) {
			Set<String> keys = redis.keys("ban:" + teamId + ":*");
			if (keys != null && !keys.isEmpty()) {
				redis.delete(keys);
			}
		}
		if (!members.isEmpty()) {
			mongo.remove(Query.query(Criteria.where("authorId").in(members)), Post.class);
		}
		String m = members.isEmpty() ? "-1" : in(members);
		String t = uniqueTeams.isEmpty() ? "-1" : in(uniqueTeams);
		jdbc.update("delete from warning_team where team_id in (" + t + ") or warning_id in (select id from warning where member_id in (" + m + "))");
		jdbc.update("delete from warning where member_id in (" + m + ")");
		jdbc.update("delete from post_index where author_id in (" + m + ") or team_id in (" + t + ")");
		jdbc.update("delete from team_invitation where invitee_id in (" + m + ") or team_id in (" + t + ")");
		jdbc.update("delete from team_member where member_id in (" + m + ") or team_id in (" + t + ")");
		jdbc.update("delete from team where id in (" + t + ") or leader_id in (" + m + ")");
	}

	private static String in(Collection<Long> ids) {
		return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
	}

	// ----- 회원 -----

	protected synchronized long member() {
		long id = members.active();
		memberIds.add(id);
		return id;
	}

	protected synchronized long memberWithLogin(String login) {
		long id = members.insert(MemberStatus.ACTIVE, MemberRole.MEMBER, login, login);
		memberIds.add(id);
		return id;
	}

	protected synchronized long pendingMember() {
		long id = members.pending();
		memberIds.add(id);
		return id;
	}

	protected synchronized long rejectedMember() {
		long id = members.rejected();
		memberIds.add(id);
		return id;
	}

	protected synchronized long adminMember() {
		long id = members.admin();
		memberIds.add(id);
		return id;
	}

	protected String loginOf(long memberId) {
		return jdbc.queryForObject("select github_login from member where id = ?", String.class, memberId);
	}

	protected LocalDateTime firstTeamJoinedAt(long memberId) {
		return jdbc.queryForObject("select first_team_joined_at from member where id = ?", LocalDateTime.class, memberId);
	}

	// ----- 팀 만들기·참가 -----

	protected CreatedTeam newTeam(long leaderId) {
		return newTeam(leaderId, "팀" + members.tag() + sequence.incrementAndGet(), null, true);
	}

	protected CreatedTeam newTeam(long leaderId, String name, String description, Boolean isPublic) {
		CreateTeamPayload payload = teamService.create(leaderId, new CreateTeamInput(name, description, isPublic));
		trackTeam(payload.team().id());
		return new CreatedTeam(payload.team().id(), payload.inviteCode());
	}

	protected synchronized void trackTeam(long teamId) {
		teamIds.add(teamId);
	}

	// 새 회원을 만들어 초대코드로 참가시킨다
	protected long joinedMember(CreatedTeam team) {
		long id = member();
		joinService.joinByCode(id, team.inviteCode());
		return id;
	}

	// 아이디 초대 → 수락까지 한 번에. 초대 id를 돌려준다
	protected void inviteAndAccept(long leaderId, CreatedTeam team, long inviteeId) {
		SentInvitationResponse invitation = invitationService.invite(leaderId, team.id(), loginOf(inviteeId));
		invitationService.accept(inviteeId, invitation.id());
	}

	// 기록글로 인정되는 개발일지를 쓴다. team이 null이면 팀 연결 없음. 글 id를 돌려준다
	protected String newPost(long authorId, CreatedTeam team) {
		String teamId = team == null ? null : Long.toString(team.id());
		return postService.create(authorId, new CreatePostInput(PostType.DEVLOG, "글-" + sequence.incrementAndGet(),
				new PostSectionsInput(null, null, null, "한 일", "배운 점"), List.of(), List.of(), teamId)).id();
	}

	// ----- 목록 전체 읽기 (커서를 따라 끝까지) -----

	protected List<TeamMemberResponse> allMembers(long viewerId, long teamId) {
		return allPages(cursor -> teamService.members(viewerId, teamId, cursor));
	}

	protected List<ReceivedInvitationResponse> allReceived(long memberId) {
		return allPages(cursor -> invitationService.received(memberId, cursor));
	}

	protected List<SentInvitationResponse> allSent(long leaderId, long teamId) {
		return allPages(cursor -> invitationService.sent(leaderId, teamId, cursor));
	}

	private static <T> List<T> allPages(Function<String, CursorPage<T>> fetch) {
		List<T> all = new ArrayList<>();
		String cursor = null;
		do {
			CursorPage<T> page = fetch.apply(cursor);
			all.addAll(page.items());
			cursor = page.nextCursor();
		} while (cursor != null);
		return all;
	}

	// ----- 오류 단언 -----

	protected static void assertApiError(ThrowingCallable call, ErrorCode expected) {
		assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(expected));
	}

	protected void assertGraphQlError(GraphQlResponse response, ErrorCode expected) {
		assertThat(response.status()).isEqualTo(200);
		assertThat(response.errorCode()).as(response.rawBody()).isEqualTo(expected.name());
		assertThat(response.errorMessage()).isEqualTo(expected.message());
	}

	protected void assertGraphQlOk(GraphQlResponse response) {
		assertThat(response.status()).isEqualTo(200);
		assertThat(response.hasErrors()).as(response.rawBody()).isFalse();
	}

	// ----- GraphQL -----

	protected GraphQlResponse gql(long memberId, String query, Map<String, Object> variables) {
		return graphQl.post(bearerFor(memberId), query, variables);
	}

	protected GraphQlResponse gql(long memberId, String query) {
		return graphQl.post(bearerFor(memberId), query);
	}

	// ----- DB 읽기 -----

	protected Map<String, Object> teamRow(long teamId) {
		return jdbc.queryForMap("select * from team where id = ?", teamId);
	}

	protected long activeMemberCount(long teamId) {
		Long count = jdbc.queryForObject("select count(*) from team_member where team_id = ? and left_at is null",
				Long.class, teamId);
		return count == null ? 0 : count;
	}

	protected boolean isActiveMember(long teamId, long memberId) {
		Long count = jdbc.queryForObject(
				"select count(*) from team_member where team_id = ? and member_id = ? and left_at is null", Long.class,
				teamId, memberId);
		return count != null && count > 0;
	}

	// 같은 팀·회원의 소속 이력 전부(참가 순서). 나가고 다시 들어오면 행이 늘어난다
	protected List<Map<String, Object>> membershipRows(long teamId, long memberId) {
		return jdbc.queryForList("select * from team_member where team_id = ? and member_id = ? order by joined_at, id",
				teamId, memberId);
	}

	protected Map<String, Object> currentMembership(long teamId, long memberId) {
		List<Map<String, Object>> rows = jdbc.queryForList(
				"select * from team_member where team_id = ? and member_id = ? and left_at is null", teamId, memberId);
		assertThat(rows).as("현재 소속 행").hasSize(1);
		return rows.get(0);
	}

	protected List<Map<String, Object>> invitationRows(long teamId, long inviteeId) {
		return jdbc.queryForList("select * from team_invitation where team_id = ? and invitee_id = ? order by id", teamId,
				inviteeId);
	}

	// queryForMap이 돌려주는 DATETIME 값(Timestamp)을 LocalDateTime으로
	protected static LocalDateTime time(Object value) {
		return value == null ? null : ((java.sql.Timestamp) value).toLocalDateTime();
	}

	protected static String banKey(long teamId, long memberId) {
		return "ban:" + teamId + ":" + memberId;
	}

}
