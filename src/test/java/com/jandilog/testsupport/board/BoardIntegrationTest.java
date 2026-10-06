package com.jandilog.testsupport.board;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import com.fasterxml.jackson.databind.JsonNode;
import com.jandilog.member.domain.MemberRole;
import com.jandilog.member.domain.MemberStatus;
import com.jandilog.testsupport.auth.AuthIntegrationTest;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;

// 게시판·공지 통합 테스트 공통: 회원 추적, 글·댓글 호출 도우미, 내가 만든 데이터만 정리.
// 공유 DB라서 TRUNCATE나 전체 삭제는 하지 않고 이 테스트가 만든 회원의 글·댓글·색인·공지만 지운다
public abstract class BoardIntegrationTest extends AuthIntegrationTest {

	public static final String POST_FIELDS = "id authorId teamId type title "
			+ "sections { problem cause solution did learned } tags commitUrls isRecord writtenDate createdAt updatedAt";

	public static final String CREATE_POST = "mutation($input: CreatePostInput!) { createPost(input: $input) { "
			+ POST_FIELDS + " } }";
	public static final String UPDATE_POST = "mutation($id: ID!, $input: UpdatePostInput!) { updatePost(id: $id, input: $input) { "
			+ POST_FIELDS + " } }";
	public static final String DELETE_POST = "mutation($id: ID!) { deletePost(id: $id) }";
	public static final String GET_POST = "query($id: ID!) { post(id: $id) { " + POST_FIELDS + " } }";

	public static final String LIST_POSTS = """
			query($type: PostType!, $tag: String, $q: String, $after: String) {
			  posts(type: $type, tag: $tag, q: $q, after: $after) {
			    nextCursor
			    totalCount
			    items { id authorId type title tags isRecord writtenDate commentCount }
			  }
			}
			""";

	public static final String COMMENT_FIELDS = "id postId authorId content createdAt updatedAt "
			+ "author { id nickname profileImageUrl }";
	public static final String CREATE_COMMENT = "mutation($postId: ID!, $content: String!) { createComment(postId: $postId, content: $content) { "
			+ COMMENT_FIELDS + " } }";
	public static final String UPDATE_COMMENT = "mutation($id: ID!, $content: String!) { updateComment(id: $id, content: $content) { "
			+ COMMENT_FIELDS + " } }";
	public static final String DELETE_COMMENT = "mutation($id: ID!) { deleteComment(id: $id) }";

	// post_index 한 행. 판정이 읽는 값만 담는다
	public record IndexRow(long authorId, Long teamId, String postType, boolean record, LocalDate writtenDate,
			LocalDateTime deletedAt) {
	}

	// 목록 한 페이지
	public record PostPage(List<JsonNode> items, String nextCursor, int totalCount) {

		public List<String> ids() {
			return items.stream().map(item -> item.path("id").asText()).toList();
		}

	}

	@Autowired
	protected MongoTemplate mongo;

	private final List<Long> boardMemberIds = new ArrayList<>();

	// ----- 회원 -----

	protected long activeMember() {
		return track(members.active());
	}

	protected long adminMember() {
		return track(members.admin());
	}

	protected long pendingMember() {
		return track(members.pending());
	}

	protected long rejectedMember() {
		return track(members.rejected());
	}

	// 닉네임과 프로필 이미지를 정해서 만든 ACTIVE 회원
	protected long activeMemberWith(String nickname, String profileImageUrl) {
		long id = track(members.insert(MemberStatus.ACTIVE, MemberRole.MEMBER, members.uniqueLogin(), nickname));
		if (profileImageUrl != null) {
			jdbc.update("update member set profile_image_url = ? where id = ?", profileImageUrl, id);
		}
		return id;
	}

	private long track(long memberId) {
		boardMemberIds.add(memberId);
		return memberId;
	}

	// ----- 정리: 부모 클래스의 회원 삭제보다 먼저 돈다 -----

	@AfterEach
	void cleanBoardData() {
		if (boardMemberIds.isEmpty()) {
			return;
		}
		Query byAuthor = Query.query(Criteria.where("authorId").in(boardMemberIds));
		List<ObjectId> postIds = mongo.findDistinct(byAuthor, "_id", "posts", ObjectId.class);
		if (!postIds.isEmpty()) {
			mongo.remove(Query.query(Criteria.where("postId").in(postIds)), "comments");
		}
		mongo.remove(byAuthor, "comments");
		mongo.remove(byAuthor, "posts");
		for (Long id : boardMemberIds) {
			jdbc.update("delete from post_index where author_id = ?", id);
			jdbc.update("delete from notice where author_id = ?", id);
		}
		boardMemberIds.clear();
	}

	// ----- GraphQL 호출 -----

	// null 값도 담을 수 있게 HashMap으로 만든다. 홀수 번째가 이름, 짝수 번째가 값
	protected static Map<String, Object> vars(Object... namesAndValues) {
		Map<String, Object> variables = new HashMap<>();
		for (int i = 0; i < namesAndValues.length; i += 2) {
			variables.put((String) namesAndValues[i], namesAndValues[i + 1]);
		}
		return variables;
	}

	protected GraphQlResponse gql(String token, String query, Map<String, Object> variables) {
		return graphQl.post(token, query, variables);
	}

	// 오류 없이 끝났는지 확인하고 응답을 돌려준다
	protected static GraphQlResponse ok(GraphQlResponse response) {
		assertThat(response.status()).isEqualTo(200);
		assertThat(response.hasErrors()).as(response.rawBody()).isFalse();
		return response;
	}

	protected static void assertError(GraphQlResponse response, String code) {
		assertThat(response.status()).isEqualTo(200);
		assertThat(response.errorCode()).as(response.rawBody()).isEqualTo(code);
		assertThat(response.dataIsNull()).isTrue();
	}

	// ----- 글 입력 만들기 -----

	protected static Map<String, Object> troubleshooting(String title, String problem, String cause, String solution) {
		Map<String, Object> sections = new LinkedHashMap<>();
		sections.put("problem", problem);
		sections.put("cause", cause);
		sections.put("solution", solution);
		return postInput("TROUBLESHOOTING", title, sections);
	}

	protected static Map<String, Object> devlog(String title, String did, String learned) {
		Map<String, Object> sections = new LinkedHashMap<>();
		sections.put("did", did);
		sections.put("learned", learned);
		return postInput("DEVLOG", title, sections);
	}

	private static Map<String, Object> postInput(String type, String title, Map<String, Object> sections) {
		Map<String, Object> input = new LinkedHashMap<>();
		input.put("type", type);
		input.put("title", title);
		input.put("sections", sections);
		return input;
	}

	// 기록글로 인정되는 완전한 트러블슈팅 입력에 태그를 붙인다
	protected static Map<String, Object> recordPost(String title, String... tags) {
		Map<String, Object> input = troubleshooting(title, "문제", "원인", "해결");
		if (tags.length > 0) {
			input.put("tags", List.of(tags));
		}
		return input;
	}

	protected GraphQlResponse createPost(long memberId, Map<String, Object> input) {
		return gql(bearerFor(memberId), CREATE_POST, vars("input", input));
	}

	// 글을 만들고 id를 돌려준다
	protected String newPost(long memberId, Map<String, Object> input) {
		GraphQlResponse response = ok(createPost(memberId, input));
		return response.data().path("createPost").path("id").asText();
	}

	protected String newComment(long memberId, String postId, String content) {
		GraphQlResponse response = ok(gql(bearerFor(memberId), CREATE_COMMENT, vars("postId", postId, "content", content)));
		return response.data().path("createComment").path("id").asText();
	}

	// ----- 목록 읽기 -----

	protected PostPage postsPage(long memberId, String type, String tag, String q, String after) {
		GraphQlResponse response = ok(gql(bearerFor(memberId), LIST_POSTS, vars("type", type, "tag", tag, "q", q, "after", after)));
		JsonNode node = response.data().path("posts");
		List<JsonNode> items = new ArrayList<>();
		node.path("items").forEach(items::add);
		String next = node.path("nextCursor").isNull() ? null : node.path("nextCursor").asText();
		return new PostPage(items, next, node.path("totalCount").asInt());
	}

	// nextCursor가 null일 때까지 모든 페이지를 읽는다
	protected List<PostPage> allPostPages(long memberId, String type, String tag, String q) {
		List<PostPage> pages = new ArrayList<>();
		String cursor = null;
		do {
			PostPage page = postsPage(memberId, type, tag, q, cursor);
			pages.add(page);
			cursor = page.nextCursor();
		} while (cursor != null && pages.size() < 50);
		return pages;
	}

	// ----- DB 직접 확인 -----

	protected Document mongoPost(String id) {
		return mongo.findById(new ObjectId(id), Document.class, "posts");
	}

	protected Document mongoComment(String id) {
		return mongo.findById(new ObjectId(id), Document.class, "comments");
	}

	protected long mongoPostCount(long authorId) {
		return mongo.count(Query.query(Criteria.where("authorId").is(authorId)), "posts");
	}

	protected Optional<IndexRow> indexRow(String mongoPostId) {
		return jdbc.query("select * from post_index where mongo_post_id = ?", (rs, i) -> {
			long team = rs.getLong("team_id");
			Long teamId = rs.wasNull() ? null : team;
			return new IndexRow(rs.getLong("author_id"), teamId, rs.getString("post_type"), rs.getBoolean("is_record"),
					rs.getObject("written_date", LocalDate.class), rs.getObject("deleted_at", LocalDateTime.class));
		}, mongoPostId).stream().findFirst();
	}

	protected int indexCount(long authorId) {
		Integer count = jdbc.queryForObject("select count(*) from post_index where author_id = ?", Integer.class, authorId);
		return count == null ? 0 : count;
	}

}
