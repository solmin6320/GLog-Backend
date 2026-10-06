package com.jandilog.team.board;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.jandilog.judgment.service.GrassClient;
import com.jandilog.post.domain.Post;
import com.jandilog.post.domain.PostType;
import com.jandilog.post.dto.CreatePostInput;
import com.jandilog.post.dto.PostSectionsInput;
import com.jandilog.post.service.PostService;
import com.jandilog.team.dto.CreateTeamInput;
import com.jandilog.team.dto.CreateTeamPayload;
import com.jandilog.team.service.TeamJoinService;
import com.jandilog.team.service.TeamService;
import com.jandilog.testsupport.auth.AuthIntegrationTest;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;

// 팀 현황판(TM-05)과 내 팀 목록 이번 주 요약(TM-01 ⑧)의 정상 경로. 시각은 2026-10-08(목) 12:00 KST로 고정한다.
// 이번 주는 10/5(월)~10/11(일)이고 잔디 캐시는 새벽 6시에 갱신된 것으로 심어 둔다 (GitHub는 부르지 않는다)
class TeamBoardIntegrationTest extends AuthIntegrationTest {

	private static final Instant NOW = Instant.parse("2026-10-08T03:00:00Z");
	private static final LocalDate TODAY = LocalDate.of(2026, 10, 8);
	private static final String FETCHED_AT = "2026-10-08T06:00:00";

	private static final String BOARD_QUERY = """
			query($id: ID!) {
			  teamBoard(teamId: $id) {
			    weekStart weekEnd grassFetchedAt grassNotLoaded
			    members {
			      memberId nickname githubLogin isMe verifiedDays recordCount warningCount status
			      days { date hasGrass hasRecord verified }
			    }
			    recentPosts { id type title createdAt author { id nickname } }
			  }
			}
			""";

	// 잔디 호출이 없음을 확인하려고 실제 클라이언트를 가짜로 바꾼다
	@MockitoBean
	private GrassClient grassClient;
	@Autowired
	private TeamService teamService;
	@Autowired
	private TeamJoinService joinService;
	@Autowired
	private PostService postService;
	@Autowired
	private MongoTemplate mongo;

	private long leader;
	private long passer;
	private long notYet;
	private long firstWeek;
	private long otherLeader;
	private long teamId;
	private long otherTeamId;
	private final List<Long> memberIds = new ArrayList<>();

	@BeforeEach
	void setUpScenario() throws JsonProcessingException {
		clock.fixAt(NOW);
		leader = member();
		passer = member();
		notYet = member();
		firstWeek = member();
		otherLeader = member();

		CreateTeamPayload team = teamService.create(leader, new CreateTeamInput("현황판-" + members.tag(), null, true));
		CreateTeamPayload other = teamService.create(otherLeader,
				new CreateTeamInput("현황U-" + members.tag(), null, true));
		teamId = team.team().id();
		otherTeamId = other.team().id();
		joinService.joinByCode(passer, team.inviteCode());
		joinService.joinByCode(notYet, team.inviteCode());
		joinService.joinByCode(firstWeek, team.inviteCode());
		joinService.joinByCode(leader, other.inviteCode());
		joinService.joinByCode(passer, other.inviteCode());
		// 첫 참가 주 제외는 이번 주에 처음 참가한 한 명만. 나머지는 지난달에 처음 참가한 것으로 맞춘다
		for (long id : List.of(leader, passer, notYet, otherLeader)) {
			jdbc.update("update member set first_team_joined_at = ? where id = ?", LocalDateTime.of(2026, 9, 1, 10, 0),
					id);
		}

		// 잔디 캐시: leader 월·화·수, passer 월. notYet·firstWeek·otherLeader는 캐시 없음(E-32)
		seedGrass(leader, Map.of(LocalDate.of(2026, 10, 5), 3, LocalDate.of(2026, 10, 6), 1,
				LocalDate.of(2026, 10, 7), 2));
		seedGrass(passer, Map.of(LocalDate.of(2026, 10, 5), 1));

		// 글: leader·passer의 기록글(이 팀), passer의 미완성 글(이 팀), leader의 기록글(다른 팀). 같은 시각이면 _id가 겹쳐 1분씩 띄운다
		post(leader, PostType.DEVLOG, "leader 기록글", true, teamId);
		clock.fixAt(NOW.plusSeconds(60));
		post(passer, PostType.TROUBLESHOOTING, "passer 기록글", true, teamId);
		clock.fixAt(NOW.plusSeconds(120));
		post(passer, PostType.DEVLOG, "passer 미완성", false, teamId);
		clock.fixAt(NOW.plusSeconds(180));
		post(leader, PostType.DEVLOG, "다른 팀 글", true, otherTeamId);
		clock.fixAt(NOW);

		// passer의 미달 경고 2개: 9/14 주는 이 팀 카테고리, 9/21 주는 다른 팀 카테고리
		warning(passer, LocalDate.of(2026, 9, 14), teamId);
		warning(passer, LocalDate.of(2026, 9, 21), otherTeamId);
	}

	@AfterEach
	void cleanUpScenario() {
		if (!memberIds.isEmpty()) {
			String ids = memberIds.stream().map(String::valueOf).collect(Collectors.joining(","));
			mongo.remove(Query.query(Criteria.where("authorId").in(memberIds)), Post.class);
			jdbc.update("delete from post_index where author_id in (" + ids + ")");
			jdbc.update("delete from warning_team where warning_id in (select id from warning where member_id in (" + ids
					+ "))");
			jdbc.update("delete from warning where member_id in (" + ids + ")");
			jdbc.update("delete from weekly_judgment where member_id in (" + ids + ")");
			for (long id : memberIds) {
				Set<String> keys = redis.keys("grass:" + id + ":*");
				if (keys != null) {
					redis.delete(keys);
				}
			}
		}
		for (long id : List.of(teamId, otherTeamId)) {
			jdbc.update("delete from team_invitation where team_id = ?", id);
			jdbc.update("delete from team_member where team_id = ?", id);
			jdbc.update("delete from team where id = ?", id);
		}
	}

	@Test
	void 팀원은_이번_주_현황판에서_팀원별_인증일과_기록글_경고_상태와_이_팀의_기록글을_본다() {
		GraphQlResponse response = graphQl.post(bearerFor(leader), BOARD_QUERY, Map.of("id", Long.toString(teamId)));

		assertThat(response.hasErrors()).as(response.rawBody()).isFalse();
		JsonNode board = response.data().path("teamBoard");
		assertThat(board.path("weekStart").asText()).isEqualTo("2026-10-05");
		assertThat(board.path("weekEnd").asText()).isEqualTo("2026-10-11");
		assertThat(board.path("grassFetchedAt").asText()).isEqualTo(FETCHED_AT);
		assertThat(board.path("grassNotLoaded").asBoolean()).isTrue();

		// 팀장이 맨 앞, 나머지는 참가한 순서
		JsonNode rows = board.path("members");
		assertThat(rows).hasSize(4);
		assertThat(ids(rows)).containsExactly(leader, passer, notYet, firstWeek);

		// 통과: 잔디 월·화·수 + 목요일 기록글 2개 → 인증일 4일, 기록글 2개(다른 팀 글도 개인 기록글로 센다)
		JsonNode me = rows.get(0);
		assertThat(me.path("isMe").asBoolean()).isTrue();
		assertThat(me.path("verifiedDays").asInt()).isEqualTo(4);
		assertThat(me.path("recordCount").asInt()).isEqualTo(2);
		assertThat(me.path("warningCount").asInt()).isZero();
		assertThat(me.path("status").asText()).isEqualTo("PASS");
		assertThat(me.path("days")).hasSize(7);
		assertThat(me.path("days").get(0).path("date").asText()).isEqualTo("2026-10-05");
		assertThat(me.path("days").get(0).path("hasGrass").asBoolean()).isTrue();
		assertThat(me.path("days").get(3).path("hasGrass").asBoolean()).isFalse();
		assertThat(me.path("days").get(3).path("hasRecord").asBoolean()).isTrue();
		assertThat(me.path("days").get(3).path("verified").asBoolean()).isTrue();
		assertThat(me.path("days").get(6).path("verified").isNull()).isTrue();

		// 아직: 월요일 잔디 + 목요일 기록글(미완성 글은 세지 않는다) → 인증일 2일. 경고는 이 팀 카테고리만 1개
		JsonNode second = rows.get(1);
		assertThat(second.path("isMe").asBoolean()).isFalse();
		assertThat(second.path("verifiedDays").asInt()).isEqualTo(2);
		assertThat(second.path("recordCount").asInt()).isEqualTo(1);
		assertThat(second.path("warningCount").asInt()).isEqualTo(1);
		assertThat(second.path("status").asText()).isEqualTo("NOT_YET");

		// 잔디 캐시가 없으면 인증일만 null이고 나머지는 정상
		JsonNode noGrass = rows.get(2);
		assertThat(noGrass.path("verifiedDays").isNull()).isTrue();
		assertThat(noGrass.path("recordCount").asInt()).isZero();
		assertThat(noGrass.path("warningCount").asInt()).isZero();
		assertThat(noGrass.path("status").asText()).isEqualTo("NOT_YET");

		// 이번 주에 처음 참가한 팀원은 판정 제외
		assertThat(rows.get(3).path("status").asText()).isEqualTo("EXCLUDED");

		// 이 팀에 연결된 기록글만 최신순 (미완성 글·다른 팀 글 제외)
		JsonNode posts = board.path("recentPosts");
		assertThat(posts).hasSize(2);
		assertThat(posts.get(0).path("title").asText()).isEqualTo("passer 기록글");
		assertThat(posts.get(0).path("type").asText()).isEqualTo("TROUBLESHOOTING");
		assertThat(posts.get(0).path("author").path("id").asLong()).isEqualTo(passer);
		assertThat(posts.get(0).path("createdAt").asText()).startsWith("2026-10-08T12:01");
		assertThat(posts.get(1).path("title").asText()).isEqualTo("leader 기록글");

		verifyNoInteractions(grassClient);
	}

	@Test
	void 내_팀_목록에서_팀마다_이번_주_통과_미달_아직_인원을_함께_받는다() {
		GraphQlResponse response = graphQl.post(bearerFor(leader),
				"{ myTeams { id weekSummary { weekStart passCount failCount notYetCount } } }");

		assertThat(response.hasErrors()).as(response.rawBody()).isFalse();
		JsonNode teams = response.data().path("myTeams");
		assertThat(teams).hasSize(2);

		// 현황판 팀: leader 통과, passer·notYet 아직, firstWeek 제외(어느 쪽에도 세지 않음)
		JsonNode board = teams.get(0);
		assertThat(board.path("id").asLong()).isEqualTo(teamId);
		assertThat(board.path("weekSummary").path("weekStart").asText()).isEqualTo("2026-10-05");
		assertThat(board.path("weekSummary").path("passCount").asInt()).isEqualTo(1);
		assertThat(board.path("weekSummary").path("failCount").asInt()).isZero();
		assertThat(board.path("weekSummary").path("notYetCount").asInt()).isEqualTo(2);

		// 다른 팀: leader 통과, passer·otherLeader 아직
		JsonNode other = teams.get(1);
		assertThat(other.path("id").asLong()).isEqualTo(otherTeamId);
		assertThat(other.path("weekSummary").path("passCount").asInt()).isEqualTo(1);
		assertThat(other.path("weekSummary").path("failCount").asInt()).isZero();
		assertThat(other.path("weekSummary").path("notYetCount").asInt()).isEqualTo(2);

		verifyNoInteractions(grassClient);
	}

	private long member() {
		long id = members.active();
		memberIds.add(id);
		return id;
	}

	private List<Long> ids(JsonNode rows) {
		List<Long> ids = new ArrayList<>();
		rows.forEach(row -> ids.add(row.path("memberId").asLong()));
		return ids;
	}

	// 새벽 갱신이 채운 것처럼 지난 주 월요일부터 오늘까지를 덮는 오늘자 캐시를 Redis에 심는다
	private void seedGrass(long memberId, Map<LocalDate, Integer> counts) throws JsonProcessingException {
		LocalDate from = LocalDate.of(2026, 9, 28);
		List<Map<String, Object>> days = new ArrayList<>();
		for (LocalDate day = from; !day.isAfter(TODAY); day = day.plusDays(1)) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("date", day.toString());
			entry.put("count", counts.getOrDefault(day, 0));
			days.add(entry);
		}
		Map<String, Object> cache = new LinkedHashMap<>();
		cache.put("memberId", memberId);
		cache.put("from", from.toString());
		cache.put("to", TODAY.toString());
		cache.put("fetchedAt", FETCHED_AT);
		cache.put("days", days);
		redis.opsForValue().set("grass:" + memberId + ":" + TODAY, objectMapper.writeValueAsString(cache),
				Duration.ofHours(1));
	}

	private void post(long authorId, PostType type, String title, boolean complete, long team) {
		PostSectionsInput sections = type == PostType.TROUBLESHOOTING
				? new PostSectionsInput("문제", "원인", "해결", null, null)
				: new PostSectionsInput(null, null, null, "한 일", complete ? "배운 점" : null);
		postService.create(authorId, new CreatePostInput(type, title, sections, List.of(), List.of(),
				Long.toString(team)));
	}

	// 판정 이력(미달)과 경고, 경고의 팀 카테고리를 직접 심는다
	private void warning(long memberId, LocalDate weekStart, long categoryTeamId) {
		jdbc.update("insert into weekly_judgment (member_id, week_start, status, retry_count, corrected, judged_at)"
				+ " values (?, ?, 'FAIL', 0, false, ?)", memberId, weekStart, weekStart.plusDays(7).atTime(7, 0));
		jdbc.update("insert into warning (member_id, week_start, created_at) values (?, ?, ?)", memberId, weekStart,
				weekStart.plusDays(7).atTime(7, 0));
		Long warningId = jdbc.queryForObject("select id from warning where member_id = ? and week_start = ?",
				Long.class, memberId, weekStart);
		jdbc.update("insert into warning_team (warning_id, team_id) values (?, ?)", warningId, categoryTeamId);
	}

}
