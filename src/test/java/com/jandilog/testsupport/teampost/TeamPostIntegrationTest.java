package com.jandilog.testsupport.teampost;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import com.fasterxml.jackson.databind.JsonNode;
import com.jandilog.team.dto.CreateTeamInput;
import com.jandilog.team.service.TeamJoinService;
import com.jandilog.team.service.TeamService;
import com.jandilog.testsupport.auth.AuthIntegrationTest;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;

// 팀장의 팀 글·댓글 관리 통합 테스트 공통: 회원·팀 준비, 글·댓글 호출 도우미, 내가 만든 데이터만 정리.
// 공유 DB라서 전체 삭제는 하지 않고 이 테스트가 만든 회원의 글·댓글·색인·팀만 지운다
public abstract class TeamPostIntegrationTest extends AuthIntegrationTest {

	public static final String POST_FIELDS = "id authorId teamId type title "
			+ "sections { problem cause solution did learned } isRecord writtenDate createdAt updatedAt";

	public static final String CREATE_POST = "mutation($input: CreatePostInput!) { createPost(input: $input) { "
			+ POST_FIELDS + " } }";
	public static final String UPDATE_POST = "mutation($id: ID!, $input: UpdatePostInput!) { updatePost(id: $id, input: $input) { "
			+ POST_FIELDS + " } }";
	public static final String DELETE_POST = "mutation($id: ID!) { deletePost(id: $id) }";

	public static final String COMMENT_FIELDS = "id postId authorId content createdAt updatedAt";
	public static final String CREATE_COMMENT = "mutation($postId: ID!, $content: String!) { createComment(postId: $postId, content: $content) { "
			+ COMMENT_FIELDS + " } }";
	public static final String UPDATE_COMMENT = "mutation($id: ID!, $content: String!) { updateComment(id: $id, content: $content) { "
			+ COMMENT_FIELDS + " } }";
	public static final String DELETE_COMMENT = "mutation($id: ID!) { deleteComment(id: $id) }";

	public static final String TEAM_POSTS = "query($teamId: ID!, $after: String) { teamPosts(teamId: $teamId, after: $after) { "
			+ "nextCursor items { " + POST_FIELDS + " commentCount } } }";
	public static final String TEAM_COMMENTS = "query($teamId: ID!, $after: String) { teamComments(teamId: $teamId, after: $after) { "
			+ "nextCursor items { " + COMMENT_FIELDS + " author { id nickname } } } }";

	// post_index 한 행. 판정이 읽는 값만 담는다
	public record IndexRow(long authorId, Long teamId, boolean record, LocalDate writtenDate, LocalDateTime deletedAt) {
	}

	@Autowired
	protected MongoTemplate mongo;
	@Autowired
	private TeamService teamService;
	@Autowired
	private TeamJoinService teamJoinService;

	private final List<Long> memberIds = new ArrayList<>();
	private final List<Long> teamIds = new ArrayList<>();
	private final AtomicInteger teamSequence = new AtomicInteger();

	// ----- 회원·팀 -----

	protected long activeMember() {
		return track(members.active());
	}

	protected long adminMember() {
		return track(members.admin());
	}

	private long track(long memberId) {
		memberIds.add(memberId);
		return memberId;
	}

	// 공개 팀을 만든다. 만든 사람이 팀장이자 첫 팀원이다
	protected long createTeam(long leaderId) {
		String name = "팀글관리-" + teamSequence.incrementAndGet() + "-" + Long.toString(leaderId, 36);
		long id = teamService.create(leaderId, new CreateTeamInput(name, null, true)).team().id();
		teamIds.add(id);
		return id;
	}

	// 초대코드로 참가한다
	protected void joinTeam(long teamId, long memberId) {
		String code = jdbc.queryForObject("select invite_code from team where id = ?", String.class, teamId);
		teamJoinService.joinByCode(memberId, code);
	}

	// ----- 정리: 부모 클래스의 회원 삭제보다 먼저 돈다 -----

	@AfterEach
	void cleanTeamPostData() {
		if (memberIds.isEmpty()) {
			return;
		}
		Query byAuthor = Query.query(Criteria.where("authorId").in(memberIds));
		List<ObjectId> postIds = mongo.findDistinct(byAuthor, "_id", "posts", ObjectId.class);
		if (!postIds.isEmpty()) {
			mongo.remove(Query.query(Criteria.where("postId").in(postIds)), "comments");
		}
		mongo.remove(byAuthor, "comments");
		mongo.remove(byAuthor, "posts");
		for (Long id : memberIds) {
			jdbc.update("delete from post_index where author_id = ?", id);
		}
		// 팀장·팀원 행이 회원을 참조하므로 회원 삭제 전에 팀을 지운다
		for (Long id : teamIds) {
			jdbc.update("delete from team_invitation where team_id = ?", id);
			jdbc.update("delete from team_member where team_id = ?", id);
			jdbc.update("delete from team where id = ?", id);
		}
		teamIds.clear();
		memberIds.clear();
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

	protected GraphQlResponse gql(long memberId, String query, Map<String, Object> variables) {
		return graphQl.post(bearerFor(memberId), query, variables);
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

	// ----- 글·댓글 만들기 -----

	// 트러블슈팅 입력. teamId가 null이면 팀 연결 없음
	protected static Map<String, Object> troubleshooting(String title, String problem, String cause, String solution,
			Long teamId) {
		Map<String, Object> sections = new LinkedHashMap<>();
		sections.put("problem", problem);
		sections.put("cause", cause);
		sections.put("solution", solution);
		Map<String, Object> input = new LinkedHashMap<>();
		input.put("type", "TROUBLESHOOTING");
		input.put("title", title);
		input.put("sections", sections);
		if (teamId != null) {
			input.put("teamId", Long.toString(teamId));
		}
		return input;
	}

	// 고칠 때 보내는 입력. 글 종류는 입력에 없다
	protected static Map<String, Object> troubleshootingEdit(String title, String problem, String cause, String solution,
			Long teamId) {
		Map<String, Object> input = troubleshooting(title, problem, cause, solution, teamId);
		input.remove("type");
		return input;
	}

	// 글을 만들고 id를 돌려준다
	protected String newPost(long memberId, Map<String, Object> input) {
		return ok(gql(memberId, CREATE_POST, vars("input", input))).data().path("createPost").path("id").asText();
	}

	protected String newComment(long memberId, String postId, String content) {
		return ok(gql(memberId, CREATE_COMMENT, vars("postId", postId, "content", content))).data()
				.path("createComment").path("id").asText();
	}

	// ----- DB 직접 확인 -----

	protected Document mongoPost(String id) {
		return mongo.findById(new ObjectId(id), Document.class, "posts");
	}

	protected Document mongoComment(String id) {
		return mongo.findById(new ObjectId(id), Document.class, "comments");
	}

	protected Optional<IndexRow> indexRow(String mongoPostId) {
		return jdbc.query("select * from post_index where mongo_post_id = ?", (rs, i) -> {
			long team = rs.getLong("team_id");
			Long teamId = rs.wasNull() ? null : team;
			return new IndexRow(rs.getLong("author_id"), teamId, rs.getBoolean("is_record"),
					rs.getObject("written_date", LocalDate.class), rs.getObject("deleted_at", LocalDateTime.class));
		}, mongoPostId).stream().findFirst();
	}

	protected static List<String> ids(JsonNode items) {
		List<String> ids = new ArrayList<>();
		items.forEach(item -> ids.add(item.path("id").asText()));
		return ids;
	}

}
