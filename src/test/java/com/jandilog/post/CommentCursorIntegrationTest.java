package com.jandilog.post;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.post.domain.Comment;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;
import com.jandilog.testsupport.board.BoardIntegrationTest;

// 글 상세 댓글 목록의 커서 페이지네이션 (기능명세서 3장 201행, 10장 502행). 오래된 순(작성 시각, id), 20개씩, {items, nextCursor}
class CommentCursorIntegrationTest extends BoardIntegrationTest {

	private static final Instant T0 = Instant.parse("2026-10-01T03:00:00Z");

	private static final String PAGE = """
			query($id: ID!, $after: String) {
			  post(id: $id) {
			    commentCount
			    comments(after: $after) { nextCursor items { id content createdAt author { id nickname } } }
			  }
			}
			""";

	private static final String LIST_WITH_COMMENTS = """
			query($type: PostType!, $tag: String) {
			  posts(type: $type, tag: $tag) {
			    items { id commentCount comments { nextCursor items { id author { id } } } }
			  }
			}
			""";

	// 한 페이지 응답
	private record CommentPage(List<String> ids, String nextCursor, int commentCount) {
	}

	private long postAuthor;
	private long commenter;
	private long other;
	private String postId;

	@BeforeEach
	void setUp() {
		postAuthor = activeMember();
		commenter = activeMember();
		other = activeMember();
		postId = newPost(postAuthor, recordPost("댓글이 많은 글"));
	}

	private CommentPage page(String id, String after) {
		JsonNode post = ok(gql(bearerFor(other), PAGE, vars("id", id, "after", after))).data().path("post");
		JsonNode comments = post.path("comments");
		List<String> ids = new ArrayList<>();
		comments.path("items").forEach(item -> ids.add(item.path("id").asText()));
		String next = comments.path("nextCursor").isNull() ? null : comments.path("nextCursor").asText();
		return new CommentPage(ids, next, post.path("commentCount").asInt());
	}

	// nextCursor가 null일 때까지 모든 페이지를 읽는다
	private List<CommentPage> allPages(String id) {
		List<CommentPage> pages = new ArrayList<>();
		String cursor = null;
		do {
			CommentPage page = page(id, cursor);
			pages.add(page);
			cursor = page.nextCursor();
		} while (cursor != null && pages.size() < 20);
		return pages;
	}

	private static List<String> flatten(List<CommentPage> pages) {
		return pages.stream().flatMap(page -> page.ids().stream()).toList();
	}

	// 댓글을 DB에 바로 넣는다. 작성 시각은 시작 시각부터 stepMillis씩 늘고, id는 만든 순서대로 커진다
	private List<String> seed(String targetPostId, int count, long stepMillis) {
		List<String> ids = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			ids.add(insert(targetPostId, T0.plusMillis(i * stepMillis)));
		}
		return ids;
	}

	private String insert(String targetPostId, Instant createdAt) {
		Comment comment = Comment.create(new ObjectId(Date.from(createdAt)), new ObjectId(targetPostId), commenter,
				"댓글", createdAt);
		mongo.insert(comment);
		return comment.getId().toHexString();
	}

	// ----- 경계 -----

	@Test
	void 댓글이_없으면_빈_목록이고_nextCursor가_null이다() {
		CommentPage page = page(postId, null);

		assertThat(page.ids()).isEmpty();
		assertThat(page.nextCursor()).isNull();
		assertThat(page.commentCount()).isZero();
	}

	@Test
	void 댓글이_정확히_20개면_한_페이지에_모두_나오고_nextCursor가_null이다() {
		List<String> expected = seed(postId, 20, 1000);

		CommentPage page = page(postId, null);

		assertThat(page.ids()).containsExactlyElementsOf(expected);
		assertThat(page.nextCursor()).isNull();
		assertThat(page.commentCount()).isEqualTo(20);
	}

	@Test
	void 댓글이_21개면_첫_페이지는_20개와_nextCursor이고_둘째_페이지는_1개와_null이다() {
		List<String> expected = seed(postId, 21, 1000);

		CommentPage first = page(postId, null);
		CommentPage second = page(postId, first.nextCursor());

		assertThat(first.ids()).containsExactlyElementsOf(expected.subList(0, 20));
		assertThat(first.nextCursor()).isNotBlank();
		assertThat(second.ids()).containsExactly(expected.get(20));
		assertThat(second.nextCursor()).isNull();
		// 댓글 수는 페이지와 상관없이 전체 수다
		assertThat(first.commentCount()).isEqualTo(21);
	}

	@Test
	void 마지막_페이지의_nextCursor는_null이고_페이지끼리_겹치거나_빠지지_않는다() {
		List<String> expected = seed(postId, 45, 1000);

		List<CommentPage> pages = allPages(postId);

		assertThat(pages).extracting(page -> page.ids().size()).containsExactly(20, 20, 5);
		assertThat(pages).extracting(CommentPage::nextCursor).containsExactly(pages.get(0).nextCursor(),
				pages.get(1).nextCursor(), null);
		List<String> all = flatten(pages);
		assertThat(all).doesNotHaveDuplicates();
		assertThat(all).containsExactlyElementsOf(expected);
	}

	@Test
	void 삭제된_댓글은_페이지를_채우는_개수에서_빠진다() {
		List<String> ids = seed(postId, 22, 1000);
		for (int i : new int[] { 0, 5, 21 }) {
			ok(gql(bearerFor(commenter), DELETE_COMMENT, vars("id", ids.get(i))));
		}

		CommentPage page = page(postId, null);

		// 22개 중 3개를 지워 19개가 남으므로 한 페이지이다
		assertThat(page.ids()).hasSize(19).doesNotContain(ids.get(0), ids.get(5), ids.get(21));
		assertThat(page.nextCursor()).isNull();
		assertThat(page.commentCount()).isEqualTo(19);
	}

	// ----- 정렬과 안정성 -----

	@Test
	void 작성_시각이_같은_댓글도_id_순으로_중복과_누락_없이_이어진다() {
		List<String> expected = new ArrayList<>();
		for (int i = 0; i < 25; i++) {
			expected.add(insert(postId, T0));
		}

		List<CommentPage> pages = allPages(postId);

		assertThat(pages).extracting(page -> page.ids().size()).containsExactly(20, 5);
		assertThat(flatten(pages)).containsExactlyElementsOf(expected);
	}

	@Test
	void 정렬은_작성_시각이_먼저이고_id는_시각이_같을_때만_쓴다() {
		// id는 만든 순서대로 커지지만 작성 시각은 거꾸로 준다
		List<ObjectId> ids = new ArrayList<>();
		for (int i = 0; i < 25; i++) {
			ids.add(new ObjectId(Date.from(T0)));
		}
		List<String> expected = new ArrayList<>();
		for (int i = 0; i < 25; i++) {
			Instant createdAt = T0.plusSeconds(24 - i);
			mongo.insert(Comment.create(ids.get(i), new ObjectId(postId), commenter, "댓글", createdAt));
			expected.add(0, ids.get(i).toHexString());
		}

		List<CommentPage> pages = allPages(postId);

		assertThat(flatten(pages)).containsExactlyElementsOf(expected);
	}

	@Test
	void 두_페이지_사이에_커서_위치의_댓글을_지워도_다음_페이지는_이어진다() {
		List<String> ids = seed(postId, 30, 1000);
		CommentPage first = page(postId, null);
		ok(gql(bearerFor(commenter), DELETE_COMMENT, vars("id", ids.get(19))));

		CommentPage second = page(postId, first.nextCursor());

		assertThat(second.ids()).containsExactlyElementsOf(ids.subList(20, 30));
		assertThat(second.nextCursor()).isNull();
	}

	@Test
	void 두_페이지_사이에_새_댓글이_달리면_마지막에_붙고_앞_페이지와_겹치지_않는다() {
		List<String> ids = seed(postId, 21, 1000);
		CommentPage first = page(postId, null);
		clock.fixAt(T0.plusSeconds(3600));
		String added = newComment(other, postId, "늦게 단 댓글");

		CommentPage second = page(postId, first.nextCursor());

		assertThat(second.ids()).containsExactly(ids.get(20), added);
		assertThat(second.nextCursor()).isNull();
		assertThat(second.ids()).doesNotContainAnyElementsOf(first.ids());
	}

	// ----- 입력과 범위 -----

	@Test
	void 잘못된_커서는_INVALID_INPUT이다() {
		seed(postId, 3, 1000);
		String garbage = Base64.getUrlEncoder().withoutPadding().encodeToString("abc|def".getBytes(StandardCharsets.UTF_8));
		// 글 목록 커서(ObjectId만 담긴 모양)도 댓글 커서가 아니다
		String postStyle = Base64.getUrlEncoder().withoutPadding()
				.encodeToString(new ObjectId().toHexString().getBytes(StandardCharsets.UTF_8));

		for (String cursor : List.of("not-a-cursor", "%%%", garbage, postStyle)) {
			GraphQlResponse response = gql(bearerFor(other), PAGE, vars("id", postId, "after", cursor));

			assertError(response, ErrorCode.INVALID_INPUT.name());
		}
	}

	@Test
	void 빈_커서는_첫_페이지로_본다() {
		List<String> expected = seed(postId, 3, 1000);

		assertThat(page(postId, "").ids()).containsExactlyElementsOf(expected);
	}

	@Test
	void 글_목록에서_댓글을_함께_요청해도_글마다_자기_댓글_한_페이지만_온다() {
		String tag = "p" + members.tag();
		clock.fixAt(T0);
		String many = newPost(postAuthor, recordPost("댓글 많은 글", tag));
		clock.fixAt(T0.plusSeconds(1));
		String few = newPost(postAuthor, recordPost("댓글 적은 글", tag));
		List<String> manyIds = seed(many, 21, 1000);
		String fewFirst = newComment(commenter, few, "하나");
		String fewSecond = newComment(other, few, "둘");

		JsonNode items = ok(gql(bearerFor(other), LIST_WITH_COMMENTS, vars("type", "TROUBLESHOOTING", "tag", tag))).data()
				.path("posts").path("items");

		assertThat(items).hasSize(2);
		JsonNode fewNode = items.get(0);
		JsonNode manyNode = items.get(1);
		assertThat(fewNode.path("id").asText()).isEqualTo(few);
		assertThat(fewNode.path("commentCount").asInt()).isEqualTo(2);
		assertThat(fewNode.path("comments").path("items")).extracting(c -> c.path("id").asText())
				.containsExactly(fewFirst, fewSecond);
		assertThat(fewNode.path("comments").path("items")).extracting(c -> c.path("author").path("id").asText())
				.containsExactly(Long.toString(commenter), Long.toString(other));
		assertThat(fewNode.path("comments").path("nextCursor").isNull()).isTrue();
		assertThat(manyNode.path("id").asText()).isEqualTo(many);
		assertThat(manyNode.path("commentCount").asInt()).isEqualTo(21);
		assertThat(manyNode.path("comments").path("items")).extracting(c -> c.path("id").asText())
				.containsExactlyElementsOf(manyIds.subList(0, 20));
		assertThat(manyNode.path("comments").path("nextCursor").isNull()).isFalse();
	}

	@Test
	void 페이지마다_작성자_정보가_맞게_붙는다() {
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < 22; i++) {
			clock.fixAt(T0.plusSeconds(i));
			long who = i % 2 == 0 ? commenter : other;
			String id = newComment(who, postId, "댓글 " + i);
			seen.add(id + ":" + who);
		}

		Set<String> actual = new HashSet<>();
		String cursor = null;
		do {
			JsonNode comments = ok(gql(bearerFor(postAuthor), PAGE, vars("id", postId, "after", cursor))).data().path("post")
					.path("comments");
			comments.path("items").forEach(item -> actual
					.add(item.path("id").asText() + ":" + item.path("author").path("id").asText()));
			cursor = comments.path("nextCursor").isNull() ? null : comments.path("nextCursor").asText();
		} while (cursor != null);

		assertThat(actual).isEqualTo(seen);
	}

}
