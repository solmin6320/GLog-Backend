package com.jandilog.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.jandilog.common.config.AuthProperties.Jwt;

// 기동 때 잘못된 로그인 설정을 막는 규칙 (JWT 키 32바이트, 만료 범위, 복귀 주소 형식)
class AuthPropertiesTest {

	private static final String SECRET = "x".repeat(32);

	private static Jwt jwt() {
		return new Jwt(SECRET, "jandilog", 60);
	}

	@Test
	void 정상_설정이면_만들어지고_웹_주소_끝의_슬래시는_뗀다() {
		AuthProperties properties = new AuthProperties("https://jandi.example.com/", "jandilog://auth", jwt());

		assertThat(properties.webBaseUrl()).isEqualTo("https://jandi.example.com");
		assertThat(properties.appDeepLink()).isEqualTo("jandilog://auth");
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"localhost:5173", "ftp://example.com", "javascript:alert(1)", "//example.com"})
	void 웹_주소는_http나_https로_시작해야_한다(String webBaseUrl) {
		assertThatThrownBy(() -> new AuthProperties(webBaseUrl, "jandilog://auth", jwt()))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"   ", "jandilog", "jandilog:/auth"})
	void 앱_딥링크는_scheme_host_형태여야_한다(String deepLink) {
		assertThatThrownBy(() -> new AuthProperties("https://jandi.example.com", deepLink, jwt()))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void JWT_설정이_없으면_만들_수_없다() {
		assertThatThrownBy(() -> new AuthProperties("https://jandi.example.com", "jandilog://auth", null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void JWT_키는_32바이트_이상이어야_한다() {
		assertThatThrownBy(() -> new Jwt(null, "jandilog", 60)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Jwt("x".repeat(31), "jandilog", 60)).isInstanceOf(IllegalArgumentException.class);
		assertThatCode(() -> new Jwt("x".repeat(32), "jandilog", 60)).doesNotThrowAnyException();
	}

	@Test
	void JWT_키_길이는_글자_수가_아니라_바이트_수로_센다() {
		// 한글 한 글자는 3바이트: 10글자 30바이트는 부족, 11글자 33바이트는 충분
		assertThatThrownBy(() -> new Jwt("가".repeat(10), "jandilog", 60)).isInstanceOf(IllegalArgumentException.class);
		assertThatCode(() -> new Jwt("가".repeat(11), "jandilog", 60)).doesNotThrowAnyException();
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"  "})
	void 발급자가_비어_있으면_만들_수_없다(String issuer) {
		assertThatThrownBy(() -> new Jwt(SECRET, issuer, 60)).isInstanceOf(IllegalArgumentException.class);
	}

	@ParameterizedTest
	@ValueSource(longs = {Long.MIN_VALUE, -1, 0, 43_201, Long.MAX_VALUE})
	void 만료_시간은_1분에서_30일_사이만_허용한다(long minutes) {
		assertThatThrownBy(() -> new Jwt(SECRET, "jandilog", minutes)).isInstanceOf(IllegalArgumentException.class);
	}

	@ParameterizedTest
	@ValueSource(longs = {1, 1440, 43_200})
	void 만료_시간_경계값은_허용한다(long minutes) {
		assertThatCode(() -> new Jwt(SECRET, "jandilog", minutes)).doesNotThrowAnyException();
	}

	@Test
	void 문자열로_찍어도_JWT_키는_드러나지_않는다() {
		String secret = "this-secret-must-not-appear-in-logs-0123456789";
		AuthProperties properties = new AuthProperties("https://jandi.example.com", "jandilog://auth",
				new Jwt(secret, "jandilog", 60));

		assertThat(properties.toString()).doesNotContain(secret);
		assertThat(properties.jwt().toString()).doesNotContain(secret).contains("****");
	}

}
