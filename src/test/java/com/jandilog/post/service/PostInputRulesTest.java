package com.jandilog.post.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.post.domain.PostSections;
import com.jandilog.post.domain.PostType;
import com.jandilog.post.dto.PostSectionsInput;

// 글·댓글·검색 입력 규칙 (BD-01·BD-03·BD-04 예외 표, E-29·E-30·E-61·E-63)
class PostInputRulesTest {

	private static void assertCode(Runnable action, ErrorCode expected) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(expected));
	}

	// ----- 제목 -----

	@Test
	void 제목은_앞뒤_공백을_지운다() {
		assertThat(PostInputRules.title("  Redis TTL 문제  ")).isEqualTo("Redis TTL 문제");
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "   ", "\t", "\n", " \t\n "})
	void 제목이_비었거나_공백뿐이면_TITLE_REQUIRED다(String raw) {
		assertCode(() -> PostInputRules.title(raw), ErrorCode.TITLE_REQUIRED);
	}

	@Test
	void 제목은_60자까지_허용하고_61자부터_INVALID_INPUT이다() {
		assertThat(PostInputRules.title("a".repeat(60))).hasSize(60);
		assertCode(() -> PostInputRules.title("a".repeat(61)), ErrorCode.INVALID_INPUT);
	}

	@Test
	void 제목_길이는_앞뒤_공백을_뺀_뒤에_센다() {
		assertThat(PostInputRules.title("  " + "a".repeat(60) + "  ")).hasSize(60);
		assertCode(() -> PostInputRules.title("  " + "a".repeat(61) + "  "), ErrorCode.INVALID_INPUT);
	}

	@Test
	void 제목_길이는_글자_수로_세어_한글과_이모지도_한_글자다() {
		assertThat(PostInputRules.title("가".repeat(60))).hasSize(60);
		assertCode(() -> PostInputRules.title("가".repeat(61)), ErrorCode.INVALID_INPUT);
		// 이모지는 UTF-16으로 두 칸이지만 한 글자로 센다
		String emoji60 = "😀".repeat(60);
		assertThat(PostInputRules.title(emoji60)).isEqualTo(emoji60);
		assertCode(() -> PostInputRules.title("😀".repeat(61)), ErrorCode.INVALID_INPUT);
	}

	// ----- 본문 항목 -----

	@Test
	void 트러블슈팅은_문제_원인_해결만_남기고_나머지는_버린다() {
		PostSections sections = PostInputRules.sections(PostType.TROUBLESHOOTING,
				new PostSectionsInput("  문제  ", " 원인 ", "해결", "한 일", "배운 점"));

		assertThat(sections).isEqualTo(new PostSections("문제", "원인", "해결", null, null));
	}

	@Test
	void 개발일지는_한_일_배운_점만_남기고_나머지는_버린다() {
		PostSections sections = PostInputRules.sections(PostType.DEVLOG,
				new PostSectionsInput("문제", "원인", "해결", " 한 일 ", "배운 점 "));

		assertThat(sections).isEqualTo(new PostSections(null, null, null, "한 일", "배운 점"));
	}

	@Test
	void 비어_있는_항목은_null이_아니라_빈_문자열로_자리를_유지한다() {
		PostSections troubleshooting = PostInputRules.sections(PostType.TROUBLESHOOTING,
				new PostSectionsInput(null, "  ", null, null, null));
		PostSections devlog = PostInputRules.sections(PostType.DEVLOG, new PostSectionsInput(null, null, null, null, ""));

		assertThat(troubleshooting).isEqualTo(new PostSections("", "", "", null, null));
		assertThat(devlog).isEqualTo(new PostSections(null, null, null, "", ""));
		assertThat(troubleshooting.isComplete(PostType.TROUBLESHOOTING)).isFalse();
		assertThat(devlog.isComplete(PostType.DEVLOG)).isFalse();
	}

	@Test
	void 본문_항목_묶음이_null이면_INVALID_INPUT이다() {
		assertCode(() -> PostInputRules.sections(PostType.DEVLOG, null), ErrorCode.INVALID_INPUT);
	}

	// ----- 태그 -----

	@Test
	void 태그가_없으면_빈_목록이다() {
		assertThat(PostInputRules.tags(null)).isEmpty();
		assertThat(PostInputRules.tags(List.of())).isEmpty();
	}

	@Test
	void 태그는_앞뒤_공백을_지우고_소문자로_통일한다() {
		assertThat(PostInputRules.tags(List.of("  Redis ", "SPRING", "JPA"))).containsExactly("redis", "spring", "jpa");
	}

	@Test
	void 대소문자만_다른_같은_태그는_하나로_합치고_입력_순서를_지킨다() {
		assertThat(PostInputRules.tags(List.of("Spring", "redis", "SPRING", " spring ", "Redis")))
				.containsExactly("spring", "redis");
	}

	@Test
	void 빈_태그와_null_태그는_버린다() {
		assertThat(PostInputRules.tags(Arrays.asList("a", "", "  ", null, "b"))).containsExactly("a", "b");
	}

	@Test
	void 태그는_5개까지_허용하고_6개부터_TAG_LIMIT_EXCEEDED다() {
		assertThat(PostInputRules.tags(List.of("a", "b", "c", "d", "e"))).hasSize(5);
		assertCode(() -> PostInputRules.tags(List.of("a", "b", "c", "d", "e", "f")), ErrorCode.TAG_LIMIT_EXCEEDED);
	}

	@Test
	void 중복과_빈_태그를_뺀_개수로_5개_한도를_센다() {
		assertThat(PostInputRules.tags(List.of("a", "A", "b", "", "c", "d", "e", " "))).hasSize(5);
	}

	@Test
	void 태그_한_개는_20자까지_허용하고_21자부터_TAG_TOO_LONG이다() {
		assertThat(PostInputRules.tags(List.of("a".repeat(20)))).hasSize(1);
		assertCode(() -> PostInputRules.tags(List.of("a".repeat(21))), ErrorCode.TAG_TOO_LONG);
	}

	@Test
	void 태그_길이는_앞뒤_공백을_뺀_뒤에_센다() {
		assertThat(PostInputRules.tags(List.of(" " + "a".repeat(20) + " "))).hasSize(1);
	}

	@Test
	void 한글_태그_20자는_허용한다() {
		assertThat(PostInputRules.tags(List.of("가".repeat(20)))).hasSize(1);
		assertCode(() -> PostInputRules.tags(List.of("가".repeat(21))), ErrorCode.TAG_TOO_LONG);
	}

	@Test
	void 태그_필터용_정규화는_공백을_지우고_소문자로_만들며_비면_null이다() {
		assertThat(PostInputRules.normalizeTag(" Redis ")).isEqualTo("redis");
		assertThat(PostInputRules.normalizeTag(null)).isNull();
		assertThat(PostInputRules.normalizeTag("")).isNull();
		assertThat(PostInputRules.normalizeTag("   ")).isNull();
	}

	// ----- 커밋 링크 -----

	@ParameterizedTest
	@ValueSource(strings = {
			"https://github.com/octo/repo/commit/abc1234",
			"https://github.com/octo/repo/commit/ABCDEF1",
			"https://github.com/octo/repo/commit/0123456789abcdef0123456789abcdef01234567",
			"https://github.com/octo-cat/my.repo_name-1/commit/abc1234",
			"https://github.com/1octo/repo/commit/abc1234"})
	void 정해진_형식의_커밋_주소는_통과한다(String url) {
		assertThat(PostInputRules.commitUrls(List.of(url))).containsExactly(url);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"http://github.com/octo/repo/commit/abc1234",
			"https://gitlab.com/octo/repo/commit/abc1234",
			"https://github.com/octo/repo",
			"https://github.com/octo/repo/pull/12",
			"https://github.com/octo/repo/commit/abc123",
			"https://github.com/octo/repo/commit/0123456789abcdef0123456789abcdef012345678",
			"https://github.com/octo/repo/commit/abcdefg",
			"https://github.com/octo/repo/commit/abc1234/",
			"https://github.com/octo/repo/commit/abc1234/files",
			"https://github.com/octo/repo/commit/abc1234?diff=split",
			"https://github.com/octo/repo/commit/abc1234#diff",
			"https://github.com/-octo/repo/commit/abc1234",
			"https://github.com/octo//commit/abc1234",
			"https://github.com/octo/../commit/abc1234",
			"https://github.com/octo/./commit/abc1234",
			"https://www.github.com/octo/repo/commit/abc1234",
			"github.com/octo/repo/commit/abc1234",
			"javascript:alert(1)",
			"not a url"})
	void 형식이_다른_커밋_주소는_INVALID_COMMIT_URL이다(String url) {
		assertCode(() -> PostInputRules.commitUrls(List.of(url)), ErrorCode.INVALID_COMMIT_URL);
	}

	@Test
	void 소유자_이름은_39자까지_허용한다() {
		assertThat(PostInputRules.commitUrls(List.of("https://github.com/" + "a".repeat(39) + "/r/commit/abc1234"))).hasSize(1);
		assertCode(() -> PostInputRules.commitUrls(List.of("https://github.com/" + "a".repeat(40) + "/r/commit/abc1234")),
				ErrorCode.INVALID_COMMIT_URL);
	}

	@Test
	void 저장소_이름은_100자까지_허용한다() {
		assertThat(PostInputRules.commitUrls(List.of("https://github.com/o/" + "r".repeat(100) + "/commit/abc1234")))
				.hasSize(1);
		assertCode(() -> PostInputRules.commitUrls(List.of("https://github.com/o/" + "r".repeat(101) + "/commit/abc1234")),
				ErrorCode.INVALID_COMMIT_URL);
	}

	@Test
	void 커밋_주소는_앞뒤_공백을_지우고_빈_값과_중복은_버린다() {
		List<String> urls = PostInputRules.commitUrls(Arrays.asList("  https://github.com/o/r/commit/abc1234 ", "",
				"  ", null, "https://github.com/o/r/commit/abc1234", "https://github.com/o/r/commit/def5678"));

		assertThat(urls).containsExactly("https://github.com/o/r/commit/abc1234", "https://github.com/o/r/commit/def5678");
	}

	@Test
	void 커밋_주소가_없으면_빈_목록이다() {
		assertThat(PostInputRules.commitUrls(null)).isEmpty();
		assertThat(PostInputRules.commitUrls(List.of())).isEmpty();
	}

	@Test
	void 하나라도_형식이_틀리면_전체가_거부된다() {
		assertCode(() -> PostInputRules.commitUrls(
				List.of("https://github.com/o/r/commit/abc1234", "https://github.com/o/r")), ErrorCode.INVALID_COMMIT_URL);
	}

	// ----- 팀 연결 -----

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "   "})
	void 팀_id가_없거나_비면_팀_연결이_없다(String raw) {
		assertThat(PostInputRules.teamId(raw)).isNull();
	}

	@Test
	void 팀_id는_양의_정수를_받고_앞뒤_공백은_지운다() {
		assertThat(PostInputRules.teamId("3")).isEqualTo(3L);
		assertThat(PostInputRules.teamId(" 42 ")).isEqualTo(42L);
		assertThat(PostInputRules.teamId(Long.toString(Long.MAX_VALUE))).isEqualTo(Long.MAX_VALUE);
	}

	@ParameterizedTest
	@ValueSource(strings = {"0", "-1", "abc", "1.5", "1 2", "99999999999999999999"})
	void 팀_id가_양의_정수가_아니면_INVALID_INPUT이다(String raw) {
		assertCode(() -> PostInputRules.teamId(raw), ErrorCode.INVALID_INPUT);
	}

	// ----- 댓글 -----

	@Test
	void 댓글은_앞뒤_공백을_지운다() {
		assertThat(PostInputRules.commentContent("  저도 같은 문제 겪었어요  ")).isEqualTo("저도 같은 문제 겪었어요");
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "\t", "\n", " \n "})
	void 댓글이_비었거나_공백뿐이면_INVALID_INPUT이다(String raw) {
		assertCode(() -> PostInputRules.commentContent(raw), ErrorCode.INVALID_INPUT);
	}

	@Test
	void 댓글은_500자까지_허용하고_501자부터_INVALID_INPUT이다() {
		assertThat(PostInputRules.commentContent("a".repeat(500))).hasSize(500);
		assertCode(() -> PostInputRules.commentContent("a".repeat(501)), ErrorCode.INVALID_INPUT);
	}

	@Test
	void 댓글_길이는_앞뒤_공백을_뺀_글자_수로_센다() {
		assertThat(PostInputRules.commentContent(" " + "가".repeat(500) + " ")).hasSize(500);
		assertCode(() -> PostInputRules.commentContent("가".repeat(501)), ErrorCode.INVALID_INPUT);
		assertThat(PostInputRules.commentContent("😀".repeat(500))).isEqualTo("😀".repeat(500));
	}

	@Test
	void 댓글_본문의_줄바꿈은_그대로_둔다() {
		assertThat(PostInputRules.commentContent("첫 줄\n둘째 줄")).isEqualTo("첫 줄\n둘째 줄");
	}

	// ----- 검색어 -----

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "   ", "\t", "\n"})
	void 검색어가_없거나_공백뿐이면_검색하지_않는다(String raw) {
		assertThat(PostInputRules.searchTerms(raw)).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = {"a", "가", "  a  ", " 가 ", "\ta\n"})
	void 검색어가_1자면_SEARCH_TOO_SHORT다(String raw) {
		assertCode(() -> PostInputRules.searchTerms(raw), ErrorCode.SEARCH_TOO_SHORT);
	}

	@Test
	void 검색어는_2자부터_50자까지_허용한다() {
		assertThat(PostInputRules.searchTerms("ab")).containsExactly("ab");
		assertThat(PostInputRules.searchTerms("a".repeat(50))).hasSize(1);
		assertCode(() -> PostInputRules.searchTerms("a".repeat(51)), ErrorCode.SEARCH_TOO_LONG);
	}

	@Test
	void 검색어_길이는_앞뒤_공백을_뺀_글자_수로_센다() {
		assertThat(PostInputRules.searchTerms("  " + "a".repeat(50) + "  ")).hasSize(1);
		assertCode(() -> PostInputRules.searchTerms("  " + "a".repeat(51) + "  "), ErrorCode.SEARCH_TOO_LONG);
		assertThat(PostInputRules.searchTerms("가".repeat(50))).hasSize(1);
		assertCode(() -> PostInputRules.searchTerms("가".repeat(51)), ErrorCode.SEARCH_TOO_LONG);
		assertThat(PostInputRules.searchTerms("😀".repeat(50))).hasSize(1);
		assertCode(() -> PostInputRules.searchTerms("😀".repeat(51)), ErrorCode.SEARCH_TOO_LONG);
	}

	@Test
	void 검색어는_공백으로_나눠_단어_목록이_된다() {
		assertThat(PostInputRules.searchTerms("redis  cache\tttl")).containsExactly("redis", "cache", "ttl");
		assertThat(PostInputRules.searchTerms("  캐시 서버  ")).containsExactly("캐시", "서버");
	}

	@Test
	void 따옴표와_역슬래시는_글자로_취급하지_않고_뺀다() {
		assertThat(PostInputRules.searchTerms("\"redis\"")).containsExactly("redis");
		assertThat(PostInputRules.searchTerms("a\"b")).containsExactly("a", "b");
		assertThat(PostInputRules.searchTerms("re\\dis")).containsExactly("re", "dis");
		assertThat(PostInputRules.searchTerms("\"redis cache\"")).containsExactly("redis", "cache");
	}

	@Test
	void 따옴표와_역슬래시뿐인_검색어는_단어가_남지_않아_SEARCH_TOO_SHORT다() {
		assertCode(() -> PostInputRules.searchTerms("\"\""), ErrorCode.SEARCH_TOO_SHORT);
		assertCode(() -> PostInputRules.searchTerms("\\\\"), ErrorCode.SEARCH_TOO_SHORT);
		assertCode(() -> PostInputRules.searchTerms("\"\\\""), ErrorCode.SEARCH_TOO_SHORT);
	}

	@Test
	void 검색_연산자_문자는_단어_안에_그대로_남는다() {
		// 단어마다 따옴표로 감싸 보내므로 -제외, $ 같은 문자는 연산자가 되지 않는다
		List<String> terms = new ArrayList<>(PostInputRules.searchTerms("-redis $cache"));

		assertThat(terms).containsExactly("-redis", "$cache");
	}

}
