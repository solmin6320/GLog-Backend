package com.jandilog.post;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import com.fasterxml.jackson.databind.JsonNode;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;
import com.jandilog.testsupport.board.BoardIntegrationTest;

// 같은 글에 겹쳐 들어오는 요청: 댓글 동시 작성, 수정과 삭제가 겹칠 때 (EX-BD04-02, Q-11)
class PostConcurrencyIntegrationTest extends BoardIntegrationTest {

	private static final int ROUNDS = 25;

	// 모든 작업을 같은 순간에 출발시키고 결과를 순서대로 모은다
	private static List<GraphQlResponse> runTogether(List<Callable<GraphQlResponse>> tasks) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
		try {
			CountDownLatch ready = new CountDownLatch(tasks.size());
			CountDownLatch go = new CountDownLatch(1);
			List<Future<GraphQlResponse>> futures = new ArrayList<>();
			for (Callable<GraphQlResponse> task : tasks) {
				futures.add(pool.submit(() -> {
					ready.countDown();
					go.await();
					return task.call();
				}));
			}
			ready.await(10, TimeUnit.SECONDS);
			go.countDown();
			List<GraphQlResponse> responses = new ArrayList<>();
			for (Future<GraphQlResponse> future : futures) {
				responses.add(future.get(60, TimeUnit.SECONDS));
			}
			return responses;
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void 같은_글에_여러_회원이_동시에_댓글을_달아도_모두_저장되고_수가_맞다() throws Exception {
		long author = activeMember();
		String postId = newPost(author, recordPost("동시 댓글"));
		int count = 8;
		List<Long> commenters = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			commenters.add(activeMember());
		}
		List<Callable<GraphQlResponse>> tasks = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			long commenter = commenters.get(i);
			String content = "동시 댓글 " + i;
			tasks.add(() -> gql(bearerFor(commenter), CREATE_COMMENT, vars("postId", postId, "content", content)));
		}

		List<GraphQlResponse> responses = runTogether(tasks);

		Set<String> createdIds = new HashSet<>();
		for (GraphQlResponse response : responses) {
			createdIds.add(ok(response).data().path("createComment").path("id").asText());
		}
		assertThat(createdIds).hasSize(count);
		JsonNode post = ok(gql(bearerFor(author), "query($id: ID!) { post(id: $id) { commentCount comments { items { id content } } } }",
				vars("id", postId))).data().path("post");
		assertThat(post.path("commentCount").asInt()).isEqualTo(count);
		assertThat(post.path("comments").path("items")).hasSize(count);
		Set<String> contents = new HashSet<>();
		post.path("comments").path("items").forEach(comment -> contents.add(comment.path("content").asText()));
		assertThat(contents).hasSize(count);
	}

	@Test
	void 같은_회원이_같은_글에_동시에_같은_댓글을_여러_번_보내도_요청마다_따로_저장된다() throws Exception {
		long author = activeMember();
		long commenter = activeMember();
		String postId = newPost(author, recordPost("중복 댓글"));
		List<Callable<GraphQlResponse>> tasks = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			tasks.add(() -> gql(bearerFor(commenter), CREATE_COMMENT, vars("postId", postId, "content", "같은 내용")));
		}

		runTogether(tasks).forEach(BoardIntegrationTest::ok);

		// 명세에 중복 댓글 차단 규칙이 없어서 요청 수만큼 쌓인다
		assertThat(mongo.count(Query.query(Criteria.where("postId").is(new ObjectId(postId))), "comments")).isEqualTo(4);
	}

	@Test
	void 수정과_삭제가_동시에_들어와도_삭제는_Mongo와_post_index_모두에_남고_두_곳의_기록글_여부가_같다() throws Exception {
		long author = activeMember();
		List<String> violations = new ArrayList<>();
		for (int round = 0; round < ROUNDS; round++) {
			String id = newPost(author, troubleshooting("원래 제목 " + round, "문제", "원인", ""));
			Map<String, Object> input = Map.of("title", "새 제목 " + round,
					"sections", Map.of("problem", "문제", "cause", "원인", "solution", "해결"));
			List<Callable<GraphQlResponse>> tasks = List.of(
					() -> gql(bearerFor(author), UPDATE_POST, vars("id", id, "input", input)),
					() -> gql(bearerFor(author), DELETE_POST, vars("id", id)));

			List<GraphQlResponse> responses = runTogether(tasks);

			GraphQlResponse update = responses.get(0);
			GraphQlResponse delete = responses.get(1);
			String updateResult = update.hasErrors() ? update.errorCode() : "OK";
			Document doc = mongoPost(id);
			IndexRow index = indexRow(id).orElseThrow();
			String state = "round %d: 수정=%s 삭제오류=%s mongo(삭제=%s, 기록글=%s) index(삭제=%s, 기록글=%s)".formatted(round,
					updateResult, delete.hasErrors(), doc.get("deletedAt") != null, doc.getBoolean("isRecord"),
					index.deletedAt() != null, index.record());
			// 삭제는 항상 성공하고, 수정은 먼저 들어갔으면 성공, 늦었으면 삭제된 글 오류다
			if (delete.hasErrors() || !(updateResult.equals("OK") || updateResult.equals(ErrorCode.POST_DELETED.name()))
					|| doc.get("deletedAt") == null || index.deletedAt() == null
					|| index.record() != doc.getBoolean("isRecord")) {
				violations.add(state);
			}
		}
		assertThat(violations).as("어긋난 라운드").isEmpty();
	}

}
