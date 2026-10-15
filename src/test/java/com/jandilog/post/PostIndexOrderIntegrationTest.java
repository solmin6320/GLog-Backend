package com.jandilog.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.jandilog.common.exception.ErrorCode;
import com.jandilog.post.domain.Post;
import com.jandilog.post.service.PostIndexService;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;
import com.jandilog.testsupport.board.BoardIntegrationTest;

// Mongo를 먼저, post_index를 나중에 쓰는 순서와 색인 쓰기 실패 때의 되돌리기 (DB명세서 4-1)
class PostIndexOrderIntegrationTest extends BoardIntegrationTest {

	private static final Instant T0 = Instant.parse("2026-10-01T03:00:00Z");

	@MockitoSpyBean
	private PostIndexService postIndexService;

	// 리졸버 안에서 던진 검사 실패는 내부 오류로 바뀌어 보이지 않으므로 따로 담아 둔다
	private final AtomicReference<Throwable> hookFailure = new AtomicReference<>();

	private long author;

	@BeforeEach
	void setUp() {
		author = activeMember();
	}

	private GraphQlResponse update(String postId, String title, String solution) {
		Map<String, Object> input = Map.of("title", title,
				"sections", Map.of("problem", "문제", "cause", "원인", "solution", solution));
		return gql(bearerFor(author), UPDATE_POST, vars("id", postId, "input", input));
	}

	// 검사를 돌린 뒤 실제 메서드를 이어서 실행한다
	private Object checkThenRun(InvocationOnMock call, Runnable checks) throws Throwable {
		try {
			checks.run();
		} catch (Throwable failure) {
			hookFailure.set(failure);
		}
		return call.callRealMethod();
	}

	private void assertNoHookFailure() {
		assertThat(hookFailure.get()).as("호출 시점 검사").isNull();
	}

	private void assertInternalError(GraphQlResponse response) {
		assertError(response, ErrorCode.INTERNAL_ERROR.name());
		assertThat(response.errorMessage()).isEqualTo(ErrorCode.INTERNAL_ERROR.message());
		// 내부 오류 문구가 사용자에게 새지 않는다
		assertThat(response.rawBody()).doesNotContain("색인 DB 장애").doesNotContain("jdbc:secret");
	}

	// ----- 쓰는 순서 -----

	@Test
	void 글을_쓸_때_post_index를_쓰는_시점에는_Mongo에_글이_이미_있다() {
		doAnswer(call -> checkThenRun(call, () -> {
			Post post = call.getArgument(0);
			assertThat(mongo.findById(post.getId(), Document.class, "posts")).as("색인보다 Mongo가 먼저").isNotNull();
			assertThat(indexRow(post.getId().toHexString())).as("아직 색인은 없다").isEmpty();
		})).when(postIndexService).register(any());

		String id = newPost(author, recordPost("순서 확인"));

		assertNoHookFailure();
		assertThat(mongoPost(id)).isNotNull();
		assertThat(indexRow(id)).isPresent();
	}

	@Test
	void 글을_고칠_때_색인을_맞추는_시점에는_Mongo가_이미_새_내용이다() {
		String id = newPost(author, troubleshooting("원래 제목", "문제", "", ""));
		doAnswer(call -> checkThenRun(call, () -> {
			Post post = call.getArgument(0);
			assertThat(mongo.findById(post.getId(), Document.class, "posts").getString("title")).isEqualTo("새 제목");
			assertThat(indexRow(id).orElseThrow().record()).as("아직 색인은 이전 값").isFalse();
		})).when(postIndexService).sync(any());

		ok(update(id, "새 제목", "해결"));

		assertNoHookFailure();
		assertThat(indexRow(id).orElseThrow().record()).isTrue();
	}

	@Test
	void 글을_지울_때_색인에_삭제_시각을_찍는_시점에는_Mongo에_이미_삭제_표시가_있다() {
		String id = newPost(author, recordPost("지울 글"));
		doAnswer(call -> checkThenRun(call, () -> {
			assertThat(mongoPost(id).get("deletedAt")).as("색인보다 Mongo가 먼저").isNotNull();
			assertThat(indexRow(id).orElseThrow().deletedAt()).isNull();
		})).when(postIndexService).markDeleted(anyString());

		ok(gql(bearerFor(author), DELETE_POST, vars("id", id)));

		assertNoHookFailure();
		assertThat(indexRow(id).orElseThrow().deletedAt()).isNotNull();
	}

	// ----- 색인 쓰기 실패: Mongo를 되돌린다 -----

	@Test
	void 새_글의_색인_쓰기가_실패하면_Mongo에_쓴_글도_지워진다() {
		doThrow(new IllegalStateException("색인 DB 장애 jdbc:secret")).when(postIndexService).register(any());

		GraphQlResponse response = createPost(author, recordPost("실패할 글"));

		assertInternalError(response);
		assertThat(mongoPostCount(author)).isZero();
		assertThat(indexCount(author)).isZero();
	}

	@Test
	void 수정의_색인_쓰기가_실패하면_글이_바뀌기_전_상태로_돌아간다() {
		clock.fixAt(T0);
		String id = newPost(author, troubleshooting("원래 제목", "문제", "원인", ""));
		Document before = mongoPost(id);
		clock.fixAt(T0.plusSeconds(3600));
		doThrow(new IllegalStateException("색인 DB 장애 jdbc:secret")).when(postIndexService).sync(any());

		GraphQlResponse response = update(id, "새 제목", "해결");

		assertInternalError(response);
		Document after = mongoPost(id);
		assertThat(after.getString("title")).isEqualTo("원래 제목");
		assertThat(after.getBoolean("isRecord")).isFalse();
		assertThat(after.getDate("updatedAt")).isEqualTo(before.getDate("updatedAt"));
		assertThat(after.get("sections", Document.class).getString("solution")).isEmpty();
		// 색인도 바뀌지 않았으니 Mongo와 같은 값이다
		assertThat(indexRow(id).orElseThrow().record()).isFalse();
	}

	@Test
	void 삭제의_색인_쓰기가_실패하면_삭제_표시를_되돌려_글이_살아_있다() {
		String id = newPost(author, recordPost("남아야 할 글"));
		doThrow(new IllegalStateException("색인 DB 장애 jdbc:secret")).when(postIndexService).markDeleted(anyString());

		GraphQlResponse response = gql(bearerFor(author), DELETE_POST, vars("id", id));

		assertInternalError(response);
		assertThat(mongoPost(id).get("deletedAt")).isNull();
		assertThat(indexRow(id).orElseThrow().deletedAt()).isNull();
		assertThat(ok(gql(bearerFor(author), GET_POST, vars("id", id))).data().path("post").path("title").asText())
				.isEqualTo("남아야 할 글");
	}

	@Test
	void 색인_실패로_지워진_새_글은_목록에도_남지_않는다() {
		String tag = "o" + members.tag();
		doThrow(new IllegalStateException("색인 DB 장애 jdbc:secret")).when(postIndexService).register(any());

		createPost(author, recordPost("실패할 글", tag));

		PostPage page = postsPage(author, "TROUBLESHOOTING", tag, null, null);
		assertThat(page.items()).isEmpty();
		assertThat(page.totalCount()).isZero();
	}

	// ----- 색인이 빠진 글을 고치면 채워진다 -----

	@Test
	void 색인이_빠져_있던_글을_수정하면_그때_색인이_채워진다() {
		clock.fixAt(Instant.parse("2026-09-28T03:00:00Z"));
		String id = newPost(author, troubleshooting("색인 빠짐", "문제", "", ""));
		// 보정 배치가 없던 시절의 누락을 흉내 낸다
		jdbc.update("delete from post_index where mongo_post_id = ?", id);
		assertThat(indexRow(id)).isEmpty();
		clock.fixAt(Instant.parse("2026-10-04T03:00:00Z"));

		ok(update(id, "색인 빠짐", "해결"));

		IndexRow row = indexRow(id).orElseThrow();
		assertThat(row.authorId()).isEqualTo(author);
		assertThat(row.record()).isTrue();
		// 수정한 날이 아니라 최초 저장일이다
		assertThat(row.writtenDate().toString()).isEqualTo("2026-09-28");
	}

	@Test
	void 색인이_없는_글을_지워도_오류_없이_삭제된다() {
		String id = newPost(author, recordPost("색인 없는 글"));
		jdbc.update("delete from post_index where mongo_post_id = ?", id);

		ok(gql(bearerFor(author), DELETE_POST, vars("id", id)));

		assertThat(mongoPost(id).get("deletedAt")).isNotNull();
		assertThat(indexRow(id)).isEmpty();
	}

}
