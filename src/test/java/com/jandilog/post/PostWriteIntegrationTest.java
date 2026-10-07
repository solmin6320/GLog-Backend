package com.jandilog.post;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;
import com.jandilog.testsupport.board.BoardIntegrationTest;

// 글 작성·수정·삭제와 MongoDB·post_index 동기화 (기능명세서 3장, DB명세서 1-5·3-1·4-1, Q-11)
class PostWriteIntegrationTest extends BoardIntegrationTest {

	private static final Instant T0 = Instant.parse("2026-10-01T03:00:00Z");

	private long author;
	private long other;

	@BeforeEach
	void setUp() {
		author = activeMember();
		other = activeMember();
	}

	private static Map<String, Object> updateInput(String title, String problem, String cause, String solution) {
		Map<String, Object> sections = new LinkedHashMap<>();
		sections.put("problem", problem);
		sections.put("cause", cause);
		sections.put("solution", solution);
		Map<String, Object> input = new LinkedHashMap<>();
		input.put("title", title);
		input.put("sections", sections);
		return input;
	}

	private GraphQlResponse update(long memberId, String postId, Map<String, Object> input) {
		return gql(bearerFor(memberId), UPDATE_POST, vars("id", postId, "input", input));
	}

	private GraphQlResponse delete(long memberId, String postId) {
		return gql(bearerFor(memberId), DELETE_POST, vars("id", postId));
	}

	private GraphQlResponse detail(long memberId, String postId) {
		return gql(bearerFor(memberId), GET_POST, vars("id", postId));
	}

	// ----- 작성: 저장 결과 -----

	@Test
	void 트러블슈팅_기록글을_쓰면_Mongo와_post_index에_함께_저장된다() {
		clock.fixAt(T0);
		Map<String, Object> input = troubleshooting("  Redis TTL 문제  ", " 문제 ", "원인", "해결");
		input.put("tags", List.of(" Redis ", "SPRING"));
		input.put("commitUrls", List.of("https://github.com/octo/repo/commit/abc1234"));

		JsonNode post = ok(createPost(author, input)).data().path("createPost");

		String id = post.path("id").asText();
		assertThat(id).matches("^[0-9a-f]{24}$");
		assertThat(post.path("authorId").asText()).isEqualTo(Long.toString(author));
		assertThat(post.path("type").asText()).isEqualTo("TROUBLESHOOTING");
		assertThat(post.path("title").asText()).isEqualTo("Redis TTL 문제");
		assertThat(post.path("sections").path("problem").asText()).isEqualTo("문제");
		assertThat(post.path("sections").path("cause").asText()).isEqualTo("원인");
		assertThat(post.path("sections").path("solution").asText()).isEqualTo("해결");
		assertThat(post.path("sections").path("did").isNull()).isTrue();
		assertThat(post.path("tags")).extracting(JsonNode::asText).containsExactly("redis", "spring");
		assertThat(post.path("commitUrls")).extracting(JsonNode::asText)
				.containsExactly("https://github.com/octo/repo/commit/abc1234");
		assertThat(post.path("teamId").isNull()).isTrue();
		assertThat(post.path("isRecord").asBoolean()).isTrue();
		assertThat(post.path("writtenDate").asText()).isEqualTo("2026-10-01");
		assertThat(post.path("createdAt").asText()).isEqualTo("2026-10-01T12:00:00");
		assertThat(post.path("updatedAt").asText()).isEqualTo("2026-10-01T12:00:00");

		Document doc = mongoPost(id);
		assertThat(doc).isNotNull();
		assertThat(doc.getLong("authorId")).isEqualTo(author);
		assertThat(doc.getString("type")).isEqualTo("TROUBLESHOOTING");
		assertThat(doc.getBoolean("isRecord")).isTrue();
		assertThat(doc.getString("writtenDate")).isEqualTo("2026-10-01");
		assertThat(doc.getDate("createdAt").toInstant()).isEqualTo(T0);
		assertThat(doc.get("deletedAt")).isNull();
		assertThat(doc.get("sections", Document.class).getString("problem")).isEqualTo("문제");

		IndexRow index = indexRow(id).orElseThrow();
		assertThat(index.authorId()).isEqualTo(author);
		assertThat(index.postType()).isEqualTo("TROUBLESHOOTING");
		assertThat(index.record()).isTrue();
		assertThat(index.writtenDate()).isEqualTo(LocalDate.of(2026, 10, 1));
		assertThat(index.teamId()).isNull();
		assertThat(index.deletedAt()).isNull();
	}

	@Test
	void 개발일지_기록글을_쓰면_종류에_맞는_항목만_저장된다() {
		// 종류에 해당하지 않는 항목(problem)을 함께 보내도 무시한다
		Map<String, Object> sent = new LinkedHashMap<>();
		sent.put("did", "API 설계");
		sent.put("learned", "커서 페이지네이션");
		sent.put("problem", "무시될 문제");
		Map<String, Object> input = new LinkedHashMap<>();
		input.put("type", "DEVLOG");
		input.put("title", "오늘의 개발일지");
		input.put("sections", sent);

		JsonNode post = ok(createPost(author, input)).data().path("createPost");

		assertThat(post.path("type").asText()).isEqualTo("DEVLOG");
		assertThat(post.path("sections").path("did").asText()).isEqualTo("API 설계");
		assertThat(post.path("sections").path("learned").asText()).isEqualTo("커서 페이지네이션");
		assertThat(post.path("sections").path("problem").isNull()).isTrue();
		assertThat(post.path("isRecord").asBoolean()).isTrue();
		Document sections = mongoPost(post.path("id").asText()).get("sections", Document.class);
		assertThat(sections.get("problem")).isNull();
		assertThat(sections.getString("did")).isEqualTo("API 설계");
		assertThat(indexRow(post.path("id").asText()).orElseThrow().postType()).isEqualTo("DEVLOG");
	}

	@Test
	void 태그와_커밋_링크와_팀을_생략하면_빈_목록과_null이다() {
		JsonNode post = ok(createPost(author, recordPost("제목"))).data().path("createPost");

		assertThat(post.path("tags")).isEmpty();
		assertThat(post.path("commitUrls")).isEmpty();
		assertThat(post.path("teamId").isNull()).isTrue();
	}

	// ----- 작성: 기록글 인정 여부 (E-25) -----

	private static Stream<Arguments> incompleteInputs() {
		return Stream.of(
				Arguments.of("문제 비어 있음", troubleshooting("t", "", "원인", "해결")),
				Arguments.of("원인 비어 있음", troubleshooting("t", "문제", "", "해결")),
				Arguments.of("해결 비어 있음", troubleshooting("t", "문제", "원인", "")),
				Arguments.of("문제 공백뿐", troubleshooting("t", "   ", "원인", "해결")),
				Arguments.of("원인 줄바꿈뿐", troubleshooting("t", "문제", "\n\t", "해결")),
				Arguments.of("트러블슈팅 항목 전부 null", troubleshooting("t", null, null, null)),
				Arguments.of("한 일 비어 있음", devlog("t", "", "배운 점")),
				Arguments.of("배운 점 비어 있음", devlog("t", "한 일", "")),
				Arguments.of("한 일 공백뿐", devlog("t", "  ", "배운 점")),
				Arguments.of("개발일지 항목 전부 null", devlog("t", null, null)));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("incompleteInputs")
	void 필수_항목이_비면_저장은_되지만_기록글로_인정되지_않는다(String label, Map<String, Object> input) {
		JsonNode post = ok(createPost(author, input)).data().path("createPost");

		String id = post.path("id").asText();
		assertThat(post.path("isRecord").asBoolean()).isFalse();
		assertThat(mongoPost(id).getBoolean("isRecord")).isFalse();
		// 색인에도 기록글이 아님으로 남아 판정이 인증으로 세지 않는다
		assertThat(indexRow(id).orElseThrow().record()).isFalse();
		assertThat(ok(detail(author, id)).data().path("post").path("isRecord").asBoolean()).isFalse();
	}

	@Test
	void 항목_앞뒤_공백은_지우고_저장한다() {
		JsonNode post = ok(createPost(author, troubleshooting("제목", "  문제  ", "\n원인\n", " 해결 "))).data()
				.path("createPost");

		assertThat(post.path("sections").path("problem").asText()).isEqualTo("문제");
		assertThat(post.path("sections").path("cause").asText()).isEqualTo("원인");
		assertThat(post.path("sections").path("solution").asText()).isEqualTo("해결");
	}

	// ----- 작성: 작성일 (E-27, Q-11) -----

	@ParameterizedTest(name = "저장 시각 {0}이면 작성일 {1}")
	@CsvSource({
			"2026-10-04T14:59:59Z, 2026-10-04",
			"2026-10-04T15:00:00Z, 2026-10-05",
			"2026-09-30T14:59:59Z, 2026-09-30",
			"2026-09-30T15:00:00Z, 2026-10-01",
			"2026-12-31T14:59:59Z, 2026-12-31",
			"2026-12-31T15:00:00Z, 2027-01-01"})
	void 작성일은_서버_저장_시각의_KST_날짜다(String savedAt, String expectedDate) {
		clock.fixAt(Instant.parse(savedAt));

		JsonNode post = ok(createPost(author, recordPost("경계"))).data().path("createPost");

		assertThat(post.path("writtenDate").asText()).isEqualTo(expectedDate);
		assertThat(mongoPost(post.path("id").asText()).getString("writtenDate")).isEqualTo(expectedDate);
		assertThat(indexRow(post.path("id").asText()).orElseThrow().writtenDate()).isEqualTo(LocalDate.parse(expectedDate));
	}

	@Test
	void 일요일_23시_59분_59초에_쓴_글은_일요일_월요일_00시_00분_00초에_쓴_글은_월요일이다() {
		clock.fixAt(Instant.parse("2026-10-04T14:59:59Z"));
		JsonNode sunday = ok(createPost(author, recordPost("일요일"))).data().path("createPost");
		clock.fixAt(Instant.parse("2026-10-04T15:00:00Z"));
		JsonNode monday = ok(createPost(author, recordPost("월요일"))).data().path("createPost");

		assertThat(sunday.path("writtenDate").asText()).isEqualTo("2026-10-04");
		assertThat(sunday.path("createdAt").asText()).isEqualTo("2026-10-04T23:59:59");
		assertThat(monday.path("writtenDate").asText()).isEqualTo("2026-10-05");
		assertThat(monday.path("createdAt").asText()).isEqualTo("2026-10-05T00:00:00");
	}

	@Test
	void 클라이언트가_작성일을_보내면_스키마에_없는_항목이라_거부되고_저장되지_않는다() {
		Map<String, Object> input = recordPost("작성일 조작");
		input.put("writtenDate", "2020-01-01");

		GraphQlResponse response = createPost(author, input);

		assertThat(response.hasErrors()).isTrue();
		assertThat(mongoPostCount(author)).isZero();
		assertThat(indexCount(author)).isZero();
	}

	@Test
	void 실제_시계로_쓴_글도_작성_응답의_시각이_다시_읽은_값과_같다() {
		clock.reset();
		JsonNode created = ok(createPost(author, recordPost("시각 확인"))).data().path("createPost");

		JsonNode read = ok(detail(author, created.path("id").asText())).data().path("post");

		assertThat(read.path("createdAt").asText()).isEqualTo(created.path("createdAt").asText());
		assertThat(read.path("updatedAt").asText()).isEqualTo(created.path("updatedAt").asText());
		assertThat(created.path("updatedAt").asText()).isEqualTo(created.path("createdAt").asText());
	}

	// ----- 작성: 입력 검사 -----

	@Test
	void 제목이_비면_TITLE_REQUIRED이고_아무것도_저장되지_않는다() {
		assertError(createPost(author, recordPost("")), ErrorCode.TITLE_REQUIRED.name());
		assertError(createPost(author, recordPost("   ")), ErrorCode.TITLE_REQUIRED.name());

		assertThat(mongoPostCount(author)).isZero();
		assertThat(indexCount(author)).isZero();
	}

	@Test
	void 제목은_60자까지_허용하고_61자부터_INVALID_INPUT이다() {
		assertThat(ok(createPost(author, recordPost("가".repeat(60)))).data().path("createPost").path("title").asText())
				.hasSize(60);
		assertError(createPost(author, recordPost("가".repeat(61))), ErrorCode.INVALID_INPUT.name());
		assertThat(mongoPostCount(author)).isEqualTo(1);
	}

	@Test
	void 태그는_소문자로_정리하고_중복은_합친다() {
		JsonNode post = ok(createPost(author, recordPost("제목", "Redis", " redis ", "SPRING", "", "Spring"))).data()
				.path("createPost");

		assertThat(post.path("tags")).extracting(JsonNode::asText).containsExactly("redis", "spring");
	}

	@Test
	void 태그가_6개면_TAG_LIMIT_EXCEEDED이고_저장되지_않는다() {
		GraphQlResponse response = createPost(author, recordPost("제목", "a", "b", "c", "d", "e", "f"));

		assertError(response, ErrorCode.TAG_LIMIT_EXCEEDED.name());
		assertThat(response.errorMessage()).isEqualTo("태그는 5개까지 달 수 있어요.");
		assertThat(mongoPostCount(author)).isZero();
	}

	@Test
	void 태그_5개는_허용한다() {
		JsonNode post = ok(createPost(author, recordPost("제목", "a", "b", "c", "d", "e"))).data().path("createPost");

		assertThat(post.path("tags")).hasSize(5);
	}

	@Test
	void 태그_한_개가_21자면_TAG_TOO_LONG이다() {
		assertThat(ok(createPost(author, recordPost("제목", "a".repeat(20)))).data().path("createPost").path("tags")).hasSize(1);
		assertError(createPost(author, recordPost("제목", "a".repeat(21))), ErrorCode.TAG_TOO_LONG.name());
	}

	@Test
	void 커밋_링크가_GitHub_커밋_주소_형식이_아니면_INVALID_COMMIT_URL이다() {
		Map<String, Object> input = recordPost("제목");
		input.put("commitUrls", List.of("https://github.com/octo/repo/commit/abc1234", "https://example.com/commit/abc1234"));

		GraphQlResponse response = createPost(author, input);

		assertError(response, ErrorCode.INVALID_COMMIT_URL.name());
		assertThat(response.errorMessage()).isEqualTo("GitHub 커밋 주소 형식이 아니에요.");
		assertThat(mongoPostCount(author)).isZero();
	}

	@Test
	void 커밋_링크_형식만_보고_실제_존재_여부는_확인하지_않는다() {
		Map<String, Object> input = recordPost("제목");
		input.put("commitUrls", List.of("https://github.com/no-such-owner-zzz/no-such-repo-zzz/commit/0000000"));

		JsonNode post = ok(createPost(author, input)).data().path("createPost");

		assertThat(post.path("commitUrls")).hasSize(1);
	}

	@Test
	void 팀_id가_양의_정수가_아니면_INVALID_INPUT이다() {
		Map<String, Object> input = recordPost("제목");
		input.put("teamId", "abc");

		assertError(createPost(author, input), ErrorCode.INVALID_INPUT.name());
		assertThat(mongoPostCount(author)).isZero();
	}

	@Test
	void 글_종류를_빼면_스키마_검증에서_거부된다() {
		Map<String, Object> input = recordPost("제목");
		input.remove("type");

		GraphQlResponse response = createPost(author, input);

		assertThat(response.hasErrors()).isTrue();
		assertThat(mongoPostCount(author)).isZero();
	}

	// ----- 수정 -----

	@Test
	void 작성자가_수정하면_내용이_바뀌고_종류_작성일_작성_시각은_그대로다() {
		clock.fixAt(T0);
		String id = newPost(author, recordPost("원래 제목", "redis"));
		clock.fixAt(Instant.parse("2026-10-06T01:30:00Z"));
		Map<String, Object> input = updateInput("새 제목", "새 문제", "새 원인", "새 해결");
		input.put("tags", List.of("Spring"));
		input.put("commitUrls", List.of("https://github.com/octo/repo/commit/def5678"));

		JsonNode post = ok(update(author, id, input)).data().path("updatePost");

		assertThat(post.path("id").asText()).isEqualTo(id);
		assertThat(post.path("title").asText()).isEqualTo("새 제목");
		assertThat(post.path("sections").path("problem").asText()).isEqualTo("새 문제");
		assertThat(post.path("tags")).extracting(JsonNode::asText).containsExactly("spring");
		assertThat(post.path("commitUrls")).hasSize(1);
		assertThat(post.path("type").asText()).isEqualTo("TROUBLESHOOTING");
		assertThat(post.path("authorId").asText()).isEqualTo(Long.toString(author));
		assertThat(post.path("writtenDate").asText()).isEqualTo("2026-10-01");
		assertThat(post.path("createdAt").asText()).isEqualTo("2026-10-01T12:00:00");
		assertThat(post.path("updatedAt").asText()).isEqualTo("2026-10-06T10:30:00");
		Document doc = mongoPost(id);
		assertThat(doc.getString("title")).isEqualTo("새 제목");
		assertThat(doc.getString("writtenDate")).isEqualTo("2026-10-01");
		assertThat(doc.getDate("createdAt").toInstant()).isEqualTo(T0);
		assertThat(ok(detail(other, id)).data().path("post").path("title").asText()).isEqualTo("새 제목");
	}

	@Test
	void 수정은_생략한_태그와_커밋_링크를_비운다() {
		Map<String, Object> create = recordPost("제목", "redis", "spring");
		create.put("commitUrls", List.of("https://github.com/octo/repo/commit/abc1234"));
		String id = newPost(author, create);

		JsonNode post = ok(update(author, id, updateInput("제목", "문제", "원인", "해결"))).data().path("updatePost");

		assertThat(post.path("tags")).isEmpty();
		assertThat(post.path("commitUrls")).isEmpty();
		assertThat(mongoPost(id).getList("tags", String.class)).isEmpty();
	}

	@Test
	void 필수_항목을_비워_둔_글을_나중에_채우면_기록글이_되지만_작성일은_처음_저장일_그대로다() {
		clock.fixAt(Instant.parse("2026-09-28T03:00:00Z"));
		String id = newPost(author, troubleshooting("나중에 채움", "문제", "", ""));
		assertThat(indexRow(id).orElseThrow().record()).isFalse();
		// 엿새 뒤에 필수 항목을 채워서 수정
		clock.fixAt(Instant.parse("2026-10-04T03:00:00Z"));

		JsonNode post = ok(update(author, id, updateInput("나중에 채움", "문제", "원인", "해결"))).data().path("updatePost");

		assertThat(post.path("isRecord").asBoolean()).isTrue();
		assertThat(post.path("writtenDate").asText()).isEqualTo("2026-09-28");
		assertThat(mongoPost(id).getBoolean("isRecord")).isTrue();
		IndexRow index = indexRow(id).orElseThrow();
		assertThat(index.record()).isTrue();
		assertThat(index.writtenDate()).isEqualTo(LocalDate.of(2026, 9, 28));
	}

	@Test
	void 기록글을_고쳐서_필수_항목을_비우면_기록글이_아니게_된다() {
		String id = newPost(author, recordPost("제목"));
		assertThat(indexRow(id).orElseThrow().record()).isTrue();

		JsonNode post = ok(update(author, id, updateInput("제목", "문제", "원인", ""))).data().path("updatePost");

		assertThat(post.path("isRecord").asBoolean()).isFalse();
		assertThat(mongoPost(id).getBoolean("isRecord")).isFalse();
		assertThat(indexRow(id).orElseThrow().record()).isFalse();
	}

	@Test
	void 수정해도_post_index_행은_하나이고_삭제_시각은_비어_있다() {
		String id = newPost(author, recordPost("제목"));

		ok(update(author, id, updateInput("제목2", "문제", "원인", "해결")));
		ok(update(author, id, updateInput("제목3", "문제", "원인", "해결")));

		assertThat(indexCount(author)).isEqualTo(1);
		assertThat(indexRow(id).orElseThrow().deletedAt()).isNull();
	}

	@Test
	void 작성자가_아니면_수정할_수_없고_글은_그대로다() {
		String id = newPost(author, recordPost("원래 제목"));

		GraphQlResponse response = update(other, id, updateInput("바꿔치기", "x", "y", "z"));

		assertError(response, ErrorCode.FORBIDDEN.name());
		assertThat(mongoPost(id).getString("title")).isEqualTo("원래 제목");
	}

	@Test
	void 수정_입력이_잘못되면_글이_바뀌지_않는다() {
		String id = newPost(author, recordPost("원래 제목", "redis"));

		assertError(update(author, id, updateInput("  ", "문제", "원인", "해결")), ErrorCode.TITLE_REQUIRED.name());
		Map<String, Object> tooManyTags = updateInput("제목", "문제", "원인", "해결");
		tooManyTags.put("tags", List.of("a", "b", "c", "d", "e", "f"));
		assertError(update(author, id, tooManyTags), ErrorCode.TAG_LIMIT_EXCEEDED.name());

		Document doc = mongoPost(id);
		assertThat(doc.getString("title")).isEqualTo("원래 제목");
		assertThat(doc.getList("tags", String.class)).containsExactly("redis");
	}

	@Test
	void 글_종류는_수정_입력에_없어서_바꿀_수_없다() {
		String id = newPost(author, recordPost("제목"));
		Map<String, Object> input = updateInput("제목", "문제", "원인", "해결");
		input.put("type", "DEVLOG");

		GraphQlResponse response = update(author, id, input);

		assertThat(response.hasErrors()).isTrue();
		assertThat(mongoPost(id).getString("type")).isEqualTo("TROUBLESHOOTING");
	}

	@Test
	void 없는_글이나_잘못된_id를_수정하면_NOT_FOUND다() {
		Map<String, Object> input = updateInput("제목", "문제", "원인", "해결");

		assertError(update(author, "0".repeat(24), input), ErrorCode.NOT_FOUND.name());
		assertError(update(author, "not-an-object-id", input), ErrorCode.NOT_FOUND.name());
	}

	@Test
	void 삭제된_글을_수정하면_POST_DELETED이고_되살아나지_않는다() {
		String id = newPost(author, recordPost("제목"));
		ok(delete(author, id));

		GraphQlResponse response = update(author, id, updateInput("되살리기", "문제", "원인", "해결"));

		assertError(response, ErrorCode.POST_DELETED.name());
		assertThat(response.errorMessage()).isEqualTo("삭제된 글이에요.");
		assertThat(mongoPost(id).get("deletedAt")).isNotNull();
		assertThat(mongoPost(id).getString("title")).isEqualTo("제목");
		assertThat(indexRow(id).orElseThrow().deletedAt()).isNotNull();
	}

	// ----- 삭제 -----

	@Test
	void 작성자가_삭제하면_Mongo와_post_index에_삭제_표시가_남고_글은_지워지지_않는다() {
		clock.fixAt(T0);
		String id = newPost(author, recordPost("삭제할 글"));
		clock.fixAt(Instant.parse("2026-10-02T05:00:00Z"));

		GraphQlResponse response = ok(delete(author, id));

		assertThat(response.data().path("deletePost").asBoolean()).isTrue();
		Document doc = mongoPost(id);
		assertThat(doc).isNotNull();
		assertThat(doc.getDate("deletedAt").toInstant()).isEqualTo(Instant.parse("2026-10-02T05:00:00Z"));
		IndexRow index = indexRow(id).orElseThrow();
		assertThat(index.deletedAt()).isNotNull();
		assertThat(index.deletedAt().toLocalDate()).isEqualTo(LocalDate.of(2026, 10, 2));
		// 판정은 deleted_at으로만 제외하므로 기록글 여부와 작성일은 그대로 남는다
		assertThat(index.record()).isTrue();
		assertThat(index.writtenDate()).isEqualTo(LocalDate.of(2026, 10, 1));
	}

	@Test
	void 삭제된_글_상세는_404가_아니라_POST_DELETED다() {
		String id = newPost(author, recordPost("삭제할 글"));
		ok(delete(author, id));

		GraphQlResponse response = detail(other, id);

		assertError(response, ErrorCode.POST_DELETED.name());
		assertThat(response.errorMessage()).isEqualTo("삭제된 글이에요.");
	}

	@Test
	void 작성자가_아니면_삭제할_수_없고_글은_살아_있다() {
		String id = newPost(author, recordPost("남의 글"));

		assertError(delete(other, id), ErrorCode.FORBIDDEN.name());

		assertThat(mongoPost(id).get("deletedAt")).isNull();
		assertThat(indexRow(id).orElseThrow().deletedAt()).isNull();
		assertThat(ok(detail(other, id)).data().path("post").path("id").asText()).isEqualTo(id);
	}

	@Test
	void 이미_삭제한_글을_다시_삭제하면_POST_DELETED다() {
		String id = newPost(author, recordPost("한 번만"));
		ok(delete(author, id));

		assertError(delete(author, id), ErrorCode.POST_DELETED.name());
	}

	@Test
	void 없는_글을_삭제하면_NOT_FOUND다() {
		assertError(delete(author, "0".repeat(24)), ErrorCode.NOT_FOUND.name());
		assertError(delete(author, "xyz"), ErrorCode.NOT_FOUND.name());
	}

	@Test
	void 한_글을_삭제해도_같은_작성자의_다른_글은_그대로다() {
		String keep = newPost(author, recordPost("남길 글"));
		String gone = newPost(author, recordPost("지울 글"));

		ok(delete(author, gone));

		assertThat(ok(detail(author, keep)).data().path("post").path("title").asText()).isEqualTo("남길 글");
		assertThat(indexRow(keep).orElseThrow().deletedAt()).isNull();
		assertThat(indexRow(gone).orElseThrow().deletedAt()).isNotNull();
	}

	@Test
	void 상세에서_없는_글은_NOT_FOUND다() {
		assertError(detail(author, "0".repeat(24)), ErrorCode.NOT_FOUND.name());
		assertError(detail(author, "abc"), ErrorCode.NOT_FOUND.name());
	}

}
