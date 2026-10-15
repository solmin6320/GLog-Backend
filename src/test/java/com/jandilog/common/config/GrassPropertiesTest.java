package com.jandilog.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

// 잔디 조회용 GitHub 서버 토큰 설정: 토큰은 환경변수로만 받고, 헤더에 그대로 실리므로 형식을 막고, 출력에서는 가린다
class GrassPropertiesTest {

	private static final String TOKEN = "ghp_unitTestOnlyToken0123456789";
	private static final String URL = "https://api.github.com/graphql";
	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	@Test
	void 정상_값은_그대로_받는다() {
		GrassProperties properties = new GrassProperties(TOKEN, URL, TIMEOUT);

		assertThat(properties.githubApiToken()).isEqualTo(TOKEN);
		assertThat(properties.githubGraphqlUrl()).isEqualTo(URL);
		assertThat(properties.timeout()).isEqualTo(TIMEOUT);
	}

	@Test
	void toString은_토큰을_가린다() {
		String text = new GrassProperties(TOKEN, URL, TIMEOUT).toString();

		assertThat(text).doesNotContain(TOKEN).doesNotContain("ghp_").contains("****").contains(URL);
	}

	@Test
	void 문자열_결합과_포맷에도_토큰이_드러나지_않는다() {
		GrassProperties properties = new GrassProperties(TOKEN, URL, TIMEOUT);

		assertThat("설정: " + properties).doesNotContain(TOKEN);
		assertThat(String.format("%s", properties)).doesNotContain(TOKEN);
		assertThat(String.valueOf(properties)).doesNotContain(TOKEN);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "   ", "\t", "\n"})
	void 토큰이_없거나_비었으면_거부한다(String token) {
		assertThatThrownBy(() -> new GrassProperties(token, URL, TIMEOUT))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = {"ghp_ab cd", "ghp_ab\tcd", "ghp_ab\ncd", "ghp_ab\r\ncd", "ghp_ab\u0000cd",
			"ghp_ab\u0001cd", "ghp_ab\u007fcd"})
	void 토큰_가운데에_공백이나_제어문자가_있으면_거부하고_메시지에_토큰을_싣지_않는다(String token) {
		assertThatThrownBy(() -> new GrassProperties(token, URL, TIMEOUT))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageNotContaining("ghp_ab");
	}

	@ParameterizedTest
	@ValueSource(strings = {" ghp_abcd", "ghp_abcd ", "ghp_abcd\n", "\tghp_abcd\r\n"})
	void 토큰_앞뒤_공백은_잘라서_받는다(String token) {
		assertThat(new GrassProperties(token, URL, TIMEOUT).githubApiToken()).isEqualTo("ghp_abcd");
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"api.github.com/graphql", "ftp://api.github.com", "file:///etc/passwd", "ws://x"})
	void 주소는_http나_https로_시작해야_한다(String url) {
		assertThatThrownBy(() -> new GrassProperties(TOKEN, url, TIMEOUT))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = {"http://127.0.0.1:8089/graphql", "https://api.github.com/graphql"})
	void http와_https_주소를_받는다(String url) {
		assertThat(new GrassProperties(TOKEN, url, TIMEOUT).githubGraphqlUrl()).isEqualTo(url);
	}

	@Test
	void 제한_시간은_0보다_커야_한다() {
		assertThatThrownBy(() -> new GrassProperties(TOKEN, URL, null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new GrassProperties(TOKEN, URL, Duration.ZERO))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new GrassProperties(TOKEN, URL, Duration.ofSeconds(-1)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(new GrassProperties(TOKEN, URL, Duration.ofMillis(1)).timeout()).isEqualTo(Duration.ofMillis(1));
	}

	@Test
	void 설정_바인딩은_토큰만_주면_주소와_제한_시간에_기본값을_쓴다() {
		Binder binder = new Binder(new MapConfigurationPropertySource(
				Map.of("jandilog.grass.github-api-token", TOKEN)));

		GrassProperties properties = binder.bind("jandilog.grass", GrassProperties.class).get();

		assertThat(properties.githubGraphqlUrl()).isEqualTo("https://api.github.com/graphql");
		assertThat(properties.timeout()).isEqualTo(Duration.ofSeconds(10));
		assertThat(properties.githubApiToken()).isEqualTo(TOKEN);
	}

	@Test
	void 설정_바인딩은_주소와_제한_시간을_덮어쓸_수_있다() {
		Binder binder = new Binder(new MapConfigurationPropertySource(Map.of(
				"jandilog.grass.github-api-token", TOKEN,
				"jandilog.grass.github-graphql-url", "http://127.0.0.1:9/graphql",
				"jandilog.grass.timeout", "3s")));

		GrassProperties properties = binder.bind("jandilog.grass", GrassProperties.class).get();

		assertThat(properties.githubGraphqlUrl()).isEqualTo("http://127.0.0.1:9/graphql");
		assertThat(properties.timeout()).isEqualTo(Duration.ofSeconds(3));
	}

}
