package com.jandilog.post;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import com.fasterxml.jackson.databind.JsonNode;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;
import com.jandilog.testsupport.board.BoardIntegrationTest;

// 댓글 작성·수정·삭제와 글 상세의 댓글 목록·수 (기능명세서 3장). 대댓글 없음, 댓글은 인증으로 인정되지 않는다
class CommentIntegrationTest extends BoardIntegrationTest {

	private static final Instant T0 = Instant.parse("2026-10-01T03:00:00Z");

	private static final String DETAIL = """
			query($id: ID!) {
			  post(id: $id) {
			    id
			    commentCount
			    comments { nextCursor items { id postId authorId content createdAt updatedAt author { id nickname profileImageUrl } } }
			  }
			}
			""";

	private long postAuthor;
	private long commenter;
	private long other;
	private String postId;

	@BeforeEach
	void setUp() {
		postAuthor = activeMember();
		commenter = activeMember();
		other = activeMember();
		postId = newPost(postAuthor, recordPost("댓글을 받을 글"));
	}

	private GraphQlResponse comment(long memberId, String targetPostId, String content) {
		return gql(bearerFor(memberId), CREATE_COMMENT, vars("postId", targetPostId, "content", content));
	}

	private GraphQlResponse edit(long memberId, String commentId, String content) {
		return gql(bearerFor(memberId), UPDATE_COMMENT, vars("id", commentId, "content", content));
	}

	private GraphQlResponse remove(long memberId, String commentId) {
		return gql(bearerFor(memberId), DELETE_COMMENT, vars("id", commentId));
	}

	private JsonNode detail(String id) {
		return ok(gql(bearerFor(other), DETAIL, vars("id", id))).data().path("post");
	}

	// ----- 작성 -----

	@Test
	void 글에_댓글을_쓰면_내용_작성자_시각이_저장되고_작성자_정보가_함께_온다() {
		clock.fixAt(T0);

		JsonNode created = ok(comment(commenter, postId, "  저도 같은 문제 겪었어요  ")).data().path("createComment");

		String id = created.path("id").asText();
		assertThat(id).matches("^[0-9a-f]{24}$");
		assertThat(created.path("postId").asText()).isEqualTo(postId);
		assertThat(created.path("authorId").asText()).isEqualTo(Long.toString(commenter));
		assertThat(created.path("content").asText()).isEqualTo("저도 같은 문제 겪었어요");
		assertThat(created.path("createdAt").asText()).isEqualTo("2026-10-01T12:00:00");
		assertThat(created.path("updatedAt").asText()).isEqualTo("2026-10-01T12:00:00");
		assertThat(created.path("author").path("id").asText()).isEqualTo(Long.toString(commenter));
		assertThat(created.path("author").path("nickname").asText()).isNotBlank();
		assertThat(created.path("author").path("profileImageUrl").isNull()).isTrue();
		Document doc = mongoComment(id);
		assertThat(doc.getObjectId("postId").toHexString()).isEqualTo(postId);
		assertThat(doc.getLong("authorId")).isEqualTo(commenter);
		assertThat(doc.getString("content")).isEqualTo("저도 같은 문제 겪었어요");
		assertThat(doc.get("deletedAt")).isNull();
	}

	@Test
	void 실제_시계로_쓴_댓글도_작성_응답의_시각이_다시_읽은_값과_같다() {
		clock.reset();
		JsonNode created = ok(comment(commenter, postId, "시각 확인")).data().path("createComment");

		JsonNode read = detail(postId).path("comments").path("items").get(0);

		assertThat(read.path("createdAt").asText()).isEqualTo(created.path("createdAt").asText());
		assertThat(read.path("updatedAt").asText()).isEqualTo(created.path("updatedAt").asText());
	}

	@Test
	void 글_작성자도_자기_글에_댓글을_쓸_수_있다() {
		JsonNode created = ok(comment(postAuthor, postId, "답글이에요")).data().path("createComment");

		assertThat(created.path("authorId").asText()).isEqualTo(Long.toString(postAuthor));
	}

	@Test
	void 댓글은_기록글로_인정되지_않아_post_index가_생기지_않는다() {
		int before = indexCount(postAuthor);

		ok(comment(commenter, postId, "댓글은 인증이 아니에요"));

		assertThat(indexCount(commenter)).isZero();
		assertThat(indexCount(postAuthor)).isEqualTo(before);
	}

	@Test
	void 댓글은_500자까지_허용하고_501자부터_INVALID_INPUT이다() {
		assertThat(ok(comment(commenter, postId, "가".repeat(500))).data().path("createComment").path("content").asText())
				.hasSize(500);

		GraphQlResponse response = comment(commenter, postId, "가".repeat(501));

		assertError(response, ErrorCode.INVALID_INPUT.name());
		assertThat(detail(postId).path("commentCount").asInt()).isEqualTo(1);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", " ", "   ", "\n", "\t \n"})
	void 비었거나_공백뿐인_댓글은_INVALID_INPUT이다(String content) {
		GraphQlResponse response = comment(commenter, postId, content);

		assertError(response, ErrorCode.INVALID_INPUT.name());
		assertThat(response.errorMessage()).isEqualTo("요청 내용을 확인해 주세요.");
		assertThat(detail(postId).path("commentCount").asInt()).isZero();
	}

	@Test
	void 댓글_내용을_보내지_않으면_스키마_검증에서_거부된다() {
		GraphQlResponse response = comment(commenter, postId, null);

		assertThat(response.hasErrors()).isTrue();
		assertThat(detail(postId).path("commentCount").asInt()).isZero();
	}

	@Test
	void 삭제된_글에는_댓글을_달_수_없다() {
		ok(gql(bearerFor(postAuthor), DELETE_POST, vars("id", postId)));

		GraphQlResponse response = comment(commenter, postId, "늦었어요");

		assertError(response, ErrorCode.POST_DELETED.name());
		assertThat(response.errorMessage()).isEqualTo("삭제된 글이에요.");
		assertThat(mongo.count(Query.query(Criteria.where("authorId").is(commenter)), "comments")).isZero();
	}

	@Test
	void 없는_글이나_잘못된_id에는_NOT_FOUND다() {
		assertError(comment(commenter, "0".repeat(24), "댓글"), ErrorCode.NOT_FOUND.name());
		assertError(comment(commenter, "not-an-id", "댓글"), ErrorCode.NOT_FOUND.name());
	}

	@Test
	void 대댓글은_없어서_부모_댓글_인자를_보내면_스키마_검증에서_거부된다() {
		String parent = newComment(commenter, postId, "부모 댓글");

		GraphQlResponse response = gql(bearerFor(other),
				"mutation($postId: ID!, $parentId: ID) { createComment(postId: $postId, content: \"답글\", parentId: $parentId) { id } }",
				vars("postId", postId, "parentId", parent));

		assertThat(response.hasErrors()).isTrue();
		assertThat(response.errorClassification()).isEqualTo("ValidationError");
		assertThat(detail(postId).path("commentCount").asInt()).isEqualTo(1);
	}

	// ----- 글 상세의 댓글 목록·수 -----

	@Test
	void 글_상세는_댓글을_오래된_순으로_보여주고_댓글_수를_센다() {
		clock.fixAt(T0);
		String first = newComment(commenter, postId, "첫 댓글");
		clock.fixAt(T0.plusSeconds(10));
		String second = newComment(other, postId, "둘째 댓글");
		clock.fixAt(T0.plusSeconds(20));
		String third = newComment(commenter, postId, "셋째 댓글");

		JsonNode post = detail(postId);

		assertThat(post.path("commentCount").asInt()).isEqualTo(3);
		assertThat(post.path("comments").path("items")).extracting(c -> c.path("id").asText()).containsExactly(first, second, third);
		assertThat(post.path("comments").path("items")).extracting(c -> c.path("content").asText())
				.containsExactly("첫 댓글", "둘째 댓글", "셋째 댓글");
	}

	@Test
	void 댓글마다_작성자_정보가_맞게_붙는다() {
		clock.fixAt(T0);
		newComment(commenter, postId, "A의 댓글");
		clock.fixAt(T0.plusSeconds(1));
		newComment(other, postId, "B의 댓글");

		JsonNode comments = detail(postId).path("comments").path("items");

		assertThat(comments.get(0).path("author").path("id").asText()).isEqualTo(Long.toString(commenter));
		assertThat(comments.get(1).path("author").path("id").asText()).isEqualTo(Long.toString(other));
		assertThat(comments.get(0).path("author").path("nickname").asText())
				.isNotEqualTo(comments.get(1).path("author").path("nickname").asText());
	}

	@Test
	void 댓글이_없는_글은_빈_목록과_0이다() {
		JsonNode post = detail(postId);

		assertThat(post.path("comments").path("items")).isEmpty();
		assertThat(post.path("commentCount").asInt()).isZero();
	}

	@Test
	void 삭제된_댓글은_목록과_댓글_수에서_빠진다() {
		clock.fixAt(T0);
		String keep = newComment(commenter, postId, "남길 댓글");
		clock.fixAt(T0.plusSeconds(1));
		String gone = newComment(commenter, postId, "지울 댓글");
		ok(remove(commenter, gone));

		JsonNode post = detail(postId);

		assertThat(post.path("commentCount").asInt()).isEqualTo(1);
		assertThat(post.path("comments").path("items")).extracting(c -> c.path("id").asText()).containsExactly(keep);
	}

	@Test
	void 글_목록의_댓글_수는_글마다_따로_센다() {
		String tag = "c" + members.tag();
		clock.fixAt(T0);
		String none = newPost(postAuthor, recordPost("댓글 없는 글", tag));
		clock.fixAt(T0.plusSeconds(1));
		String two = newPost(postAuthor, recordPost("댓글 둘인 글", tag));
		clock.fixAt(T0.plusSeconds(2));
		String one = newPost(postAuthor, recordPost("댓글 하나인 글", tag));
		newComment(commenter, two, "하나");
		newComment(other, two, "둘");
		newComment(commenter, one, "하나");
		// 삭제된 댓글은 세지 않는다
		String gone = newComment(commenter, one, "지울 댓글");
		ok(remove(commenter, gone));

		PostPage page = postsPage(other, "TROUBLESHOOTING", tag, null, null);

		assertThat(page.ids()).containsExactly(one, two, none);
		assertThat(page.items()).extracting(item -> item.path("commentCount").asInt()).containsExactly(1, 2, 0);
	}

	@Test
	void 삭제된_글의_상세는_댓글_영역까지_POST_DELETED로_막힌다() {
		newComment(commenter, postId, "댓글");
		ok(gql(bearerFor(postAuthor), DELETE_POST, vars("id", postId)));

		GraphQlResponse response = gql(bearerFor(other), DETAIL, vars("id", postId));

		assertError(response, ErrorCode.POST_DELETED.name());
		assertThat(response.rawBody()).doesNotContain("댓글");
	}

	// ----- 수정 -----

	@Test
	void 작성자가_수정하면_내용과_수정_시각만_바뀐다() {
		clock.fixAt(T0);
		String id = newComment(commenter, postId, "원래 댓글");
		clock.fixAt(T0.plusSeconds(3600));

		JsonNode updated = ok(edit(commenter, id, "  고친 댓글  ")).data().path("updateComment");

		assertThat(updated.path("id").asText()).isEqualTo(id);
		assertThat(updated.path("content").asText()).isEqualTo("고친 댓글");
		assertThat(updated.path("createdAt").asText()).isEqualTo("2026-10-01T12:00:00");
		assertThat(updated.path("updatedAt").asText()).isEqualTo("2026-10-01T13:00:00");
		assertThat(updated.path("authorId").asText()).isEqualTo(Long.toString(commenter));
		assertThat(updated.path("postId").asText()).isEqualTo(postId);
		assertThat(mongoComment(id).getString("content")).isEqualTo("고친 댓글");
		assertThat(detail(postId).path("comments").path("items").get(0).path("content").asText()).isEqualTo("고친 댓글");
	}

	@Test
	void 작성자가_아니면_수정할_수_없다() {
		String id = newComment(commenter, postId, "원래 댓글");

		assertError(edit(other, id, "바꿔치기"), ErrorCode.FORBIDDEN.name());
		// 글 작성자라도 남의 댓글은 고치지 못한다
		assertError(edit(postAuthor, id, "바꿔치기"), ErrorCode.FORBIDDEN.name());

		assertThat(mongoComment(id).getString("content")).isEqualTo("원래 댓글");
	}

	@Test
	void 수정할_내용이_비었거나_501자면_INVALID_INPUT이고_그대로다() {
		String id = newComment(commenter, postId, "원래 댓글");

		assertError(edit(commenter, id, "  "), ErrorCode.INVALID_INPUT.name());
		assertError(edit(commenter, id, "a".repeat(501)), ErrorCode.INVALID_INPUT.name());

		assertThat(mongoComment(id).getString("content")).isEqualTo("원래 댓글");
	}

	@Test
	void 삭제된_댓글이나_없는_댓글은_수정할_때_NOT_FOUND다() {
		String id = newComment(commenter, postId, "지울 댓글");
		ok(remove(commenter, id));

		assertError(edit(commenter, id, "되살리기"), ErrorCode.NOT_FOUND.name());
		assertError(edit(commenter, "0".repeat(24), "없음"), ErrorCode.NOT_FOUND.name());
		assertError(edit(commenter, "not-an-id", "없음"), ErrorCode.NOT_FOUND.name());
		assertThat(mongoComment(id).get("deletedAt")).isNotNull();
	}

	@Test
	void 글이_삭제된_뒤에는_댓글을_수정할_수_없다() {
		String id = newComment(commenter, postId, "원래 댓글");
		ok(gql(bearerFor(postAuthor), DELETE_POST, vars("id", postId)));

		assertError(edit(commenter, id, "늦은 수정"), ErrorCode.POST_DELETED.name());

		assertThat(mongoComment(id).getString("content")).isEqualTo("원래 댓글");
	}

	// ----- 삭제 -----

	@Test
	void 작성자가_삭제하면_삭제_표시만_남고_댓글_문서는_지워지지_않는다() {
		clock.fixAt(T0);
		String id = newComment(commenter, postId, "지울 댓글");
		clock.fixAt(T0.plusSeconds(60));

		GraphQlResponse response = ok(remove(commenter, id));

		assertThat(response.data().path("deleteComment").asBoolean()).isTrue();
		Document doc = mongoComment(id);
		assertThat(doc).isNotNull();
		assertThat(doc.getDate("deletedAt").toInstant()).isEqualTo(T0.plusSeconds(60));
		assertThat(doc.getString("content")).isEqualTo("지울 댓글");
	}

	@Test
	void 작성자가_아니면_삭제할_수_없다() {
		String id = newComment(commenter, postId, "내 댓글");

		assertError(remove(other, id), ErrorCode.FORBIDDEN.name());
		assertError(remove(postAuthor, id), ErrorCode.FORBIDDEN.name());

		assertThat(mongoComment(id).get("deletedAt")).isNull();
		assertThat(detail(postId).path("commentCount").asInt()).isEqualTo(1);
	}

	@Test
	void 이미_삭제한_댓글을_다시_삭제하면_NOT_FOUND다() {
		String id = newComment(commenter, postId, "한 번만");
		ok(remove(commenter, id));

		assertError(remove(commenter, id), ErrorCode.NOT_FOUND.name());
		assertError(remove(commenter, "0".repeat(24)), ErrorCode.NOT_FOUND.name());
		assertError(remove(commenter, "zzz"), ErrorCode.NOT_FOUND.name());
	}

	@Test
	void 글이_삭제된_뒤에는_댓글을_삭제할_수_없다() {
		String id = newComment(commenter, postId, "내 댓글");
		ok(gql(bearerFor(postAuthor), DELETE_POST, vars("id", postId)));

		assertError(remove(commenter, id), ErrorCode.POST_DELETED.name());

		assertThat(mongoComment(id).get("deletedAt")).isNull();
	}

	@Test
	void 댓글을_지워도_같은_글의_다른_댓글은_그대로다() {
		clock.fixAt(T0);
		String keep = newComment(other, postId, "남길 댓글");
		clock.fixAt(T0.plusSeconds(1));
		String gone = newComment(commenter, postId, "지울 댓글");

		ok(remove(commenter, gone));

		assertThat(detail(postId).path("comments").path("items")).extracting(c -> c.path("id").asText()).containsExactly(keep);
		assertThat(mongoComment(gone).get("deletedAt")).isNotNull();
	}

}
