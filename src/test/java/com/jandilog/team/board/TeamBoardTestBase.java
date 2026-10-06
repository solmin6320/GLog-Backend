package com.jandilog.team.board;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.judgment.service.GrassCacheService;
import com.jandilog.judgment.service.GrassClient;
import com.jandilog.post.domain.Post;
import com.jandilog.post.domain.PostType;
import com.jandilog.post.dto.CreatePostInput;
import com.jandilog.post.dto.PostResponse;
import com.jandilog.post.dto.PostSectionsInput;
import com.jandilog.post.service.PostService;
import com.jandilog.team.dto.CreateTeamInput;
import com.jandilog.team.dto.CreateTeamPayload;
import com.jandilog.team.service.TeamInvitationService;
import com.jandilog.team.service.TeamJoinService;
import com.jandilog.team.service.TeamLeaveService;
import com.jandilog.team.service.TeamService;
import com.jandilog.testsupport.auth.AuthIntegrationTest;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;

// 팀 현황판 경계·예외 통합 테스트 공통 바탕. 회원·팀·글·잔디 캐시·판정 행을 만들고 끝에 자기 것만 지운다.
// 글 _id는 저장 시각(초)에서 만들어져 같은 초에 쓴 글끼리 겹치므로, 글은 postAt으로 초마다 하나씩만 쓴다
abstract class TeamBoardTestBase extends AuthIntegrationTest {

	protected static final ZoneId KST = ZoneId.of("Asia/Seoul");

	protected static final String BOARD_QUERY = """
			query($id: ID!) {
			  teamBoard(teamId: $id) {
			    weekStart weekEnd grassFetchedAt grassNotLoaded
			    members {
			      memberId nickname githubLogin isMe verifiedDays recordCount warningCount status
			      days { date hasGrass hasRecord verified }
			    }
			    recentPosts { id type title createdAt isRecord author { id nickname } }
			  }
			}
			""";

	protected static final String MY_TEAMS_QUERY = """
			{ myTeams { id weekSummary { weekStart passCount failCount notYetCount } } }
			""";

	protected record TeamRef(long id, String code) {
	}

	// 잔디 호출이 없음을 모든 테스트가 끝에 확인한다
	@MockitoBean
	protected GrassClient grassClient;
	@Autowired
	protected TeamService teamService;
	@Autowired
	protected TeamJoinService joinService;
	@Autowired
	protected TeamLeaveService leaveService;
	@Autowired
	protected TeamInvitationService invitationService;
	@Autowired
	protected PostService postService;
	@Autowired
	protected MongoTemplate mongo;

	private final List<Long> memberIds = new ArrayList<>();
	private final List<Long> teamIds = new ArrayList<>();
	private final Set<Instant> usedPostInstants = new HashSet<>();
	private int teamSequence;

	@AfterEach
	void cleanUpBoardData() {
		try {
			verifyNoInteractions(grassClient);
		}
		finally {
			cleanUp();
		}
	}

	private void cleanUp() {
		if (!memberIds.isEmpty()) {
			String ids = memberIds.stream().map(String::valueOf).collect(Collectors.joining(","));
			mongo.remove(Query.query(Criteria.where("authorId").in(memberIds)), Post.class);
			jdbc.update("delete from post_index where author_id in (" + ids + ")");
			jdbc.update("delete from warning_team where warning_id in (select id from warning where member_id in (" + ids
					+ "))");
			jdbc.update("delete from warning where member_id in (" + ids + ")");
			jdbc.update("delete from judgment_day where judgment_id in (select id from weekly_judgment where member_id in ("
					+ ids + "))");
			jdbc.update("delete from judgment_team where judgment_id in (select id from weekly_judgment where member_id in ("
					+ ids + "))");
			jdbc.update("delete from weekly_judgment where member_id in (" + ids + ")");
			jdbc.update("delete from personal_exemption where member_id in (" + ids + ") or requested_by in (" + ids + ")");
			jdbc.update("delete from penalty_fulfillment where member_id in (" + ids + ") or admin_id in (" + ids + ")");
			jdbc.update("delete from team_invitation where invitee_id in (" + ids + ")");
			for (long id : memberIds) {
				deleteKeys("grass:" + id + ":*");
			}
		}
		for (long id : teamIds) {
			jdbc.update("delete from team_invitation where team_id = ?", id);
			jdbc.update("delete from warning_team where team_id = ?", id);
			jdbc.update("delete from judgment_team where team_id = ?", id);
			jdbc.update("delete from team_member where team_id = ?", id);
			jdbc.update("delete from team where id = ?", id);
			deleteKeys("ban:" + id + ":*");
		}
	}

	private void deleteKeys(String pattern) {
		Set<String> keys = redis.keys(pattern);
		if (keys != null && !keys.isEmpty()) {
			redis.delete(keys);
		}
	}

	// ----- 시각 -----

	// "2026-12-13T23:59:59" 같은 KST 시각을 Instant로
	protected static Instant kst(String localDateTime) {
		return LocalDateTime.parse(localDateTime).atZone(KST).toInstant();
	}

	protected void at(String kstDateTime) {
		clock.fixAt(kst(kstDateTime));
	}

	// ----- 회원·팀 -----

	protected long member() {
		long id = members.active();
		memberIds.add(id);
		return id;
	}

	protected long admin() {
		long id = members.admin();
		memberIds.add(id);
		return id;
	}

	protected long pending() {
		long id = members.pending();
		memberIds.add(id);
		return id;
	}

	protected long rejected() {
		long id = members.rejected();
		memberIds.add(id);
		return id;
	}

	protected String loginOf(long memberId) {
		return members.find(memberId).orElseThrow().githubLogin();
	}

	protected TeamRef createTeam(long leader, boolean isPublic) {
		CreateTeamPayload payload = teamService.create(leader,
				new CreateTeamInput("판-" + members.tag() + "-" + (++teamSequence), null, isPublic));
		trackTeam(payload.team().id());
		return new TeamRef(payload.team().id(), payload.inviteCode());
	}

	// GraphQL로 만든 팀도 끝에 지우도록 등록한다
	protected void trackTeam(long teamId) {
		teamIds.add(teamId);
	}

	protected void join(long memberId, TeamRef team) {
		joinService.joinByCode(memberId, team.code());
	}

	// 첫 참가 주 제외를 피하려고 처음 참가 시각을 지난 달로 돌린다
	protected void settleFirstWeek(long... ids) {
		for (long id : ids) {
			jdbc.update("update member set first_team_joined_at = ? where id = ?", LocalDateTime.of(2026, 11, 1, 10, 0),
					id);
		}
	}

	// ----- 글 -----

	protected PostResponse post(long authorId, PostType type, String title, boolean complete, Long team) {
		PostSectionsInput sections = type == PostType.TROUBLESHOOTING
				? new PostSectionsInput("문제", "원인", "해결", null, null)
				: new PostSectionsInput(null, null, null, "한 일", complete ? "배운 점" : null);
		return postService.create(authorId, new CreatePostInput(type, title, sections, List.of(), List.of(),
				team == null ? null : Long.toString(team)));
	}

	// 시계를 그 시각에 맞추고 글을 쓴다. 글 _id가 초 단위라 한 테스트 안에서 같은 시각을 두 번 쓰면 막는다
	protected PostResponse postAt(String kstDateTime, long authorId, PostType type, String title, boolean complete,
			Long team) {
		Instant instant = kst(kstDateTime);
		if (!usedPostInstants.add(instant)) {
			throw new IllegalStateException("같은 시각에 글을 두 번 쓰면 _id가 겹쳐요: " + kstDateTime);
		}
		clock.fixAt(instant);
		return post(authorId, type, title, complete, team);
	}

	// ----- 잔디 캐시 -----

	// 새벽 갱신이 채운 것처럼 지난 주 월요일부터 today까지를 덮는 today자 캐시를 Redis에 심는다. grassDays에 든 날만 잔디 1칸
	protected void seedGrass(long memberId, LocalDate today, Set<LocalDate> grassDays) {
		seedGrass(memberId, today, GrassCacheService.defaultFrom(today), today, today.atTime(6, 0), grassDays);
	}

	// 캐시 키 날짜(조회한 날)와 구간·갱신 시각을 직접 정한다
	protected void seedGrass(long memberId, LocalDate keyDate, LocalDate from, LocalDate to, LocalDateTime fetchedAt,
			Set<LocalDate> grassDays) {
		List<Map<String, Object>> days = new ArrayList<>();
		for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("date", day.toString());
			entry.put("count", grassDays.contains(day) ? 2 : 0);
			days.add(entry);
		}
		Map<String, Object> cache = new LinkedHashMap<>();
		cache.put("memberId", memberId);
		cache.put("from", from.toString());
		cache.put("to", to.toString());
		cache.put("fetchedAt", fetchedAt.toString());
		cache.put("days", days);
		try {
			redis.opsForValue().set(GrassCacheService.key(memberId, keyDate), objectMapper.writeValueAsString(cache),
					Duration.ofHours(1));
		}
		catch (JsonProcessingException e) {
			throw new IllegalStateException(e);
		}
	}

	// ----- 판정·경고 행 -----

	// 경고 없이 판정 상태만 남긴 주 (통과 이력·보류 등). 근거 7일은 만들지 않는다
	protected void judgmentRow(long memberId, LocalDate weekStart, String status) {
		jdbc.update("insert into weekly_judgment (member_id, week_start, status, hold_reason, retry_count, corrected,"
				+ " judged_at) values (?, ?, ?, ?, 0, false, ?)", memberId, weekStart, status,
				"HOLD".equals(status) ? "API_ERROR" : null,
				"HOLD".equals(status) ? null : weekStart.plusDays(7).atTime(7, 0));
	}

	// 한 주 미달 판정 이력과 경고 1개, 경고에 붙는 팀 카테고리들을 직접 심는다
	protected void warning(long memberId, LocalDate weekStart, long... categoryTeamIds) {
		jdbc.update("insert into weekly_judgment (member_id, week_start, status, retry_count, corrected, judged_at)"
				+ " values (?, ?, 'FAIL', 0, false, ?)", memberId, weekStart, weekStart.plusDays(7).atTime(7, 0));
		jdbc.update("insert into warning (member_id, week_start, created_at) values (?, ?, ?)", memberId, weekStart,
				weekStart.plusDays(7).atTime(7, 0));
		Long warningId = jdbc.queryForObject("select id from warning where member_id = ? and week_start = ?",
				Long.class, memberId, weekStart);
		for (long teamId : categoryTeamIds) {
			jdbc.update("insert into warning_team (warning_id, team_id) values (?, ?)", warningId, teamId);
		}
	}

	// 확정된 판정 한 건과 그 주 7일치 근거
	protected void judgment(long memberId, LocalDate weekStart, String status, Integer verifiedDays, Integer recordCount,
			Set<LocalDate> grassDays, Set<LocalDate> recordDays) {
		jdbc.update("insert into weekly_judgment (member_id, week_start, status, retry_count, verified_days, record_count,"
				+ " corrected, judged_at) values (?, ?, ?, 0, ?, ?, false, ?)", memberId, weekStart, status, verifiedDays,
				recordCount, weekStart.plusDays(7).atTime(7, 0));
		Long id = jdbc.queryForObject("select id from weekly_judgment where member_id = ? and week_start = ?", Long.class,
				memberId, weekStart);
		for (int i = 0; i < 7; i++) {
			LocalDate day = weekStart.plusDays(i);
			jdbc.update("insert into judgment_day (judgment_id, day, has_grass, has_record) values (?, ?, ?, ?)", id, day,
					grassDays.contains(day), recordDays.contains(day));
		}
	}

	protected void personalExemption(long memberId, LocalDate weekStart, String status, long requestedBy) {
		jdbc.update("insert into personal_exemption (member_id, requested_by, week_start, status, reason, created_at)"
				+ " values (?, ?, ?, ?, '테스트', ?)", memberId, requestedBy, weekStart, status,
				weekStart.minusDays(3).atTime(9, 0));
	}

	// ----- GraphQL 호출·읽기 -----

	protected GraphQlResponse boardResponse(String bearer, String teamId) {
		return graphQl.post(bearer, BOARD_QUERY, Map.of("id", teamId));
	}

	protected GraphQlResponse boardResponse(long viewer, long teamId) {
		return boardResponse(bearerFor(viewer), Long.toString(teamId));
	}

	protected JsonNode board(long viewer, long teamId) {
		GraphQlResponse response = boardResponse(viewer, teamId);
		assertThat(response.hasErrors()).as(response.rawBody()).isFalse();
		return response.data().path("teamBoard");
	}

	protected JsonNode myTeams(long viewer) {
		GraphQlResponse response = graphQl.post(bearerFor(viewer), MY_TEAMS_QUERY);
		assertThat(response.hasErrors()).as(response.rawBody()).isFalse();
		return response.data().path("myTeams");
	}

	// 내 팀 목록에서 그 팀의 이번 주 요약. 팀이 없으면 null
	protected JsonNode summaryOf(JsonNode myTeams, long teamId) {
		for (JsonNode team : myTeams) {
			if (team.path("id").asLong() == teamId) {
				return team.path("weekSummary");
			}
		}
		return null;
	}

	protected void assertDenied(GraphQlResponse response, ErrorCode expected) {
		assertThat(response.status()).isEqualTo(200);
		assertThat(response.errorCode()).as(response.rawBody()).isEqualTo(expected.name());
		assertThat(response.errorMessage()).isEqualTo(expected.message());
		assertThat(response.dataIsNull()).isTrue();
	}

	protected static List<Long> memberIdsOf(JsonNode board) {
		List<Long> ids = new ArrayList<>();
		board.path("members").forEach(row -> ids.add(row.path("memberId").asLong()));
		return ids;
	}

	protected static JsonNode rowOf(JsonNode board, long memberId) {
		for (JsonNode row : board.path("members")) {
			if (row.path("memberId").asLong() == memberId) {
				return row;
			}
		}
		return null;
	}

	protected static List<String> titlesOf(JsonNode board) {
		List<String> titles = new ArrayList<>();
		board.path("recentPosts").forEach(post -> titles.add(post.path("title").asText()));
		return titles;
	}

	protected static int[] counts(JsonNode summary) {
		return new int[] {summary.path("passCount").asInt(), summary.path("failCount").asInt(),
				summary.path("notYetCount").asInt()};
	}

}
