package com.jandilog.post.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

// 기록글 인정 여부 = 글 종류별 필수 항목을 모두 채웠는가 (기능명세서 3장 작성 항목)
class PostSectionsTest {

	@Test
	void 트러블슈팅은_문제_원인_해결을_모두_채우면_기록글이다() {
		PostSections sections = new PostSections("문제", "원인", "해결", null, null);

		assertThat(sections.isComplete(PostType.TROUBLESHOOTING)).isTrue();
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "   ", "\t", "\n", " \n\t "})
	void 트러블슈팅은_문제가_비면_기록글이_아니다(String blank) {
		assertThat(new PostSections(blank, "원인", "해결", null, null).isComplete(PostType.TROUBLESHOOTING)).isFalse();
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "\t"})
	void 트러블슈팅은_원인이_비면_기록글이_아니다(String blank) {
		assertThat(new PostSections("문제", blank, "해결", null, null).isComplete(PostType.TROUBLESHOOTING)).isFalse();
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "\t"})
	void 트러블슈팅은_해결이_비면_기록글이_아니다(String blank) {
		assertThat(new PostSections("문제", "원인", blank, null, null).isComplete(PostType.TROUBLESHOOTING)).isFalse();
	}

	@Test
	void 개발일지는_한_일_배운_점을_모두_채우면_기록글이다() {
		PostSections sections = new PostSections(null, null, null, "한 일", "배운 점");

		assertThat(sections.isComplete(PostType.DEVLOG)).isTrue();
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "\t", "\n"})
	void 개발일지는_한_일이_비면_기록글이_아니다(String blank) {
		assertThat(new PostSections(null, null, null, blank, "배운 점").isComplete(PostType.DEVLOG)).isFalse();
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "\t", "\n"})
	void 개발일지는_배운_점이_비면_기록글이_아니다(String blank) {
		assertThat(new PostSections(null, null, null, "한 일", blank).isComplete(PostType.DEVLOG)).isFalse();
	}

	@Test
	void 종류에_해당하지_않는_항목만_채워서는_기록글이_아니다() {
		PostSections onlyTroubleshooting = new PostSections("문제", "원인", "해결", null, null);
		PostSections onlyDevlog = new PostSections(null, null, null, "한 일", "배운 점");

		assertThat(onlyTroubleshooting.isComplete(PostType.DEVLOG)).isFalse();
		assertThat(onlyDevlog.isComplete(PostType.TROUBLESHOOTING)).isFalse();
	}

	@Test
	void 모든_항목이_비면_종류와_상관없이_기록글이_아니다() {
		PostSections empty = new PostSections(null, null, null, null, null);

		assertThat(empty.isComplete(PostType.TROUBLESHOOTING)).isFalse();
		assertThat(empty.isComplete(PostType.DEVLOG)).isFalse();
	}

	@Test
	void 항목_길이는_따지지_않고_한_글자만_있어도_채운_것이다() {
		assertThat(new PostSections("a", "b", "c", null, null).isComplete(PostType.TROUBLESHOOTING)).isTrue();
		assertThat(new PostSections(null, null, null, "가", "나").isComplete(PostType.DEVLOG)).isTrue();
	}

}
