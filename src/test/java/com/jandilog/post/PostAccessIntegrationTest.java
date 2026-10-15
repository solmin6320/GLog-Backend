package com.jandilog.post;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.jandilog.common.exception.ErrorCode;
import com.jandilog.member.domain.MemberStatus;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;
import com.jandilog.testsupport.board.BoardIntegrationTest;

// 게시판 루트 필드 접근 제어: 비로그인·승인 대기·거절은 기본 거부, ACTIVE 회원만 통과 (E-01·E-02·E-03)
class PostAccessIntegrationTest extends BoardIntegrationTest {

	private static final String NO_ID = "000000000000000000000000";
	private static final String NEW_POST_INPUT = "{ type: DEVLOG, title: \"권한 확인\", sections: { did: \"한 일\", learned: \"배운 점\" } }";
	private static final String UPDATE_POST_INPUT = "{ title: \"권한 확인\", sections: { did: \"한 일\", learned: \"배운 점\" } }";

	// 이름, 쿼리, ACTIVE 회원이 불렀을 때 기대하는 결과(오류 코드, 없으면 성공)
	private record Operation(String name, String query, String memberResult) {

		@Override
		public String toString() {
			return name;
		}

	}

	private static Stream<Operation> operations() {
		return Stream.of(
				new Operation("posts", "query { posts(type: DEVLOG, tag: \"zz-none-zz\") { nextCursor totalCount items { id } } }", null),
				new Operation("post", "query { post(id: \"" + NO_ID + "\") { id } }", "NOT_FOUND"),
				new Operation("popularTags", "query { popularTags { tag count } }", null),
				new Operation("createPost", "mutation { createPost(input: " + NEW_POST_INPUT + ") { id } }", null),
				new Operation("updatePost", "mutation { updatePost(id: \"" + NO_ID + "\", input: " + UPDATE_POST_INPUT + ") { id } }",
						"NOT_FOUND"),
				new Operation("deletePost", "mutation { deletePost(id: \"" + NO_ID + "\") }", "NOT_FOUND"),
				new Operation("createComment", "mutation { createComment(postId: \"" + NO_ID + "\", content: \"댓글\") { id } }",
						"NOT_FOUND"),
				new Operation("updateComment", "mutation { updateComment(id: \"" + NO_ID + "\", content: \"댓글\") { id } }",
						"NOT_FOUND"),
				new Operation("deleteComment", "mutation { deleteComment(id: \"" + NO_ID + "\") }", "NOT_FOUND"));
	}

	private static Stream<Arguments> deniedByStatus() {
		return operations().flatMap(operation -> Stream.of(
				Arguments.of(operation, "ANONYMOUS", ErrorCode.UNAUTHENTICATED),
				Arguments.of(operation, "PENDING", ErrorCode.ACCOUNT_PENDING),
				Arguments.of(operation, "REJECTED", ErrorCode.ACCOUNT_REJECTED)));
	}

	private long pending;
	private long rejected;
	private long active;
	private long admin;

	@BeforeEach
	void setUp() {
		pending = pendingMember();
		rejected = rejectedMember();
		active = activeMember();
		admin = adminMember();
	}

	private String tokenFor(String kind) {
		return switch (kind) {
			case "ANONYMOUS" -> null;
			case "PENDING" -> bearerFor(pending);
			case "REJECTED" -> bearerFor(rejected);
			default -> throw new IllegalArgumentException(kind);
		};
	}

	@ParameterizedTest(name = "{0} - {1}")
	@MethodSource("deniedByStatus")
	void 비로그인_승인_대기_거절_계정은_모든_게시판_루트_필드가_거부된다(Operation operation, String kind, ErrorCode expected) {
		GraphQlResponse response = graphQl.post(tokenFor(kind), operation.query());

		assertThat(response.status()).isEqualTo(200);
		assertThat(response.errorCode()).as(response.rawBody()).isEqualTo(expected.name());
		assertThat(response.errorMessage()).isEqualTo(expected.message());
		assertThat(response.dataIsNull()).isTrue();
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("operations")
	void ACTIVE_회원은_모든_게시판_루트_필드의_리졸버까지_도달한다(Operation operation) {
		GraphQlResponse response = graphQl.post(bearerFor(active), operation.query());

		if (operation.memberResult() == null) {
			assertThat(response.hasErrors()).as(response.rawBody()).isFalse();
		} else {
			// 없는 글·댓글이라 NOT_FOUND가 나온다. 접근 거부 오류가 아니다
			assertThat(response.errorCode()).as(response.rawBody()).isEqualTo(operation.memberResult());
		}
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("operations")
	void 관리자도_ACTIVE_회원이라_게시판_루트_필드에_도달한다(Operation operation) {
		GraphQlResponse response = graphQl.post(bearerFor(admin), operation.query());

		assertThat(response.errorCode()).as(response.rawBody()).isNotIn("UNAUTHENTICATED", "ACCOUNT_PENDING",
				"ACCOUNT_REJECTED", "FORBIDDEN");
	}

	@Test
	void 거부된_쓰기는_글_색인_댓글을_만들지_않는다() {
		graphQl.post(bearerFor(pending), "mutation { createPost(input: " + NEW_POST_INPUT + ") { id } }");
		graphQl.post(bearerFor(rejected), "mutation { createPost(input: " + NEW_POST_INPUT + ") { id } }");
		graphQl.post(null, "mutation { createPost(input: " + NEW_POST_INPUT + ") { id } }");

		assertThat(mongoPostCount(pending)).isZero();
		assertThat(mongoPostCount(rejected)).isZero();
		assertThat(indexCount(pending)).isZero();
		assertThat(indexCount(rejected)).isZero();
	}

	@Test
	void 승인_대기_계정은_남의_글을_수정_삭제_댓글_모두_할_수_없다() {
		String postId = newPost(active, recordPost("남의 글"));

		GraphQlResponse update = graphQl.post(bearerFor(pending),
				"mutation { updatePost(id: \"" + postId + "\", input: " + UPDATE_POST_INPUT + ") { id } }");
		GraphQlResponse delete = graphQl.post(bearerFor(pending), "mutation { deletePost(id: \"" + postId + "\") }");
		GraphQlResponse comment = graphQl.post(bearerFor(pending),
				"mutation { createComment(postId: \"" + postId + "\", content: \"댓글\") { id } }");

		assertThat(update.errorCode()).isEqualTo("ACCOUNT_PENDING");
		assertThat(delete.errorCode()).isEqualTo("ACCOUNT_PENDING");
		assertThat(comment.errorCode()).isEqualTo("ACCOUNT_PENDING");
		assertThat(mongoPost(postId).get("deletedAt")).isNull();
		assertThat(mongoPost(postId).getString("title")).isEqualTo("남의 글");
	}

	@Test
	void 요청_시점의_계정_상태를_따라서_같은_토큰도_상태가_바뀌면_거부된다() {
		String token = bearerFor(active);
		String query = "query { posts(type: DEVLOG, tag: \"zz-none-zz\") { items { id } } }";
		assertThat(graphQl.post(token, query).hasErrors()).isFalse();

		members.setStatus(active, MemberStatus.REJECTED);

		GraphQlResponse response = graphQl.post(token, query);
		assertThat(response.errorCode()).isEqualTo("ACCOUNT_REJECTED");

		members.setStatus(active, MemberStatus.PENDING);
		assertThat(graphQl.post(token, query).errorCode()).isEqualTo("ACCOUNT_PENDING");
	}

	@Test
	void 응답에는_내부_정보가_없다() {
		GraphQlResponse response = graphQl.post(null, "query { post(id: \"" + NO_ID + "\") { id } }");

		assertThat(response.rawBody()).doesNotContain("Exception").doesNotContain("at com.jandilog")
				.doesNotContain("mongodb").doesNotContain("jdbc");
		assertThat(response.status()).isEqualTo(200);
	}

}
