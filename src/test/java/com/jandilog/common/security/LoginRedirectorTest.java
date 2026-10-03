package com.jandilog.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.jandilog.common.config.AuthProperties;

// 로그인 결과 복귀 주소: 웹은 /auth/callback, 앱은 딥링크. 토큰은 싣지 않는다 (기능명세서 1장)
class LoginRedirectorTest {

	private static final String SECRET = "unit-test-secret-key-0123456789-abcdef";

	private final LoginRedirector redirector = new LoginRedirector(new AuthProperties(
			"https://jandi.example.com/", "jandilog://auth", new AuthProperties.Jwt(SECRET, "jandilog", 60)));

	private MockHttpServletRequest requestFrom(LoginClient client) {
		MockHttpServletRequest request = new MockHttpServletRequest();
		if (client != null) {
			request.setAttribute(ClientAwareAuthorizationRequestRepository.CLIENT_ATTRIBUTE, client.name());
		}
		return request;
	}

	@Test
	void 웹_로그인은_웹_콜백_주소로_코드를_실어_보낸다() throws IOException {
		MockHttpServletResponse response = new MockHttpServletResponse();

		redirector.sendCode(requestFrom(LoginClient.WEB), response, "abc_DEF-123");

		assertThat(response.getStatus()).isEqualTo(302);
		assertThat(response.getHeader("Location")).isEqualTo("https://jandi.example.com/auth/callback?code=abc_DEF-123");
	}

	@Test
	void 앱_로그인은_딥링크로_코드를_실어_보낸다() throws IOException {
		MockHttpServletResponse response = new MockHttpServletResponse();

		redirector.sendCode(requestFrom(LoginClient.APP), response, "abc_DEF-123");

		assertThat(response.getStatus()).isEqualTo(302);
		assertThat(response.getHeader("Location")).isEqualTo("jandilog://auth?code=abc_DEF-123");
	}

	@Test
	void 시작_구분이_없으면_웹으로_보낸다() throws IOException {
		MockHttpServletResponse response = new MockHttpServletResponse();

		redirector.sendCode(requestFrom(null), response, "c");

		assertThat(response.getHeader("Location")).startsWith("https://jandi.example.com/auth/callback?");
	}

	@Test
	void 오류는_error_파라미터로_구분만_싣는다() throws IOException {
		MockHttpServletResponse web = new MockHttpServletResponse();
		MockHttpServletResponse app = new MockHttpServletResponse();

		redirector.sendError(requestFrom(LoginClient.WEB), web, "rejected");
		redirector.sendError(requestFrom(LoginClient.APP), app, "cancelled");

		assertThat(web.getHeader("Location")).isEqualTo("https://jandi.example.com/auth/callback?error=rejected");
		assertThat(app.getHeader("Location")).isEqualTo("jandilog://auth?error=cancelled");
	}

	@Test
	void 코드가_든_주소가_캐시와_Referer로_새지_않게_헤더를_둔다() throws IOException {
		MockHttpServletResponse response = new MockHttpServletResponse();

		redirector.sendCode(requestFrom(LoginClient.WEB), response, "c");

		assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
		assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
	}

}
