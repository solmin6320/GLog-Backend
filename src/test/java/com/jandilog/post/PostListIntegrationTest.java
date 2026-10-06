package com.jandilog.post;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.databind.JsonNode;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.post.domain.PostType;
import com.jandilog.post.repository.PostRepository;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;
import com.jandilog.testsupport.board.BoardIntegrationTest;

// 게시판 목록: 최신순, 커서 기반 20개(Q-06), 태그 필터, 인기 태그, 팀·작성자 필터 없음(Q-12)
class PostListIntegrationTest extends BoardIntegrationTest {

	private static final Instant BASE = Instant.parse("2026-10-01T00:00:00Z");
	private static final String TS = "TROUBLESHOOTING";
	private static final String DEV = "DEVLOG";

	@Autowired
	private PostRepository postRepository;

	private long writer;
	private long reader;
	// 이 테스트의 글만 가려내는 표식 태그
	private String mark;
	private int seconds;

	@BeforeEach
	void setUp() {
		writer = activeMember();
		reader = activeMember();
		mark = "l" + members.tag();
		seconds = 0;
	}

	// 글마다 저장 시각을 1초씩 늘려 만든 순서가 곧 최신순의 반대가 되게 한다. 만든 순서대로 id를 돌려준다
	private List<String> createPosts(int count, String type, String... tags) {
		List<String> ids = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			clock.fixAt(BASE.plusSeconds(seconds++));
			Map<String, Object> input = DEV.equals(type) ? devlog("일지 " + i, "한 일", "배운 점")
					: recordPost("글 " + i);
			List<String> allTags = new ArrayList<>(List.of(tags));
			input.put("tags", allTags);
			ids.add(newPost(writer, input));
		}
		return ids;
	}

	private static String cursorOf(String postId) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(postId.getBytes(StandardCharsets.UTF_8));
	}

	private static List<String> newestFirst(List<String> createdOrder) {
		List<String> reversed = new ArrayList<>(createdOrder);
		Collections.reverse(reversed);
		return reversed;
	}

	// ----- 페이지 -----

	@ParameterizedTest
	@ValueSource(ints = {0, 1, 20, 21, 40, 41})
	void 한_페이지는_20개이고_중복과_누락_없이_최신순으로_끝까지_읽힌다(int count) {
		List<String> created = createPosts(count, TS, mark);

		List<PostPage> pages = allPostPages(reader, TS, mark, null);

		int expectedPages = Math.max(1, (count + 19) / 20);
		assertThat(pages).hasSize(expectedPages);
		List<String> all = new ArrayList<>();
		for (int i = 0; i < pages.size(); i++) {
			PostPage page = pages.get(i);
			boolean last = i == pages.size() - 1;
			assertThat(page.items()).hasSize(last ? count - 20 * (expectedPages - 1) : 20);
			// 정확히 20의 배수여도 마지막 페이지는 커서가 없다 (E-57)
			if (last) {
				assertThat(page.nextCursor()).isNull();
			} else {
				assertThat(page.nextCursor()).isNotBlank();
			}
			assertThat(page.totalCount()).isEqualTo(count);
			all.addAll(page.ids());
		}
		assertThat(all).containsExactlyElementsOf(newestFirst(created));
		assertThat(all).doesNotHaveDuplicates();
	}

	@Test
	void 커서를_넘기면_그_글_다음부터_읽는다() {
		List<String> created = createPosts(5, TS, mark);

		PostPage page = postsPage(reader, TS, mark, null, cursorOf(created.get(3)));

		assertThat(page.ids()).containsExactly(created.get(2), created.get(1), created.get(0));
		assertThat(page.nextCursor()).isNull();
		assertThat(page.totalCount()).isEqualTo(5);
	}

	@Test
	void 가장_오래된_글_이후_커서는_빈_목록이고_전체_건수는_그대로다() {
		List<String> created = createPosts(3, TS, mark);

		PostPage page = postsPage(reader, TS, mark, null, cursorOf(created.get(0)));

		assertThat(page.items()).isEmpty();
		assertThat(page.nextCursor()).isNull();
		assertThat(page.totalCount()).isEqualTo(3);
	}

	@Test
	void 커서가_비어_있으면_처음부터_읽는다() {
		List<String> created = createPosts(3, TS, mark);

		assertThat(postsPage(reader, TS, mark, null, "").ids()).containsExactlyElementsOf(newestFirst(created));
	}

	@ParameterizedTest
	@ValueSource(strings = {"!!!", "abc", "한글", "====", "a+b/", "not a cursor", "MTIz"})
	void 잘못된_커서는_INVALID_INPUT이다(String cursor) {
		GraphQlResponse response = gql(bearerFor(reader), LIST_POSTS, vars("type", TS, "tag", mark, "q", null, "after", cursor));

		assertError(response, ErrorCode.INVALID_INPUT.name());
		assertThat(response.errorMessage()).isEqualTo(ErrorCode.INVALID_INPUT.message());
	}

	@Test
	void 첫_페이지를_읽는_사이_새_글이_올라와도_다음_페이지에_중복이_없다() {
		List<String> created = createPosts(25, TS, mark);
		PostPage first = postsPage(reader, TS, mark, null, null);

		createPosts(1, TS, mark);
		PostPage second = postsPage(reader, TS, mark, null, first.nextCursor());

		assertThat(first.ids()).hasSize(20);
		assertThat(second.ids()).containsExactlyElementsOf(newestFirst(created).subList(20, 25));
		assertThat(second.nextCursor()).isNull();
	}

	@Test
	void 첫_페이지의_글이_삭제돼도_다음_페이지에서_빠지는_글이_없다() {
		List<String> created = createPosts(25, TS, mark);
		PostPage first = postsPage(reader, TS, mark, null, null);
		for (int i = 0; i < 10; i++) {
			ok(gql(bearerFor(writer), DELETE_POST, vars("id", first.ids().get(i))));
		}

		PostPage second = postsPage(reader, TS, mark, null, first.nextCursor());

		// 커서 기준이라 21~25번째가 그대로 나온다 (오프셋 방식이면 건너뛰게 된다)
		assertThat(second.ids()).containsExactlyElementsOf(newestFirst(created).subList(20, 25));
		assertThat(second.totalCount()).isEqualTo(15);
	}

	// ----- 종류·태그 -----

	@Test
	void 글_종류별로_따로_모아_보여준다() {
		List<String> troubleshooting = createPosts(3, TS, mark);
		List<String> devlogs = createPosts(2, DEV, mark);

		PostPage ts = postsPage(reader, TS, mark, null, null);
		PostPage dev = postsPage(reader, DEV, mark, null, null);

		assertThat(ts.ids()).containsExactlyElementsOf(newestFirst(troubleshooting));
		assertThat(dev.ids()).containsExactlyElementsOf(newestFirst(devlogs));
		assertThat(ts.totalCount()).isEqualTo(3);
		assertThat(dev.totalCount()).isEqualTo(2);
		assertThat(ts.items()).allSatisfy(item -> assertThat(item.path("type").asText()).isEqualTo(TS));
		assertThat(dev.items()).allSatisfy(item -> assertThat(item.path("type").asText()).isEqualTo(DEV));
	}

	@Test
	void 태그_필터는_정확히_일치하는_태그만_모은다() {
		String exact = mark + "a";
		String longer = mark + "ab";
		List<String> withExact = createPosts(2, TS, exact);
		createPosts(1, TS, longer);
		List<String> withBoth = createPosts(1, TS, exact, longer);

		PostPage page = postsPage(reader, TS, exact, null, null);

		List<String> expected = new ArrayList<>(withExact);
		expected.addAll(withBoth);
		assertThat(page.ids()).containsExactlyElementsOf(newestFirst(expected));
		assertThat(page.totalCount()).isEqualTo(3);
	}

	@Test
	void 태그_필터는_대소문자와_앞뒤_공백을_가리지_않는다() {
		List<String> created = createPosts(2, TS, mark);

		assertThat(postsPage(reader, TS, mark.toUpperCase(), null, null).ids()).containsExactlyElementsOf(newestFirst(created));
		assertThat(postsPage(reader, TS, "  " + mark + "  ", null, null).ids()).containsExactlyElementsOf(newestFirst(created));
	}

	@Test
	void 존재하지_않는_태그는_빈_목록이다() {
		createPosts(2, TS, mark);

		PostPage page = postsPage(reader, TS, mark + "-none", null, null);

		assertThat(page.items()).isEmpty();
		assertThat(page.nextCursor()).isNull();
		assertThat(page.totalCount()).isZero();
	}

	@Test
	void 삭제된_글은_목록과_건수에서_빠진다() {
		List<String> created = createPosts(4, TS, mark);
		ok(gql(bearerFor(writer), DELETE_POST, vars("id", created.get(1))));

		PostPage page = postsPage(reader, TS, mark, null, null);

		assertThat(page.ids()).containsExactly(created.get(3), created.get(2), created.get(0));
		assertThat(page.totalCount()).isEqualTo(3);
	}

	@Test
	void 필수_항목이_빈_글도_목록에_나오고_기록글이_아님으로_표시된다() {
		clock.fixAt(BASE);
		String record = newPost(writer, recordPost("완전한 글", mark));
		Map<String, Object> incompleteInput = troubleshooting("비어 있는 글", "문제", "", "");
		incompleteInput.put("tags", List.of(mark));
		clock.fixAt(BASE.plusSeconds(1));
		String incomplete = newPost(writer, incompleteInput);

		PostPage page = postsPage(reader, TS, mark, null, null);

		assertThat(page.ids()).containsExactly(incomplete, record);
		assertThat(page.items().get(0).path("isRecord").asBoolean()).isFalse();
		assertThat(page.items().get(1).path("isRecord").asBoolean()).isTrue();
	}

	@Test
	void 목록_항목에는_작성자_종류_제목_태그_작성일_댓글_수가_담긴다() {
		clock.fixAt(Instant.parse("2026-10-04T14:59:59Z"));
		String id = newPost(writer, recordPost("목록 확인", mark, "extra"));

		JsonNode item = postsPage(reader, TS, mark, null, null).items().get(0);

		assertThat(item.path("id").asText()).isEqualTo(id);
		assertThat(item.path("authorId").asText()).isEqualTo(Long.toString(writer));
		assertThat(item.path("type").asText()).isEqualTo(TS);
		assertThat(item.path("title").asText()).isEqualTo("목록 확인");
		assertThat(item.path("tags")).extracting(JsonNode::asText).containsExactly(mark, "extra");
		assertThat(item.path("writtenDate").asText()).isEqualTo("2026-10-04");
		assertThat(item.path("commentCount").asInt()).isZero();
	}

	@Test
	void 목록과_상세의_작성자_정보는_글마다_맞는_회원이다() {
		long first = activeMemberWith("가나다" + members.tag(), "https://example.com/a.png");
		long second = activeMemberWith("라마바" + members.tag(), null);
		clock.fixAt(BASE);
		String firstPost = newPost(first, recordPost("첫째 글", mark));
		clock.fixAt(BASE.plusSeconds(1));
		String secondPost = newPost(second, recordPost("둘째 글", mark));
		String listQuery = "query($tag: String) { posts(type: TROUBLESHOOTING, tag: $tag) { items { id author { id nickname profileImageUrl } } } }";

		JsonNode items = ok(gql(bearerFor(reader), listQuery, vars("tag", mark))).data().path("posts").path("items");

		assertThat(items).extracting(item -> item.path("id").asText()).containsExactly(secondPost, firstPost);
		JsonNode secondAuthor = items.get(0).path("author");
		JsonNode firstAuthor = items.get(1).path("author");
		assertThat(secondAuthor.path("id").asText()).isEqualTo(Long.toString(second));
		assertThat(secondAuthor.path("nickname").asText()).isEqualTo("라마바" + members.tag());
		assertThat(secondAuthor.path("profileImageUrl").isNull()).isTrue();
		assertThat(firstAuthor.path("id").asText()).isEqualTo(Long.toString(first));
		assertThat(firstAuthor.path("nickname").asText()).isEqualTo("가나다" + members.tag());
		assertThat(firstAuthor.path("profileImageUrl").asText()).isEqualTo("https://example.com/a.png");
		JsonNode detail = ok(gql(bearerFor(reader), "query($id: ID!) { post(id: $id) { author { id nickname } } }",
				vars("id", firstPost))).data().path("post").path("author");
		assertThat(detail.path("id").asText()).isEqualTo(Long.toString(first));
		assertThat(detail.path("nickname").asText()).isEqualTo("가나다" + members.tag());
	}

	// ----- 팀·작성자 필터 없음 (Q-12) -----

	@Test
	void 목록에는_작성자_필터_인자가_없다() {
		GraphQlResponse response = gql(bearerFor(reader), "query { posts(type: TROUBLESHOOTING, authorId: 1) { items { id } } }", Map.of());

		assertThat(response.hasErrors()).isTrue();
		assertThat(response.errorClassification()).isEqualTo("ValidationError");
		assertThat(response.dataIsNull()).isTrue();
	}

	@Test
	void 목록에는_팀_필터_인자가_없다() {
		GraphQlResponse response = gql(bearerFor(reader), "query { posts(type: DEVLOG, teamId: 1) { items { id } } }", Map.of());

		assertThat(response.hasErrors()).isTrue();
		assertThat(response.errorClassification()).isEqualTo("ValidationError");
		assertThat(response.dataIsNull()).isTrue();
	}

	@Test
	void 글_종류는_필수_인자다() {
		GraphQlResponse response = gql(bearerFor(reader), "query { posts { items { id } } }", Map.of());

		assertThat(response.hasErrors()).isTrue();
		assertThat(response.errorClassification()).isEqualTo("ValidationError");
	}

	// ----- 인기 태그 -----

	// 이 테스트가 만든 태그만 골라 "태그=글 수" 순서로 돌려준다
	private List<String> myPopular(List<Document> rows) {
		List<String> mine = new ArrayList<>();
		for (Document row : rows) {
			String tag = row.getString("_id");
			if (tag.startsWith("p" + members.tag())) {
				mine.add(tag.substring(("p" + members.tag()).length()) + "=" + row.getInteger("count"));
			}
		}
		return mine;
	}

	private void createPopularFixture() {
		String p = "p" + members.tag();
		createPosts(1, TS, p + "a", p + "b", p + "c");
		createPosts(1, TS, p + "a", p + "b");
		createPosts(1, TS, p + "a");
		createPosts(1, DEV, p + "b", p + "e");
		// 삭제된 글의 태그는 세지 않는다
		List<String> deleted = createPosts(1, TS, p + "d");
		ok(gql(bearerFor(writer), DELETE_POST, vars("id", deleted.get(0))));
	}

	@Test
	void 인기_태그는_삭제된_글을_빼고_글_수가_많은_순서에_같으면_이름순이다() {
		createPopularFixture();

		List<String> all = myPopular(postRepository.popularTags(null, 10_000));
		List<String> troubleshooting = myPopular(postRepository.popularTags(PostType.TROUBLESHOOTING, 10_000));
		List<String> devlog = myPopular(postRepository.popularTags(PostType.DEVLOG, 10_000));

		assertThat(all).containsExactly("a=3", "b=3", "c=1", "e=1");
		assertThat(troubleshooting).containsExactly("a=3", "b=2", "c=1");
		assertThat(devlog).containsExactly("b=1", "e=1");
	}

	@Test
	void 인기_태그는_상위_10개까지만_글_수_내림차순_이름_오름차순으로_돌려준다() {
		createPopularFixture();
		String query = "query($type: PostType) { popularTags(type: $type) { tag count } }";

		for (String type : new String[] {null, TS, DEV}) {
			JsonNode tags = ok(gql(bearerFor(reader), query, vars("type", type))).data().path("popularTags");

			assertThat(tags.size()).isLessThanOrEqualTo(10);
			for (int i = 0; i < tags.size(); i++) {
				assertThat(tags.get(i).path("count").asInt()).isPositive();
				if (i > 0) {
					int previousCount = tags.get(i - 1).path("count").asInt();
					int count = tags.get(i).path("count").asInt();
					assertThat(previousCount).isGreaterThanOrEqualTo(count);
					if (previousCount == count) {
						assertThat(tags.get(i - 1).path("tag").asText()).isLessThan(tags.get(i).path("tag").asText());
					}
				}
			}
		}
	}

}
