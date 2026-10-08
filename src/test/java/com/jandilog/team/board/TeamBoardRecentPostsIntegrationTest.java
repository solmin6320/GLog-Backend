package com.jandilog.team.board;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.jandilog.post.domain.PostType;
import com.jandilog.post.dto.PostResponse;
import com.jandilog.post.dto.PostSectionsInput;
import com.jandilog.post.dto.UpdatePostInput;

// 팀 현황판 "이 팀의 기록글" 목록(TM-05 ⑦)과 기록글 수·인증일에 글 상태가 주는 영향.
// 확정된 결정: 이 팀에 연결된 글 전체, 필수 항목이 빈 글도 포함. 최신 5건은 화면설계서의 (제안) 값이다.
// 이번 주는 2026-12-07(월)~12-13(일), 시각은 12-09(수)
class TeamBoardRecentPostsIntegrationTest extends TeamBoardTestBase {

	private static final LocalDate MON = LocalDate.of(2026, 12, 7);
	private static final LocalDate TUE = MON.plusDays(1);
	private static final LocalDate WED = MON.plusDays(2);

	private long leader;
	private long writer;
	private long other;
	private TeamRef team;
	private TeamRef otherTeam;

	@BeforeEach
	void setUpScenario() {
		at("2026-12-09T12:07:13");
		leader = member();
		writer = member();
		other = member();
		team = createTeam(leader, true);
		join(writer, team);
		otherTeam = createTeam(other, true);
		join(writer, otherTeam);
		settleFirstWeek(leader, writer, other);
	}

	@Test
	void 연결된_글이_없으면_빈_목록이고_다른_팀_글과_팀_없는_글은_나오지_않는다() {
		assertThat(board(leader, team.id()).path("recentPosts")).isEmpty();

		postAt("2026-12-09T12:10:01", writer, PostType.DEVLOG, "다른 팀 글", true, otherTeam.id());
		postAt("2026-12-09T12:10:02", writer, PostType.DEVLOG, "팀 없는 글", true, null);
		postAt("2026-12-09T12:10:03", writer, PostType.TROUBLESHOOTING, "이 팀 글", true, team.id());

		JsonNode board = board(leader, team.id());

		assertThat(titlesOf(board)).containsExactly("이 팀 글");
		assertThat(board.path("recentPosts").get(0).path("author").path("id").asLong()).isEqualTo(writer);
		assertThat(titlesOf(board(other, otherTeam.id()))).containsExactly("다른 팀 글");
	}

	@Test
	void 최신_5건까지만_내려오고_가장_오래된_글부터_밀려난다() {
		for (int i = 1; i <= 6; i++) {
			postAt("2026-12-09T12:1" + i + ":00", i % 2 == 0 ? leader : writer, PostType.DEVLOG, "글" + i, true, team.id());
		}

		List<String> titles = titlesOf(board(leader, team.id()));

		assertThat(titles).containsExactly("글6", "글5", "글4", "글3", "글2");
	}

	@Test
	void 지워진_글은_목록에서_빠지고_빈_자리는_더_오래된_글이_채운다() {
		PostResponse[] posts = new PostResponse[6];
		for (int i = 1; i <= 6; i++) {
			posts[i - 1] = postAt("2026-12-09T12:1" + i + ":00", writer, PostType.DEVLOG, "글" + i, true, team.id());
		}

		postService.delete(writer, posts[5].id());

		assertThat(titlesOf(board(leader, team.id()))).containsExactly("글5", "글4", "글3", "글2", "글1");

		postService.delete(writer, posts[0].id());
		postService.delete(writer, posts[1].id());

		assertThat(titlesOf(board(leader, team.id()))).containsExactly("글5", "글4", "글3");
	}

	@Test
	void 지운_기록글은_기록글_수와_인증일에서도_빠진다() {
		PostResponse linked = postAt("2026-12-09T12:10:01", writer, PostType.DEVLOG, "연결된 기록글", true, team.id());
		PostResponse unlinked = postAt("2026-12-09T12:10:02", writer, PostType.DEVLOG, "연결 없는 기록글", true, null);
		JsonNode before = rowOf(board(leader, team.id()), writer);
		assertThat(before.path("recordCount").asInt()).isEqualTo(2);
		assertThat(before.path("days").get(2).path("hasRecord").asBoolean()).isTrue();

		postService.delete(writer, linked.id());
		JsonNode afterOne = rowOf(board(leader, team.id()), writer);
		// 팀 연결과 상관없이 개인 기록글이라 연결 없는 글이 남아 있으면 그날 인증은 유지된다
		assertThat(afterOne.path("recordCount").asInt()).isEqualTo(1);
		assertThat(afterOne.path("days").get(2).path("hasRecord").asBoolean()).isTrue();
		assertThat(afterOne.path("days").get(2).path("verified").asBoolean()).isTrue();

		postService.delete(writer, unlinked.id());
		JsonNode afterAll = rowOf(board(leader, team.id()), writer);
		assertThat(afterAll.path("recordCount").asInt()).isZero();
		assertThat(afterAll.path("days").get(2).path("hasRecord").asBoolean()).isFalse();
		assertThat(afterAll.path("days").get(2).path("verified").isNull()).isTrue();
	}

	@Test
	void 필수_항목이_빈_글은_목록에는_나오지만_기록글_수와_인증일과_상태에는_세지_않는다() {
		seedGrass(writer, WED, Set.of(MON, TUE, WED));
		postAt("2026-12-09T12:10:01", writer, PostType.DEVLOG, "미완성 글", false, team.id());

		JsonNode board = board(leader, team.id());
		JsonNode row = rowOf(board, writer);

		assertThat(titlesOf(board)).containsExactly("미완성 글");
		assertThat(board.path("recentPosts").get(0).path("isRecord").asBoolean()).isFalse();
		assertThat(row.path("recordCount").asInt()).isZero();
		assertThat(row.path("days").get(2).path("hasRecord").asBoolean()).isFalse();
		// 잔디 3일이어도 기록글이 0개라 통과가 아니다
		assertThat(row.path("verifiedDays").asInt()).isEqualTo(3);
		assertThat(row.path("status").asText()).isEqualTo("NOT_YET");

		postAt("2026-12-09T12:10:02", writer, PostType.TROUBLESHOOTING, "완성 글", true, team.id());
		JsonNode withRecord = rowOf(board(leader, team.id()), writer);
		assertThat(withRecord.path("recordCount").asInt()).isEqualTo(1);
		assertThat(withRecord.path("status").asText()).isEqualTo("PASS");
	}

	@Test
	void 수정해서_필수_항목을_채우면_기록글이_되고_글은_같은_자리에_남는다() {
		PostResponse draft = postAt("2026-12-09T12:10:01", writer, PostType.DEVLOG, "나중에 채움", false, team.id());
		assertThat(rowOf(board(leader, team.id()), writer).path("recordCount").asInt()).isZero();

		postService.update(writer, draft.id(), new UpdatePostInput("나중에 채움", new PostSectionsInput(null, null, null, "한 일", "배운 점"),
				List.of(), List.of(), Long.toString(team.id())));

		JsonNode board = board(leader, team.id());
		assertThat(titlesOf(board)).containsExactly("나중에 채움");
		assertThat(board.path("recentPosts").get(0).path("isRecord").asBoolean()).isTrue();
		assertThat(rowOf(board, writer).path("recordCount").asInt()).isEqualTo(1);
		// 인증 날짜는 최초 저장일(수요일)로 고정이다
		assertThat(rowOf(board, writer).path("days").get(2).path("hasRecord").asBoolean()).isTrue();
	}

	@Test
	void 글의_팀_연결을_바꾸면_이전_팀_현황판에서는_빠지고_새_팀_현황판에_나온다() {
		PostResponse post = postAt("2026-12-09T12:10:01", writer, PostType.DEVLOG, "옮겨 갈 글", true, team.id());
		assertThat(titlesOf(board(leader, team.id()))).containsExactly("옮겨 갈 글");

		postService.update(writer, post.id(), new UpdatePostInput("옮겨 갈 글", new PostSectionsInput(null, null, null, "한 일", "배운 점"),
				List.of(), List.of(), Long.toString(otherTeam.id())));

		assertThat(board(leader, team.id()).path("recentPosts")).isEmpty();
		assertThat(titlesOf(board(other, otherTeam.id()))).containsExactly("옮겨 갈 글");
		// 연결이 바뀌어도 개인 기록글 수는 그대로다
		assertThat(rowOf(board(leader, team.id()), writer).path("recordCount").asInt()).isEqualTo(1);
	}

	@Test
	void 지난_주에_쓴_글도_최신_글로_나오지만_이번_주_기록글_수에는_세지_않는다() {
		postAt("2026-12-03T10:11:12", writer, PostType.DEVLOG, "지난 주 글", true, team.id());
		at("2026-12-09T12:20:00");

		JsonNode board = board(leader, team.id());

		assertThat(titlesOf(board)).containsExactly("지난 주 글");
		assertThat(board.path("recentPosts").get(0).path("createdAt").asText()).startsWith("2026-12-03T10:11");
		assertThat(rowOf(board, writer).path("recordCount").asInt()).isZero();
	}

	@Test
	void 자정_직후에_쓴_글의_작성_시각은_UTC가_아니라_KST_날짜로_보인다() {
		postAt("2026-12-14T00:00:00", writer, PostType.DEVLOG, "월요일 0시 글", true, team.id());

		JsonNode board = board(leader, team.id());

		assertThat(board.path("recentPosts").get(0).path("createdAt").asText()).startsWith("2026-12-14T00:00:00");
	}

}
