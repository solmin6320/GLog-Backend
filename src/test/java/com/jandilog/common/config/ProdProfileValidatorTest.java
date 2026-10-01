package com.jandilog.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

// prod 프로필 기동 검증: 로그인 복귀 주소·GitHub 콜백 주소는 https만, 세션 쿠키는 Secure
class ProdProfileValidatorTest {

	private static final String REDIRECT_KEY = "spring.security.oauth2.client.registration.github.redirect-uri";
	private static final String HTTPS_REDIRECT = "https://api.example.com/login/oauth2/code/{registrationId}";
	private static final String SECRET = "k3Jx9Qv7mW2pL8nR4tY6uB1cD5eF0gHaZsXoViNqMlPwKjTy";

	private static AuthProperties auth(String webBaseUrl) {
		return new AuthProperties(webBaseUrl, "com.jandilog.app://auth", new AuthProperties.Jwt(SECRET, "jandilog", 60));
	}

	private static ServerProperties server(Boolean secure) {
		ServerProperties properties = new ServerProperties();
		properties.getServlet().getSession().getCookie().setSecure(secure);
		return properties;
	}

	private static MockEnvironment env(String redirectUri) {
		MockEnvironment env = new MockEnvironment();
		if (redirectUri != null) {
			env.setProperty(REDIRECT_KEY, redirectUri);
		}
		return env;
	}

	@Test
	void 두_주소가_https이고_쿠키가_Secure면_통과한다() {
		assertThatCode(() -> new ProdProfileValidator(auth("https://jandi.example.com"), server(true),
				env(HTTPS_REDIRECT))).doesNotThrowAnyException();
	}

	@Test
	void 웹_주소가_http면_기동을_막는다() {
		assertThatThrownBy(() -> new ProdProfileValidator(auth("http://jandi.example.com"), server(true),
				env(HTTPS_REDIRECT))).isInstanceOf(IllegalStateException.class).hasMessageContaining("WEB_BASE_URL");
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"http://api.example.com/login/oauth2/code/{registrationId}",
			"api.example.com/login", "//api.example.com/login"})
	void GitHub_콜백_주소가_https가_아니면_기동을_막는다(String redirectUri) {
		assertThatThrownBy(() -> new ProdProfileValidator(auth("https://jandi.example.com"), server(true),
				env(redirectUri))).isInstanceOf(IllegalStateException.class).hasMessageContaining("BACKEND_BASE_URL");
	}

	@Test
	void 세션_쿠키_Secure가_꺼져_있거나_비어_있으면_기동을_막는다() {
		assertThatThrownBy(() -> new ProdProfileValidator(auth("https://jandi.example.com"), server(false),
				env(HTTPS_REDIRECT))).isInstanceOf(IllegalStateException.class).hasMessageContaining("Secure");
		assertThatThrownBy(() -> new ProdProfileValidator(auth("https://jandi.example.com"), server(null),
				env(HTTPS_REDIRECT))).isInstanceOf(IllegalStateException.class).hasMessageContaining("Secure");
	}

	@Test
	void prod_프로필이_켜졌을_때만_검증이_돈다() {
		ApplicationContextRunner base = new ApplicationContextRunner()
				.withBean(AuthProperties.class, () -> auth("http://localhost:5173"))
				.withBean(ServerProperties.class, () -> server(false))
				.withUserConfiguration(ProdProfileValidator.class);

		// prod가 아니면 http·Secure 꺼짐이어도 빈이 없고 그대로 뜬다 (로컬 개발)
		base.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).doesNotHaveBean(ProdProfileValidator.class);
		});

		// prod면 http 주소에서 컨텍스트가 뜨지 않는다
		base.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
				.withPropertyValues(REDIRECT_KEY + "=" + HTTPS_REDIRECT)
				.run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class);
				});
	}

	@Test
	void prod에서_모든_조건이_맞으면_컨텍스트가_뜬다() {
		new ApplicationContextRunner()
				.withBean(AuthProperties.class, () -> auth("https://jandi.example.com"))
				.withBean(ServerProperties.class, () -> server(true))
				.withUserConfiguration(ProdProfileValidator.class)
				.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
				.withPropertyValues(REDIRECT_KEY + "=" + HTTPS_REDIRECT)
				.run(context -> assertThat(context).hasNotFailed().hasSingleBean(ProdProfileValidator.class));
	}

}
