package com.jandilog.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.ServerProperties;

import com.jandilog.testsupport.auth.AuthIntegrationTest;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;

// OAuth 진행 중 세션 쿠키 속성. 기본값이 Secure여야 한다 (운영 HTTPS 전제)
class SessionCookieIntegrationTest extends AuthIntegrationTest {

	@Autowired
	private ServerProperties serverProperties;

	@Test
	void 세션_쿠키_Secure_기본값은_true다() {
		assertThat(serverProperties.getServlet().getSession().getCookie().getSecure()).isTrue();
		assertThat(serverProperties.getServlet().getSession().getCookie().getHttpOnly()).isTrue();
	}

	@Test
	void GitHub_로그인_시작_응답의_세션_쿠키에_Secure_HttpOnly_SameSite가_붙는다() {
		GraphQlResponse response = graphQl.get("/oauth2/authorization/github", null);

		List<String> cookies = response.headers().allValues("Set-Cookie");
		assertThat(response.status()).isEqualTo(302);
		assertThat(cookies).hasSize(1);
		assertThat(cookies.get(0)).startsWith("JSESSIONID=").contains("Secure").contains("HttpOnly")
				.containsIgnoringCase("SameSite=Lax");
	}

}
