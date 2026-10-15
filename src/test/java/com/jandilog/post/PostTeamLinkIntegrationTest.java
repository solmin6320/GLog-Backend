package com.jandilog.post;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;
import com.jandilog.testsupport.board.BoardIntegrationTest;

// 글의 팀 연결: 지금 소속인 삭제되지 않은 팀만, Mongo teamId와 post_index.team_id는 같은 값 (기능명세서 2·3장, DB명세서 4-1·4-4, E-23)
class PostTeamLinkIntegrationTest extends BoardIntegrationTest {

	private static final String POST_TEAM = "query($id: ID!) { post(id: $id) { id teamId teamName } }";

	private long author;
	private long other;

	@BeforeEach
	void setUp() {
		author = activeMember();
		other = activeMember();
	}

	private static Map<String, Object> linked(Map<String, Object> input, long teamId) {
		input.put("teamId", Long.toString(teamId));
		return input;
	}

	private static Map<String, Object> updateInput(String title) {
		Map<String, Object> sections = new LinkedHashMap<>();
		sections.put("problem", "문제");
		sections.put("cause", "원인");
		sections.put("solution", "해결");
		Map<String, Object> input = new LinkedHashMap<>();
		input.put("title", title);
		input.put("sections", sections);
		return input;
	}

	private GraphQlResponse update(long memberId, String postId, Map<String, Object> input) {
		return gql(bearerFor(memberId), UPDATE_POST, vars("id", postId, "input", input));
	}

	private JsonNode teamOf(long viewerId, String postId) {
		return ok(gql(bearerFor(viewerId), POST_TEAM, vars("id", postId))).data().path("post");
	}

	private static Long mongoTeamId(Document doc) {
		Object value = doc.get("teamId");
		return value == null ? null : ((Number) value).longValue();
	}

	private void assertLinkedTo(String postId, Long teamId) {
		assertThat(mongoTeamId(mongoPost(postId))).isEqualTo(teamId);
		assertThat(indexRow(postId).orElseThrow().teamId()).isEqualTo(teamId);
	}

	// ----- 작성 -----

	@Test
	void 내가_속한_팀을_연결하면_Mongo와_post_index에_같은_팀이_저장된다() {
		long team = teams.create(author);

		JsonNode post = ok(createPost(author, linked(recordPost("팀 글"), team))).data().path("createPost");

		String id = post.path("id").asText();
		assertThat(post.path("teamId").asText()).isEqualTo(Long.toString(team));
		assertLinkedTo(id, team);
		assertThat(teamOf(other, id).path("teamId").asText()).isEqualTo(Long.toString(team));
	}

	@Test
	void 초대코드로_참가한_팀원도_팀을_연결할_수_있다() {
		long team = teams.create(other);
		teams.join(team, author);

		String id = newPost(author, linked(recordPost("팀원 글"), team));

		assertLinkedTo(id, team);
	}

	@Test
	void 속하지_않은_팀을_연결하면_POST_TEAM_INVALID이고_아무것도_저장되지_않는다() {
		long othersTeam = teams.create(other);

		assertError(createPost(author, linked(recordPost("남의 팀 글"), othersTeam)), "POST_TEAM_INVALID");

		assertThat(mongoPostCount(author)).isZero();
		assertThat(indexCount(author)).isZero();
	}

	@Test
	void 나간_팀을_연결하면_POST_TEAM_INVALID다() {
		long team = teams.create(other);
		teams.join(team, author);
		teams.leave(team, author);

		assertError(createPost(author, linked(recordPost("나간 팀 글"), team)), "POST_TEAM_INVALID");

		assertThat(mongoPostCount(author)).isZero();
	}

	@Test
	void 삭제된_팀을_연결하면_POST_TEAM_INVALID다() {
		long team = teams.create(author);
		teams.delete(team, author);

		assertError(createPost(author, linked(recordPost("삭제된 팀 글"), team)), "POST_TEAM_INVALID");

		assertThat(mongoPostCount(author)).isZero();
	}

	@Test
	void 없는_팀_id를_연결하면_POST_TEAM_INVALID다() {
		assertError(createPost(author, linked(recordPost("없는 팀 글"), Long.MAX_VALUE)), "POST_TEAM_INVALID");

		assertThat(mongoPostCount(author)).isZero();
	}

	// ----- 수정 -----

	@Test
	void 수정에서_내가_속한_다른_팀으로_바꾸면_두_곳이_함께_바뀌고_생략하면_함께_비워진다() {
		long first = teams.create(author);
		long second = teams.create(author);
		String id = newPost(author, linked(recordPost("팀 글"), first));

		JsonNode changed = ok(update(author, id, linked(updateInput("팀 글"), second))).data().path("updatePost");
		assertThat(changed.path("teamId").asText()).isEqualTo(Long.toString(second));
		assertLinkedTo(id, second);

		JsonNode cleared = ok(update(author, id, updateInput("팀 글"))).data().path("updatePost");
		assertThat(cleared.path("teamId").isNull()).isTrue();
		assertLinkedTo(id, null);
	}

	// 이미 연결된 팀은 다시 보지 않는다 (CLAUDE.md 결정 표 "글 팀 연결")
	@Test
	void 팀을_나간_뒤에도_그_팀이_연결된_내_글은_연결을_유지한_채_고칠_수_있다() {
		long team = teams.create(other);
		teams.join(team, author);
		String id = newPost(author, linked(recordPost("나가기 전 글"), team));
		teams.leave(team, author);

		JsonNode updated = ok(update(author, id, linked(updateInput("나간 뒤 고친 글"), team))).data().path("updatePost");

		assertThat(updated.path("title").asText()).isEqualTo("나간 뒤 고친 글");
		assertThat(updated.path("teamId").asText()).isEqualTo(Long.toString(team));
		assertLinkedTo(id, team);
	}

	@Test
	void 수정에서_속하지_않은_팀으로_바꾸면_거부되고_기존_연결이_유지된다() {
		long mine = teams.create(author);
		long othersTeam = teams.create(other);
		String id = newPost(author, linked(recordPost("팀 글"), mine));

		assertError(update(author, id, linked(updateInput("바뀐 제목"), othersTeam)), "POST_TEAM_INVALID");

		assertLinkedTo(id, mine);
		assertThat(mongoPost(id).getString("title")).isEqualTo("팀 글");
	}

	// ----- 팀 삭제 -----

	@Test
	void 팀을_삭제하면_글은_남고_Mongo와_post_index의_팀_연결이_함께_끊긴다() {
		long team = teams.create(author);
		String alive = newPost(author, linked(recordPost("남는 글"), team));
		String deleted = newPost(author, linked(recordPost("지운 글"), team));
		ok(gql(bearerFor(author), DELETE_POST, vars("id", deleted)));

		teams.delete(team, author);

		assertLinkedTo(alive, null);
		assertLinkedTo(deleted, null);
		assertThat(mongoPost(alive)).isNotNull();
		assertThat(indexRow(alive).orElseThrow().deletedAt()).isNull();
		assertThat(teamOf(other, alive).path("teamId").isNull()).isTrue();
	}

	// ----- 팀 이름 (E-23) -----

	@Test
	void 공개_팀_글에는_팀_이름이_붙는다() {
		long team = teams.create(author, true);
		String id = newPost(author, linked(recordPost("공개 팀 글"), team));

		assertThat(teamOf(other, id).path("teamName").asText()).isEqualTo(teams.name(team));
	}

	@Test
	void 비공개_팀_글은_열람자가_팀원이어도_팀_이름이_숨겨진다() {
		long team = teams.create(author, false);
		String id = newPost(author, linked(recordPost("비공개 팀 글"), team));

		assertThat(teamOf(author, id).path("teamName").isNull()).isTrue();
		assertThat(teamOf(other, id).path("teamName").isNull()).isTrue();
	}

	@Test
	void 팀을_연결하지_않은_글은_팀_이름이_없다() {
		String id = newPost(author, recordPost("팀 없는 글"));

		JsonNode post = teamOf(other, id);
		assertThat(post.path("teamId").isNull()).isTrue();
		assertThat(post.path("teamName").isNull()).isTrue();
	}

}
