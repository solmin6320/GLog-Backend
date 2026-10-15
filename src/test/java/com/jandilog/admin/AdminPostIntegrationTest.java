package com.jandilog.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bson.types.ObjectId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.databind.JsonNode;
import com.jandilog.post.domain.Comment;
import com.jandilog.post.domain.Post;
import com.jandilog.post.domain.PostSections;
import com.jandilog.post.domain.PostType;
import com.jandilog.post.dto.CreatePostInput;
import com.jandilog.post.dto.PostSectionsInput;
import com.jandilog.post.repository.CommentRepository;
import com.jandilog.post.repository.PostRepository;
import com.jandilog.post.service.CommentService;
import com.jandilog.post.service.PostService;
import com.jandilog.testsupport.admin.AdminIntegrationTest;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;

// 게시물 관리 탭: 글 · 댓글 목록(검색 · 종류 필터 · 커서)과 관리자 삭제 (AD-01 게시물 관리, 기능명세서 3·9장)
class AdminPostIntegrationTest extends AdminIntegrationTest {

	private static final Instant BASE = Instant.parse("2044-06-01T00:00:00Z");

	private static final String LIST = """
			query($filter: AdminPostFilter, $keyword: String, $after: String) {
			  adminPosts(filter: $filter, keyword: $keyword, after: $after) {
			    nextCursor
			    items { kind id postId title content author { id nickname githubLogin } createdAt }
			  }
			}
			""";

	private static final String DELETE_POST = "mutation($postId: ID!) { adminDeletePost(postId: $postId) }";
	private static final String DELETE_COMMENT = "mutation($commentId: ID!) { adminDeleteComment(commentId: $commentId) }";

	@Autowired
	private PostRepository postRepository;
	@Autowired
	private CommentRepository commentRepository;
	@Autowired
	private PostService postService;
	@Autowired
	private CommentService commentService;

	@Test
	@DisplayName("검색어로 제목 · 내용 · 작성자를 찾고 종류 필터가 글과 댓글을 가른다")
	void searchesTitleContentAndAuthorWithKindFilter() {
		String token = data.uniqueName("검색");
		long writer = activeMember();
		long other = activeMember();
		Post byTitle = insertPost(0, writer, "제목에 " + token + " 포함", "본문");
		insertPost(2, writer, "관계없는 글", "본문에도 " + token + " 있음");
		insertPost(4, other, "전혀 다른 글", "본문");
		Comment byContent = insertComment(5, byTitle, other, "댓글 내용에 " + token);
		insertComment(6, byTitle, other, "관계없는 댓글");

		List<JsonNode> all = items(Map.of("keyword", token));
		assertThat(all).hasSize(3);
		assertThat(all.stream().map(item -> item.path("kind").asText()).toList())
				.containsExactlyInAnyOrder("POST", "POST", "COMMENT");

		JsonNode comment = all.stream().filter(item -> item.path("kind").asText().equals("COMMENT")).findFirst().orElseThrow();
		assertThat(comment.path("id").asText()).isEqualTo(byContent.getId().toHexString());
		assertThat(comment.path("postId").asText()).isEqualTo(byTitle.getId().toHexString());
		// 댓글 행에는 글 제목과 댓글 내용이 함께 실린다
		assertThat(comment.path("title").asText()).isEqualTo(byTitle.getTitle());
		assertThat(comment.path("content").asText()).contains(token);
		assertThat(comment.path("author").path("id").asLong()).isEqualTo(other);

		assertThat(items(Map.of("keyword", token, "filter", "POSTS"))).hasSize(2)
				.allMatch(item -> item.path("kind").asText().equals("POST"));
		assertThat(items(Map.of("keyword", token, "filter", "COMMENTS"))).hasSize(1)
				.allMatch(item -> item.path("kind").asText().equals("COMMENT"));

		// 작성자는 닉네임 · GitHub 아이디로 찾는다 (글과 댓글 모두)
		String otherLogin = members.find(other).orElseThrow().githubLogin();
		List<JsonNode> byAuthor = items(Map.of("keyword", otherLogin));
		assertThat(byAuthor).hasSize(3);
		assertThat(byAuthor).allMatch(item -> item.path("author").path("id").asLong() == other);
	}

	@Test
	@DisplayName("검색어의 정규식 기호는 글자 그대로 찾는다")
	void regexCharactersAreLiteral() {
		long writer = activeMember();
		String token = data.uniqueName("기호");
		insertPost(0, writer, token + " a(b)[c].*", "본문");
		insertPost(2, writer, token + " abc", "본문");

		assertThat(items(Map.of("keyword", token + " a(b)[c]"))).hasSize(1);
		assertThat(items(Map.of("keyword", token + " a(b)[c].*"))).hasSize(1);
		// 점과 별표가 아무 글자나 뜻하지 않는다
		assertThat(items(Map.of("keyword", token + " a.*c"))).isEmpty();
		assertThat(items(Map.of("keyword", token.toUpperCase()))).hasSize(2);
	}

	@Test
	@DisplayName("글과 댓글은 최신순으로 한 줄에 섞이고 20개씩 커서로 나뉜다")
	void mixedListIsNewestFirstAndPaginated() {
		String token = data.uniqueName("페이지");
		long writer = activeMember();
		List<ObjectId> expected = new ArrayList<>();
		Post parent = null;
		for (int i = 0; i < 12; i++) {
			Post post = insertPost(i * 2, writer, token + " 글" + i, "본문");
			if (parent == null) {
				parent = post;
			}
			expected.add(post.getId());
			expected.add(insertComment(i * 2 + 1, parent, writer, token + " 댓글" + i).getId());
		}

		JsonNode first = page(Map.of("keyword", token));
		assertThat(first.path("items")).hasSize(20);
		assertThat(first.path("nextCursor").isNull()).isFalse();
		JsonNode second = page(Map.of("keyword", token, "after", first.path("nextCursor").asText()));
		assertThat(second.path("items")).hasSize(4);
		assertThat(second.path("nextCursor").isNull()).isTrue();

		List<String> seen = new ArrayList<>();
		first.path("items").forEach(item -> seen.add(item.path("id").asText()));
		second.path("items").forEach(item -> seen.add(item.path("id").asText()));
		List<String> newestFirst = new ArrayList<>(expected.stream().map(ObjectId::toHexString).toList());
		Collections.reverse(newestFirst);
		assertThat(seen).containsExactlyElementsOf(newestFirst);
	}

	@Test
	@DisplayName("글을 삭제하면 소프트 삭제되고 색인과 관리자 로그가 함께 남는다")
	void deletePostSoftDeletesIndexesAndLogs() {
		long writer = activeMember();
		String postId = postService.create(writer, new CreatePostInput(PostType.DEVLOG, data.uniqueName("삭제글"),
				new PostSectionsInput(null, null, null, "했다", "배웠다"), List.of(), List.of(), null)).id();

		assertThat(dataOf(asAdmin(DELETE_POST, Map.of("postId", postId))).path("adminDeletePost").asBoolean()).isTrue();

		assertThat(postRepository.findById(new ObjectId(postId)).orElseThrow().getDeletedAt()).isNotNull();
		assertThat(jdbc.queryForObject("select deleted_at is not null from post_index where mongo_post_id = ?",
				Boolean.class, postId)).isTrue();
		assertThat(data.countLogs("DELETE_POST", "POST", postId)).isEqualTo(1);
		assertThat(items(Map.of("keyword", members.find(writer).orElseThrow().githubLogin()))).isEmpty();

		assertThat(asAdmin(DELETE_POST, Map.of("postId", postId)).errorCode()).isEqualTo("POST_DELETED");
		assertThat(data.countLogs("DELETE_POST", "POST", postId)).isEqualTo(1);
		assertThat(asAdmin(DELETE_POST, Map.of("postId", new ObjectId().toHexString())).errorCode())
				.isEqualTo("NOT_FOUND");
		assertThat(asAdmin(DELETE_POST, Map.of("postId", "not-an-id")).errorCode()).isEqualTo("NOT_FOUND");
	}

	@Test
	@DisplayName("댓글을 삭제하면 소프트 삭제되고 관리자 로그가 남으며 두 번째는 없는 댓글로 본다")
	void deleteCommentSoftDeletesAndLogs() {
		long writer = activeMember();
		String postId = postService.create(writer, new CreatePostInput(PostType.DEVLOG, data.uniqueName("댓글글"),
				new PostSectionsInput(null, null, null, "했다", "배웠다"), List.of(), List.of(), null)).id();
		String commentId = commentService.create(writer, postId, "지울 댓글").id();

		assertThat(dataOf(asAdmin(DELETE_COMMENT, Map.of("commentId", commentId))).path("adminDeleteComment")
				.asBoolean()).isTrue();

		assertThat(commentRepository.findById(new ObjectId(commentId)).orElseThrow().getDeletedAt()).isNotNull();
		assertThat(data.countLogs("DELETE_COMMENT", "COMMENT", commentId)).isEqualTo(1);
		assertThat(asAdmin(DELETE_COMMENT, Map.of("commentId", commentId)).errorCode()).isEqualTo("NOT_FOUND");
		assertThat(data.countLogs("DELETE_COMMENT", "COMMENT", commentId)).isEqualTo(1);
	}

	@Test
	@DisplayName("관리자가 아니면 게시물 관리를 쓸 수 없고 잘못된 입력은 거부한다")
	void accessAndInputRules() {
		long member = activeMember();

		assertThat(asMember(member, LIST, Map.of()).errorCode()).isEqualTo("FORBIDDEN");
		assertThat(asMember(member, DELETE_POST, Map.of("postId", new ObjectId().toHexString())).errorCode())
				.isEqualTo("FORBIDDEN");
		assertThat(asMember(member, DELETE_COMMENT, Map.of("commentId", new ObjectId().toHexString())).errorCode())
				.isEqualTo("FORBIDDEN");
		assertThat(graphQl.post(null, LIST).errorCode()).isEqualTo("UNAUTHENTICATED");

		assertThat(asAdmin(LIST, Map.of("keyword", "가".repeat(51))).errorCode()).isEqualTo("INVALID_INPUT");
		assertThat(asAdmin(LIST, Map.of("after", "%%%")).errorCode()).isEqualTo("INVALID_INPUT");
		assertThat(asAdmin(LIST, Map.of("after", "bm90LWFuLW9iamVjdC1pZA")).errorCode()).isEqualTo("INVALID_INPUT");
	}

	// _id 시각을 초 단위로 달리해 글 · 댓글의 정렬이 정해지도록 직접 넣는다
	private Post insertPost(int second, long authorId, String title, String body) {
		Instant at = BASE.plusSeconds(second);
		Post post = Post.create(new ObjectId(Date.from(at)), authorId, null, PostType.DEVLOG, title,
				new PostSections(null, null, null, body, "배움"), List.of(), List.of(), LocalDate.of(2044, 6, 1), at);
		postRepository.insert(post);
		return post;
	}

	private Comment insertComment(int second, Post post, long authorId, String content) {
		Instant at = BASE.plusSeconds(second);
		Comment comment = Comment.create(new ObjectId(Date.from(at)), post.getId(), authorId, content, at);
		commentRepository.insert(comment);
		return comment;
	}

	private JsonNode page(Map<String, Object> variables) {
		return dataOf(asAdmin(LIST, variables)).path("adminPosts");
	}

	private List<JsonNode> items(Map<String, Object> variables) {
		List<JsonNode> items = new ArrayList<>();
		Map<String, Object> current = new HashMap<>(variables);
		while (true) {
			GraphQlResponse response = asAdmin(LIST, current);
			JsonNode page = dataOf(response).path("adminPosts");
			page.path("items").forEach(items::add);
			if (page.path("nextCursor").isNull()) {
				return items;
			}
			current.put("after", page.path("nextCursor").asText());
		}
	}

}
