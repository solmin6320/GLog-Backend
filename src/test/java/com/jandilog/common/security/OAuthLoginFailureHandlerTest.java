package com.jandilog.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

import com.jandilog.common.config.AuthProperties;

// GitHub 화면에서 취소하면 cancelled, 그 밖의 실패는 failed로 돌려보낸다 (E-04)
class OAuthLoginFailureHandlerTest {

	private final OAuthLoginFailureHandler handler = new OAuthLoginFailureHandler(new LoginRedirector(
			new AuthProperties("https://jandi.example.com", "jandilog://auth",
					new AuthProperties.Jwt("unit-test-secret-key-0123456789-abcdef", "jandilog", 60))));

	private MockHttpServletRequest request(LoginClient client) {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setAttribute(ClientAwareAuthorizationRequestRepository.CLIENT_ATTRIBUTE, client.name());
		return request;
	}

	@Test
	void access_denied는_취소로_돌려보낸다() throws IOException {
		MockHttpServletResponse response = new MockHttpServletResponse();

		handler.onAuthenticationFailure(request(LoginClient.WEB), response,
				new OAuth2AuthenticationException(new OAuth2Error("access_denied")));

		assertThat(response.getHeader("Location")).isEqualTo("https://jandi.example.com/auth/callback?error=cancelled");
	}

	@Test
	void 그_밖의_OAuth_오류는_실패로_돌려보낸다() throws IOException {
		MockHttpServletResponse response = new MockHttpServletResponse();

		handler.onAuthenticationFailure(request(LoginClient.WEB), response,
				new OAuth2AuthenticationException(new OAuth2Error("invalid_request")));

		assertThat(response.getHeader("Location")).isEqualTo("https://jandi.example.com/auth/callback?error=failed");
	}

	@Test
	void OAuth가_아닌_예외도_실패로_돌려보낸다() throws IOException {
		MockHttpServletResponse response = new MockHttpServletResponse();

		handler.onAuthenticationFailure(request(LoginClient.WEB), response, new BadCredentialsException("x"));

		assertThat(response.getHeader("Location")).isEqualTo("https://jandi.example.com/auth/callback?error=failed");
	}

	@Test
	void 앱에서_시작했으면_딥링크로_돌려보낸다() throws IOException {
		MockHttpServletResponse response = new MockHttpServletResponse();

		handler.onAuthenticationFailure(request(LoginClient.APP), response,
				new OAuth2AuthenticationException(new OAuth2Error("access_denied")));

		assertThat(response.getHeader("Location")).isEqualTo("jandilog://auth?error=cancelled");
	}

	@Test
	void 인증을_잇던_세션은_버린다() throws IOException {
		MockHttpServletRequest request = request(LoginClient.WEB);
		MockHttpSession session = (MockHttpSession) request.getSession(true);

		handler.onAuthenticationFailure(request, new MockHttpServletResponse(), new BadCredentialsException("x"));

		assertThat(session.isInvalid()).isTrue();
	}

}
