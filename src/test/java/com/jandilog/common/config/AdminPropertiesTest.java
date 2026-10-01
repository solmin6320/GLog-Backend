package com.jandilog.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

// ADMIN_GITHUB_IDS 파싱: GitHub 숫자 id만, 쉼표 구분. 비어 있으면 빈 목록, 잘못된 항목은 기동 실패
class AdminPropertiesTest {

	@Configuration
	@EnableConfigurationProperties(AdminProperties.class)
	static class Config {
	}

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "   ", "\t"})
	void 비어_있으면_빈_목록이다(String raw) {
		assertThat(new AdminProperties(raw).githubIdSet()).isEmpty();
	}

	@Test
	void 숫자_id를_쉼표로_구분해_읽는다() {
		assertThat(new AdminProperties("12345678").githubIdSet()).containsExactly(12345678L);
		assertThat(new AdminProperties("1,2,3").githubIdSet()).containsExactly(1L, 2L, 3L);
	}

	@Test
	void 항목_앞뒤_공백은_잘라서_읽는다() {
		assertThat(new AdminProperties(" 10 , 20 ,30 ").githubIdSet()).containsExactly(10L, 20L, 30L);
	}

	@Test
	void 중복은_한_번만_센다() {
		assertThat(new AdminProperties("7,7,8,7").githubIdSet()).containsExactlyInAnyOrder(7L, 8L);
	}

	@Test
	void Long_최댓값까지_받는다() {
		assertThat(new AdminProperties(Long.toString(Long.MAX_VALUE)).githubIdSet()).containsExactly(Long.MAX_VALUE);
	}

	@ParameterizedTest
	@ValueSource(strings = {"abc", "12a", "a12", "octocat", "1 2", "1.5", "1e3", "0x10", "-5", "+5", "-", "0", "00",
			"99999999999999999999", "９９９"})
	void 숫자_id가_아닌_항목은_거부한다(String raw) {
		assertThatThrownBy(() -> new AdminProperties(raw)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("ADMIN_GITHUB_IDS");
	}

	@ParameterizedTest
	@ValueSource(strings = {"1,,2", "1, ,2", ",", ",1", "1,", "1,2,", " , "})
	void 빈_항목이_섞이면_거부한다(String raw) {
		assertThatThrownBy(() -> new AdminProperties(raw)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("빈 항목");
	}

	@Test
	void 오류는_몇_번째_항목이_왜_틀렸는지_알려준다() {
		assertThatThrownBy(() -> new AdminProperties("100,200,octocat,300"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("3번째")
				.hasMessageContaining("octocat")
				.hasMessageContaining("GitHub 숫자 id");
		assertThatThrownBy(() -> new AdminProperties("100,-7")).hasMessageContaining("2번째").hasMessageContaining("-7");
	}

	@Test
	void 아주_긴_항목은_잘라서_알려준다() {
		String huge = "9".repeat(500);

		assertThatThrownBy(() -> new AdminProperties(huge)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageNotContaining(huge);
	}

	// ----- 기동 시 설정 바인딩 -----

	@Test
	void 설정이_없으면_빈_목록으로_뜬다() {
		runner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(AdminProperties.class).githubIdSet()).isEmpty();
		});
	}

	@Test
	void 설정_키로_바인딩된다() {
		runner.withPropertyValues("jandilog.admin.github-ids=11, 22")
				.run(context -> assertThat(context.getBean(AdminProperties.class).githubIdSet())
						.containsExactly(11L, 22L));
	}

	@ParameterizedTest
	@ValueSource(strings = {"abc", "12a", "-5", "0", "1,,2", "1,2,", "99999999999999999999"})
	void 잘못된_항목이_있으면_기동이_실패하고_원인을_알려준다(String raw) {
		runner.withPropertyValues("jandilog.admin.github-ids=" + raw).run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause().isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("ADMIN_GITHUB_IDS").hasMessageContaining("GitHub 숫자 id");
		});
	}

}
