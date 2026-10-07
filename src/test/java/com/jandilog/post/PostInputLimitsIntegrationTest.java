package com.jandilog.post;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import com.jandilog.common.exception.ErrorCode;
import com.jandilog.testsupport.auth.AuthIntegrationTest;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;

// 글 입력 상한을 작성·수정 양쪽에서 실제 GraphQL 호출로 확인한다. 넘으면 500이 아니라 입력 검증 오류이고 저장되지 않는다
class PostInputLimitsIntegrationTest extends AuthIntegrationTest {

	private static final String POST_FIELDS = "id sections { problem cause solution did learned } commitUrls isRecord";
	private static final String CREATE_POST = "mutation($input: CreatePostInput!) { createPost(input: $input) { "
			+ POST_FIELDS + " } }";
	private static final String UPDATE_POST = "mutation($id: ID!, $input: UpdatePostInput!) { updatePost(id: $id, input: $input) { "
			+ POST_FIELDS + " } }";

	@Autowired
	private MongoTemplate mongo;

	private long author;

	@BeforeEach
	void setUpAuthor() {
		author = members.active();
	}

	// 내가 만든 회원의 글과 색인만 지운다. 부모 클래스의 회원 삭제보다 먼저 돈다
	@AfterEach
	void cleanPosts() {
		mongo.remove(Query.query(Criteria.where("authorId").is(author)), "posts");
		jdbc.update("delete from post_index where author_id = ?", author);
	}

	// ----- 입력 만들기 -----

	private static Map<String, Object> troubleshooting(String problem, String cause, String solution, List<String> urls) {
		Map<String, Object> sections = new LinkedHashMap<>();
		sections.put("problem", problem);
		sections.put("cause", cause);
		sections.put("solution", solution);
		Map<String, Object> input = new LinkedHashMap<>();
		input.put("type", "TROUBLESHOOTING");
		input.put("title", "제목");
		input.put("sections", sections);
		input.put("commitUrls", urls);
		return input;
	}

	private static Map<String, Object> devlog(String did, String learned) {
		Map<String, Object> sections = new LinkedHashMap<>();
		sections.put("did", did);
		sections.put("learned", learned);
		Map<String, Object> input = new LinkedHashMap<>();
		input.put("type", "DEVLOG");
		input.put("title", "제목");
		input.put("sections", sections);
		return input;
	}

	// 수정 입력에는 글 종류가 없다
	private static Map<String, Object> editOf(Map<String, Object> input) {
		Map<String, Object> edit = new LinkedHashMap<>(input);
		edit.remove("type");
		return edit;
	}

	private static List<String> commitUrls(int count) {
		List<String> urls = new ArrayList<>();
		for (int i = 1; i <= count; i++) {
			urls.add(String.format("https://github.com/owner/repo/commit/%07x", i));
		}
		return urls;
	}

	private GraphQlResponse create(Map<String, Object> input) {
		return graphQl.post(bearerFor(author), CREATE_POST, vars("input", input));
	}

	private GraphQlResponse update(String id, Map<String, Object> input) {
		return graphQl.post(bearerFor(author), UPDATE_POST, vars("id", id, "input", editOf(input)));
	}

	private static Map<String, Object> vars(Object... namesAndValues) {
		Map<String, Object> variables = new HashMap<>();
		for (int i = 0; i < namesAndValues.length; i += 2) {
			variables.put((String) namesAndValues[i], namesAndValues[i + 1]);
		}
		return variables;
	}

	// 입력 검증 오류(400 계열)로 돌려주고 500(E-52)이 아니어야 한다
	private static void assertRejected(GraphQlResponse response, ErrorCode expected) {
		assertThat(response.status()).isEqualTo(200);
		assertThat(response.errorCode()).as(response.rawBody()).isEqualTo(expected.name());
		assertThat(response.errorCode()).isNotEqualTo(ErrorCode.INTERNAL_ERROR.name());
		assertThat(response.errorMessage()).isEqualTo(expected.message());
		assertThat(response.dataIsNull()).isTrue();
	}

	private static String createdId(GraphQlResponse response) {
		assertThat(response.hasErrors()).as(response.rawBody()).isFalse();
		return response.data().path("createPost").path("id").asText();
	}

	private long postCount() {
		return mongo.count(Query.query(Criteria.where("authorId").is(author)), "posts");
	}

	private Document mongoPost(String id) {
		return mongo.findById(new ObjectId(id), Document.class, "posts");
	}

	// ----- 작성 -----

	@Test
	void 작성할_때_항목이_5000자면_저장되고_5001자면_항목별_오류로_저장되지_않는다() {
		String max = "a".repeat(5000);
		String over = "a".repeat(5001);

		String id = createdId(create(troubleshooting(max, max, max, List.of())));
		assertThat(mongoPost(id).get("sections", Document.class).getString("cause")).hasSize(5000);
		long saved = postCount();

		assertRejected(create(troubleshooting(over, "원인", "해결", List.of())), ErrorCode.POST_PROBLEM_TOO_LONG);
		assertRejected(create(troubleshooting("문제", over, "해결", List.of())), ErrorCode.POST_CAUSE_TOO_LONG);
		assertRejected(create(troubleshooting("문제", "원인", over, List.of())), ErrorCode.POST_SOLUTION_TOO_LONG);
		assertRejected(create(devlog(over, "배운 점")), ErrorCode.POST_DID_TOO_LONG);
		assertRejected(create(devlog("한 일", over)), ErrorCode.POST_LEARNED_TOO_LONG);
		assertThat(postCount()).isEqualTo(saved);
		assertThat(indexCount()).isEqualTo(saved);
	}

	@Test
	void 작성할_때_앞뒤_공백을_뺀_글자_수로_센다() {
		String paddedMax = "  " + "가".repeat(5000) + "  ";
		String paddedOver = "  " + "가".repeat(5001) + "  ";

		createdId(create(devlog(paddedMax, "배운 점")));
		assertRejected(create(devlog(paddedOver, "배운 점")), ErrorCode.POST_DID_TOO_LONG);
	}

	@Test
	void 작성할_때_커밋_링크가_5개면_저장되고_6개면_오류로_저장되지_않는다() {
		String id = createdId(create(troubleshooting("문제", "원인", "해결", commitUrls(5))));
		assertThat(mongoPost(id).getList("commitUrls", String.class)).hasSize(5);
		long saved = postCount();

		assertRejected(create(troubleshooting("문제", "원인", "해결", commitUrls(6))),
				ErrorCode.POST_COMMIT_URLS_LIMIT_EXCEEDED);
		assertThat(postCount()).isEqualTo(saved);
	}

	// ----- 수정 -----

	@Test
	void 수정할_때_항목이_5000자면_바뀌고_5001자면_항목별_오류로_그대로다() {
		String id = createdId(create(troubleshooting("문제", "원인", "해결", commitUrls(1))));
		String max = "b".repeat(5000);
		String over = "b".repeat(5001);

		GraphQlResponse ok = update(id, troubleshooting(max, "원인", "해결", commitUrls(1)));
		assertThat(ok.hasErrors()).as(ok.rawBody()).isFalse();
		assertThat(mongoPost(id).get("sections", Document.class).getString("problem")).hasSize(5000);

		assertRejected(update(id, troubleshooting(over, "원인", "해결", commitUrls(1))), ErrorCode.POST_PROBLEM_TOO_LONG);
		assertRejected(update(id, troubleshooting("문제", over, "해결", commitUrls(1))), ErrorCode.POST_CAUSE_TOO_LONG);
		assertRejected(update(id, troubleshooting("문제", "원인", over, commitUrls(1))), ErrorCode.POST_SOLUTION_TOO_LONG);
		assertThat(mongoPost(id).get("sections", Document.class).getString("problem")).hasSize(5000);
		assertThat(mongoPost(id).get("sections", Document.class).getString("cause")).isEqualTo("원인");
	}

	@Test
	void 개발일지도_수정할_때_5001자면_항목별_오류로_그대로다() {
		String id = createdId(create(devlog("한 일", "배운 점")));
		String over = "c".repeat(5001);

		assertRejected(update(id, devlog(over, "배운 점")), ErrorCode.POST_DID_TOO_LONG);
		assertRejected(update(id, devlog("한 일", over)), ErrorCode.POST_LEARNED_TOO_LONG);
		Document sections = mongoPost(id).get("sections", Document.class);
		assertThat(sections.getString("did")).isEqualTo("한 일");
		assertThat(sections.getString("learned")).isEqualTo("배운 점");
	}

	@Test
	void 수정할_때_커밋_링크가_5개면_바뀌고_6개면_오류로_그대로다() {
		String id = createdId(create(troubleshooting("문제", "원인", "해결", commitUrls(1))));

		GraphQlResponse ok = update(id, troubleshooting("문제", "원인", "해결", commitUrls(5)));
		assertThat(ok.hasErrors()).as(ok.rawBody()).isFalse();
		assertThat(mongoPost(id).getList("commitUrls", String.class)).hasSize(5);

		assertRejected(update(id, troubleshooting("문제", "원인", "해결", commitUrls(6))),
				ErrorCode.POST_COMMIT_URLS_LIMIT_EXCEEDED);
		assertThat(mongoPost(id).getList("commitUrls", String.class)).hasSize(5);
	}

	@Test
	void 상한을_넘어도_기록글_여부와_post_index는_이전_값_그대로다() {
		String id = createdId(create(troubleshooting("문제", "원인", "해결", List.of())));

		assertRejected(update(id, troubleshooting("문제", "원인", "d".repeat(5001), List.of())),
				ErrorCode.POST_SOLUTION_TOO_LONG);

		assertThat(mongoPost(id).getBoolean("isRecord")).isTrue();
		Boolean indexRecord = jdbc.queryForObject("select is_record from post_index where mongo_post_id = ?",
				Boolean.class, id);
		assertThat(indexRecord).isTrue();
	}

	private long indexCount() {
		Long count = jdbc.queryForObject("select count(*) from post_index where author_id = ?", Long.class, author);
		return count == null ? 0 : count;
	}

}
