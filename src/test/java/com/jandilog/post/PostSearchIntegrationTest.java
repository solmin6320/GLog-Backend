package com.jandilog.post;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.jandilog.common.exception.ErrorCode;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;
import com.jandilog.testsupport.board.BoardIntegrationTest;

// 게시판 검색: 제목·내용·태그 텍스트 인덱스, 2~50자(E-61·E-63), 종류·태그와 함께 걸기 (기능명세서 3장, DB명세서 3-1)
class PostSearchIntegrationTest extends BoardIntegrationTest {

	private static final Instant BASE = Instant.parse("2026-10-01T00:00:00Z");
	private static final String TS = "TROUBLESHOOTING";
	private static final String DEV = "DEVLOG";

	private long writer;
	private long reader;
	// 이 테스트의 글만 걸러내는 표식 태그와, 검색어로 쓰는 고유 단어
	private String mark;
	private String t;
	private int seconds;

	@BeforeEach
	void setUp() {
		writer = activeMember();
		reader = activeMember();
		t = members.tag();
		mark = "s" + t;
		seconds = 0;
	}

	private String word(String name) {
		return name + t;
	}

	// 표식 태그를 붙여 글을 만든다. 만든 순서대로 저장 시각이 1초씩 늘어난다
	private String post(Map<String, Object> input, String... extraTags) {
		clock.fixAt(BASE.plusSeconds(seconds++));
		List<String> tags = new ArrayList<>();
		tags.add(mark);
		tags.addAll(List.of(extraTags));
		input.put("tags", tags);
		return newPost(writer, input);
	}

	private List<String> search(String type, String q) {
		return postsPage(reader, type, mark, q, null).ids();
	}

	private GraphQlResponse searchResponse(String type, String q) {
		return gql(bearerFor(reader), LIST_POSTS, vars("type", type, "tag", mark, "q", q, "after", null));
	}

	// ----- 제목·내용·태그 -----

	@Test
	void 제목에_있는_단어로_찾는다() {
		String hit = post(recordPost(word("alpha") + " 장애 정리"));
		post(recordPost("전혀 다른 제목"));

		assertThat(search(TS, word("alpha"))).containsExactly(hit);
	}

	@Test
	void 트러블슈팅_문제_원인_해결_본문에서_찾는다() {
		String inProblem = post(troubleshooting("제목1", "문제에 " + word("pword") + " 있음", "원인", "해결"));
		String inCause = post(troubleshooting("제목2", "문제", "원인에 " + word("cword") + " 있음", "해결"));
		String inSolution = post(troubleshooting("제목3", "문제", "원인", "해결에 " + word("sword") + " 있음"));

		assertThat(search(TS, word("pword"))).containsExactly(inProblem);
		assertThat(search(TS, word("cword"))).containsExactly(inCause);
		assertThat(search(TS, word("sword"))).containsExactly(inSolution);
	}

	@Test
	void 개발일지_한_일_배운_점_본문에서_찾는다() {
		String inDid = post(devlog("일지1", "오늘 " + word("dword") + " 구현", "배운 점"));
		String inLearned = post(devlog("일지2", "한 일", "배운 점은 " + word("lword") + " 이다"));

		assertThat(search(DEV, word("dword"))).containsExactly(inDid);
		assertThat(search(DEV, word("lword"))).containsExactly(inLearned);
	}

	@Test
	void 태그로도_찾는다() {
		String hit = post(recordPost("태그로 찾을 글"), word("tagword"));
		post(recordPost("태그 없는 글"));

		assertThat(search(TS, word("tagword"))).containsExactly(hit);
	}

	@Test
	void 검색은_대소문자를_가리지_않는다() {
		String hit = post(recordPost(word("MixedCase") + " 제목"));

		assertThat(search(TS, word("mixedcase"))).containsExactly(hit);
		assertThat(search(TS, word("MIXEDCASE"))).containsExactly(hit);
	}

	@Test
	void 검색어_앞뒤_공백은_무시한다() {
		String hit = post(recordPost(word("trim") + " 제목"));

		assertThat(search(TS, "   " + word("trim") + "  ")).containsExactly(hit);
	}

	@Test
	void 검색어를_공백으로_나누면_모든_단어를_포함한_글만_나온다() {
		post(recordPost(word("one") + " 만 있는 글"));
		post(recordPost(word("two") + " 만 있는 글"));
		String both = post(troubleshooting("두 단어", word("one") + " 문제", "원인", word("two") + " 해결"));

		assertThat(search(TS, word("one") + " " + word("two"))).containsExactly(both);
		assertThat(search(TS, word("two") + "   " + word("one"))).containsExactly(both);
	}

	@Test
	void 검색은_글_종류_안에서만_한다() {
		String ts = post(recordPost(word("shared") + " 트러블슈팅"));
		String dev = post(devlog(word("shared") + " 일지", "한 일", "배운 점"));

		assertThat(search(TS, word("shared"))).containsExactly(ts);
		assertThat(search(DEV, word("shared"))).containsExactly(dev);
	}

	@Test
	void 검색어와_태그를_함께_걸면_둘_다_만족하는_글만_나온다() {
		post(recordPost(word("both") + " 검색어만"));
		String hit = post(recordPost(word("both") + " 둘 다"), word("extra"));
		post(recordPost("태그만"), word("extra"));

		PostPage page = postsPage(reader, TS, word("extra"), word("both"), null);

		assertThat(page.ids()).containsExactly(hit);
		assertThat(page.totalCount()).isEqualTo(1);
	}

	@Test
	void 삭제된_글은_검색되지_않는다() {
		String alive = post(recordPost(word("gone") + " 살아 있는 글"));
		String deleted = post(recordPost(word("gone") + " 지울 글"));
		ok(gql(bearerFor(writer), DELETE_POST, vars("id", deleted)));

		assertThat(search(TS, word("gone"))).containsExactly(alive);
	}

	@Test
	void 수정한_내용으로_검색된다() {
		String id = post(recordPost(word("before") + " 제목"));
		Map<String, Object> sections = new LinkedHashMap<>();
		sections.put("problem", "문제");
		sections.put("cause", "원인");
		sections.put("solution", "해결");
		Map<String, Object> input = new LinkedHashMap<>();
		input.put("title", word("after") + " 제목");
		input.put("sections", sections);
		input.put("tags", List.of(mark));
		ok(gql(bearerFor(writer), UPDATE_POST, vars("id", id, "input", input)));

		assertThat(search(TS, word("before"))).isEmpty();
		assertThat(search(TS, word("after"))).containsExactly(id);
	}

	@Test
	void 일치하는_글이_없으면_빈_목록이고_건수는_0이다() {
		post(recordPost("아무 글"));

		PostPage page = postsPage(reader, TS, mark, word("nomatch"), null);

		assertThat(page.items()).isEmpty();
		assertThat(page.nextCursor()).isNull();
		assertThat(page.totalCount()).isZero();
	}

	@Test
	void 검색_결과도_20개씩_커서로_이어_읽는다() {
		List<String> created = new ArrayList<>();
		for (int i = 0; i < 23; i++) {
			created.add(post(recordPost(word("many") + " 글 " + i)));
		}

		List<PostPage> pages = allPostPages(reader, TS, mark, word("many"));

		assertThat(pages).extracting(page -> page.items().size()).containsExactly(20, 3);
		assertThat(pages.get(0).totalCount()).isEqualTo(23);
		List<String> all = new ArrayList<>(pages.get(0).ids());
		all.addAll(pages.get(1).ids());
		List<String> expected = new ArrayList<>(created);
		Collections.reverse(expected);
		assertThat(all).containsExactlyElementsOf(expected);
	}

	// ----- 한국어: 단어 단위로만 일치한다 (DB명세서 3-1) -----

	@Test
	void 한국어는_어절_단위로_찾고_조사가_붙은_어절의_일부로는_찾지_못한다() {
		String id = post(recordPost("캐시 서버를 정리했어요"));

		assertThat(search(TS, "캐시")).containsExactly(id);
		assertThat(search(TS, "서버를")).containsExactly(id);
		// 형태소 분석이 없어 "서버를"의 일부인 "서버"로는 안 걸린다. 부분 일치가 필요하면 별도 결정이 필요하다
		assertThat(search(TS, "서버")).isEmpty();
	}

	// ----- 검색어 길이 (E-61, E-63) -----

	@Test
	void 검색어가_1자면_SEARCH_TOO_SHORT다() {
		post(recordPost("아무 글"));

		GraphQlResponse response = searchResponse(TS, "a");

		assertError(response, ErrorCode.SEARCH_TOO_SHORT.name());
		assertThat(response.errorMessage()).isEqualTo("2자 이상 입력해 주세요.");
		assertError(searchResponse(TS, " 가 "), ErrorCode.SEARCH_TOO_SHORT.name());
	}

	@Test
	void 검색어가_2자면_검색한다() {
		String id = post(recordPost("제목"), "ab");

		assertThat(search(TS, "ab")).containsExactly(id);
	}

	@Test
	void 검색어는_50자까지_허용하고_51자부터_SEARCH_TOO_LONG이다() {
		post(recordPost("아무 글"));

		assertThat(searchResponse(TS, "a".repeat(50)).hasErrors()).isFalse();
		GraphQlResponse response = searchResponse(TS, "a".repeat(51));

		assertError(response, ErrorCode.SEARCH_TOO_LONG.name());
		assertThat(response.errorMessage()).isEqualTo("검색어는 50자까지 쓸 수 있어요.");
		assertThat(searchResponse(TS, "가".repeat(50)).hasErrors()).isFalse();
		assertError(searchResponse(TS, "가".repeat(51)), ErrorCode.SEARCH_TOO_LONG.name());
	}

	@Test
	void 검색어_길이는_앞뒤_공백을_뺀_뒤에_센다() {
		assertThat(searchResponse(TS, "  " + "a".repeat(50) + "  ").hasErrors()).isFalse();
		assertError(searchResponse(TS, "  " + "a".repeat(51) + "  "), ErrorCode.SEARCH_TOO_LONG.name());
	}

	@Test
	void 검색어가_비었거나_공백뿐이면_검색하지_않고_표식_태그_글이_모두_나온다() {
		String first = post(recordPost("첫 글"));
		String second = post(recordPost("둘째 글"));

		assertThat(search(TS, null)).containsExactly(second, first);
		assertThat(search(TS, "")).containsExactly(second, first);
		assertThat(search(TS, "   ")).containsExactly(second, first);
	}

	// ----- 검색 문법 문자 -----

	@Test
	void 따옴표가_섞여도_오류_없이_따옴표를_뺀_단어로_찾는다() {
		String id = post(recordPost(word("quoted") + " 제목"));

		GraphQlResponse response = searchResponse(TS, "\"" + word("quoted") + "\"");

		assertThat(response.hasErrors()).isFalse();
		assertThat(response.data().path("posts").path("items").get(0).path("id").asText()).isEqualTo(id);
	}

	@Test
	void 역슬래시와_따옴표뿐인_검색어는_SEARCH_TOO_SHORT다() {
		assertError(searchResponse(TS, "\"\""), ErrorCode.SEARCH_TOO_SHORT.name());
		assertError(searchResponse(TS, "\\\\"), ErrorCode.SEARCH_TOO_SHORT.name());
	}

	@Test
	void 앞에_붙은_빼기_기호는_제외_연산자가_아니다() {
		String id = post(recordPost(word("negate") + " 제목"));

		// -단어를 제외 연산자로 해석하면 아무 글도 안 나오거나 오류가 난다. 단어 일부로만 다룬다
		GraphQlResponse response = searchResponse(TS, "-" + word("negate"));

		assertThat(response.hasErrors()).isFalse();
		assertThat(response.data().path("posts").path("items").size()).isLessThanOrEqualTo(1);
		if (response.data().path("posts").path("items").size() == 1) {
			assertThat(response.data().path("posts").path("items").get(0).path("id").asText()).isEqualTo(id);
		}
	}

	@Test
	void 달러와_괄호가_든_검색어도_오류_없이_처리한다() {
		post(recordPost("아무 글"));

		for (String q : new String[] {"$where", "{$ne: 1}", "a(b)c", "a|b", "a.*", "%%", "한글 $regex"}) {
			GraphQlResponse response = searchResponse(TS, q);
			assertThat(response.hasErrors()).as(q + " -> " + response.rawBody()).isFalse();
		}
	}

}
