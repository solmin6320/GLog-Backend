package com.jandilog.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

// 로그인 시작 때의 client 값을 콜백까지 들고 간다 (기능명세서 1장: 웹·앱 한 콜백)
class ClientAwareAuthorizationRequestRepositoryTest {

	private final ClientAwareAuthorizationRequestRepository repository = new ClientAwareAuthorizationRequestRepository();

	private OAuth2AuthorizationRequest authorizationRequest(String state) {
		return OAuth2AuthorizationRequest.authorizationCode()
				.authorizationUri("https://github.com/login/oauth/authorize")
				.clientId("test-client")
				.redirectUri("http://localhost:8080/login/oauth2/code/github")
				.state(state)
				.build();
	}

	// 시작 요청에서 저장하고, 같은 세션의 콜백 요청에서 꺼낸다
	private MockHttpServletRequest startAndCallback(String clientParam, String state) {
		MockHttpServletRequest start = new MockHttpServletRequest("GET", "/oauth2/authorization/github");
		if (clientParam != null) {
			start.setParameter("client", clientParam);
		}
		repository.saveAuthorizationRequest(authorizationRequest(state), start, new MockHttpServletResponse());

		MockHttpServletRequest callback = new MockHttpServletRequest("GET", "/login/oauth2/code/github");
		callback.setSession(start.getSession());
		callback.setParameter("state", state);
		OAuth2AuthorizationRequest removed = repository.removeAuthorizationRequest(callback, new MockHttpServletResponse());
		assertThat(removed).isNotNull();
		return callback;
	}

	@Test
	void client_app으로_시작하면_콜백에서_앱으로_읽힌다() {
		assertThat(ClientAwareAuthorizationRequestRepository.clientOf(startAndCallback("app", "s1")))
				.isEqualTo(LoginClient.APP);
	}

	@Test
	void client_web이나_없는_값이면_웹이다() {
		assertThat(ClientAwareAuthorizationRequestRepository.clientOf(startAndCallback("web", "s2")))
				.isEqualTo(LoginClient.WEB);
		assertThat(ClientAwareAuthorizationRequestRepository.clientOf(startAndCallback(null, "s3")))
				.isEqualTo(LoginClient.WEB);
		assertThat(ClientAwareAuthorizationRequestRepository.clientOf(startAndCallback("전혀-모르는-값", "s4")))
				.isEqualTo(LoginClient.WEB);
	}

	@Test
	void 저장된_인증_요청이_없으면_웹이다() {
		MockHttpServletRequest callback = new MockHttpServletRequest("GET", "/login/oauth2/code/github");
		callback.setParameter("state", "none");

		assertThat(repository.removeAuthorizationRequest(callback, new MockHttpServletResponse())).isNull();
		assertThat(ClientAwareAuthorizationRequestRepository.clientOf(callback)).isEqualTo(LoginClient.WEB);
	}

	@Test
	void 한_번_꺼낸_인증_요청은_다시_꺼낼_수_없다() {
		MockHttpServletRequest start = new MockHttpServletRequest("GET", "/oauth2/authorization/github");
		start.setParameter("client", "app");
		repository.saveAuthorizationRequest(authorizationRequest("once"), start, new MockHttpServletResponse());

		MockHttpServletRequest first = new MockHttpServletRequest();
		first.setSession(start.getSession());
		first.setParameter("state", "once");
		MockHttpServletRequest second = new MockHttpServletRequest();
		second.setSession(start.getSession());
		second.setParameter("state", "once");

		assertThat(repository.removeAuthorizationRequest(first, new MockHttpServletResponse())).isNotNull();
		assertThat(repository.removeAuthorizationRequest(second, new MockHttpServletResponse())).isNull();
	}

}
