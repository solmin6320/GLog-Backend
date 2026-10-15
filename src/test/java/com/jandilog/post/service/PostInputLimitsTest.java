package com.jandilog.post.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.post.domain.PostSections;
import com.jandilog.post.domain.PostType;
import com.jandilog.post.dto.PostSectionsInput;

// 글 입력 상한: 필수 항목은 항목당 5000자, 관련 커밋 링크는 5개 (사용자 확정 2026-10-06, 문구는 5-2-8 표준형)
class PostInputLimitsTest {

	private static void assertCode(Runnable action, ErrorCode expected) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(expected));
	}

	private static PostSectionsInput troubleshooting(String problem, String cause, String solution) {
		return new PostSectionsInput(problem, cause, solution, null, null);
	}

	private static PostSectionsInput devlog(String did, String learned) {
		return new PostSectionsInput(null, null, null, did, learned);
	}

	private static List<String> commitUrls(int count) {
		List<String> urls = new ArrayList<>();
		for (int i = 1; i <= count; i++) {
			urls.add(String.format("https://github.com/owner/repo/commit/%07x", i));
		}
		return urls;
	}

	// ----- 필수 항목 5000자 -----

	@Test
	void 트러블슈팅_항목은_5000자까지_허용하고_5001자부터_항목별_오류다() {
		String max = "a".repeat(5000);
		String over = "a".repeat(5001);

		assertThat(PostInputRules.sections(PostType.TROUBLESHOOTING, troubleshooting(max, max, max)))
				.isEqualTo(new PostSections(max, max, max, null, null));
		assertCode(() -> PostInputRules.sections(PostType.TROUBLESHOOTING, troubleshooting(over, "원인", "해결")),
				ErrorCode.POST_PROBLEM_TOO_LONG);
		assertCode(() -> PostInputRules.sections(PostType.TROUBLESHOOTING, troubleshooting("문제", over, "해결")),
				ErrorCode.POST_CAUSE_TOO_LONG);
		assertCode(() -> PostInputRules.sections(PostType.TROUBLESHOOTING, troubleshooting("문제", "원인", over)),
				ErrorCode.POST_SOLUTION_TOO_LONG);
	}

	@Test
	void 개발일지_항목은_5000자까지_허용하고_5001자부터_항목별_오류다() {
		String max = "a".repeat(5000);
		String over = "a".repeat(5001);

		assertThat(PostInputRules.sections(PostType.DEVLOG, devlog(max, max)))
				.isEqualTo(new PostSections(null, null, null, max, max));
		assertCode(() -> PostInputRules.sections(PostType.DEVLOG, devlog(over, "배운 점")), ErrorCode.POST_DID_TOO_LONG);
		assertCode(() -> PostInputRules.sections(PostType.DEVLOG, devlog("한 일", over)), ErrorCode.POST_LEARNED_TOO_LONG);
	}

	@Test
	void 항목_길이는_앞뒤_공백을_지운_뒤_글자_수로_센다() {
		String paddedMax = "  " + "a".repeat(5000) + "\n\t ";
		String paddedOver = "  " + "a".repeat(5001) + "  ";

		assertThat(PostInputRules.sections(PostType.DEVLOG, devlog(paddedMax, "배운 점")).did()).hasSize(5000);
		assertCode(() -> PostInputRules.sections(PostType.DEVLOG, devlog(paddedOver, "배운 점")),
				ErrorCode.POST_DID_TOO_LONG);
		// 공백만 5001자여도 지우면 비어서 오류가 아니다
		assertThat(PostInputRules.sections(PostType.DEVLOG, devlog(" ".repeat(5001), "배운 점")).did()).isEmpty();
	}

	@Test
	void 한글과_이모지도_한_글자로_세어_5000자까지_허용한다() {
		String hangulMax = "가".repeat(5000);
		// 이모지는 UTF-16으로 두 칸이지만 한 글자로 센다
		String emojiMax = "😀".repeat(5000);

		assertThat(PostInputRules.sections(PostType.DEVLOG, devlog(hangulMax, emojiMax)))
				.isEqualTo(new PostSections(null, null, null, hangulMax, emojiMax));
		assertCode(() -> PostInputRules.sections(PostType.DEVLOG, devlog("가".repeat(5001), "배운 점")),
				ErrorCode.POST_DID_TOO_LONG);
		assertCode(() -> PostInputRules.sections(PostType.DEVLOG, devlog("한 일", "😀".repeat(5001))),
				ErrorCode.POST_LEARNED_TOO_LONG);
	}

	@ParameterizedTest
	@EnumSource(PostType.class)
	void 필수_항목이_비거나_없어도_오류가_아니고_기록글_여부만_거짓이다(PostType type) {
		PostSections empty = PostInputRules.sections(type, new PostSectionsInput(null, "", "   ", null, ""));

		assertThat(empty.isComplete(type)).isFalse();
	}

	@Test
	void 항목_길이_검사_순서는_문제_원인_해결_순이다() {
		String over = "a".repeat(5001);

		assertCode(() -> PostInputRules.sections(PostType.TROUBLESHOOTING, troubleshooting(over, over, over)),
				ErrorCode.POST_PROBLEM_TOO_LONG);
		assertCode(() -> PostInputRules.sections(PostType.TROUBLESHOOTING, troubleshooting("문제", over, over)),
				ErrorCode.POST_CAUSE_TOO_LONG);
	}

	@Test
	void 글_종류에_없는_항목은_길이를_보지_않고_버린다() {
		PostSectionsInput input = new PostSectionsInput("문제", "원인", "해결", "a".repeat(9000), "b".repeat(9000));

		assertThat(PostInputRules.sections(PostType.TROUBLESHOOTING, input))
				.isEqualTo(new PostSections("문제", "원인", "해결", null, null));
	}

	// ----- 관련 커밋 링크 5개 -----

	@Test
	void 커밋_링크는_5개까지_허용하고_6개부터_오류다() {
		assertThat(PostInputRules.commitUrls(commitUrls(5))).hasSize(5);
		assertCode(() -> PostInputRules.commitUrls(commitUrls(6)), ErrorCode.POST_COMMIT_URLS_LIMIT_EXCEEDED);
	}

	@Test
	void 커밋_링크_개수는_빈_값과_중복을_뺀_뒤에_센다() {
		List<String> urls = new ArrayList<>(commitUrls(5));
		urls.add("  ");
		urls.add(null);
		urls.add("  " + urls.get(0) + "  ");
		urls.add(urls.get(1));

		assertThat(PostInputRules.commitUrls(urls)).containsExactlyElementsOf(commitUrls(5));
	}

	@Test
	void 커밋_링크가_없거나_null이면_빈_목록이다() {
		assertThat(PostInputRules.commitUrls(null)).isEmpty();
		assertThat(PostInputRules.commitUrls(List.of())).isEmpty();
	}

	@Test
	void 형식이_틀린_링크는_개수보다_먼저_형식_오류다() {
		List<String> urls = new ArrayList<>(commitUrls(5));
		urls.add("https://example.com/not-a-commit");

		assertCode(() -> PostInputRules.commitUrls(urls), ErrorCode.INVALID_COMMIT_URL);
	}

	// ----- 문구 -----

	@Test
	void 오류_문구는_5_2_8_입력_검증_표준형이고_입력_오류_400이다() {
		assertThat(ErrorCode.POST_PROBLEM_TOO_LONG.message()).isEqualTo("문제는 5000자까지 쓸 수 있어요.");
		assertThat(ErrorCode.POST_CAUSE_TOO_LONG.message()).isEqualTo("원인은 5000자까지 쓸 수 있어요.");
		assertThat(ErrorCode.POST_SOLUTION_TOO_LONG.message()).isEqualTo("해결은 5000자까지 쓸 수 있어요.");
		assertThat(ErrorCode.POST_DID_TOO_LONG.message()).isEqualTo("한 일은 5000자까지 쓸 수 있어요.");
		assertThat(ErrorCode.POST_LEARNED_TOO_LONG.message()).isEqualTo("배운 점은 5000자까지 쓸 수 있어요.");
		assertThat(ErrorCode.POST_COMMIT_URLS_LIMIT_EXCEEDED.message()).isEqualTo("관련 커밋 링크는 5개까지 달 수 있어요.");
		for (ErrorCode code : new ErrorCode[] { ErrorCode.POST_PROBLEM_TOO_LONG, ErrorCode.POST_CAUSE_TOO_LONG,
				ErrorCode.POST_SOLUTION_TOO_LONG, ErrorCode.POST_DID_TOO_LONG, ErrorCode.POST_LEARNED_TOO_LONG,
				ErrorCode.POST_COMMIT_URLS_LIMIT_EXCEEDED }) {
			assertThat(code.httpStatus()).isEqualTo(400);
			assertThat(code.graphQlType()).isEqualTo(ErrorCode.INVALID_INPUT.graphQlType());
		}
	}

}
