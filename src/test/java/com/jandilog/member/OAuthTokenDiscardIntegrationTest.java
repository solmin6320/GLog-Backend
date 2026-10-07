package com.jandilog.member;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.util.UriComponentsBuilder;

import com.jandilog.testsupport.auth.AuthIntegrationTest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

// GitHub 사용자 액세스 토큰을 서버에 남기지 않는지 확인 (기능명세서 5장 310행)
// 성공 핸들러만 부르면 토큰 저장 단계를 지나가지 않으므로, 가짜 GitHub 서버를 두고 실제 OAuth 필터 체인으로 로그인한다
class OAuthTokenDiscardIntegrationTest extends AuthIntegrationTest {

	private static final String TOKEN_PREFIX = "gho_test_";
	private static final HttpServer GITHUB = startFakeGithub();

	@LocalServerPort
	private int serverPort;
	@Autowired
	private OAuth2AuthorizedClientService authorizedClientService;
	@Autowired
	private OAuth2AuthorizedClientRepository authorizedClientRepository;

	@DynamicPropertySource
	static void fakeGithubEndpoints(DynamicPropertyRegistry registry) {
		String base = "http://localhost:" + GITHUB.getAddress().getPort();
		registry.add("spring.security.oauth2.client.provider.github.authorization-uri", () -> base + "/login/oauth/authorize");
		registry.add("spring.security.oauth2.client.provider.github.token-uri", () -> base + "/login/oauth/access_token");
		registry.add("spring.security.oauth2.client.provider.github.user-info-uri", () -> base + "/user");
	}

	@AfterAll
	static void stopFakeGithub() {
		GITHUB.stop(0);
	}

	// code 파라미터에 GitHub 숫자 id를 실어 토큰·사용자 정보 응답에 그대로 쓴다
	private static HttpServer startFakeGithub() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
			server.createContext("/login/oauth/access_token", exchange -> {
				String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
				String code = formValue(body, "code");
				respondJson(exchange, "{\"access_token\":\"" + TOKEN_PREFIX + code
						+ "\",\"token_type\":\"bearer\",\"scope\":\"read:user\"}");
			});
			server.createContext("/user", exchange -> {
				String authorization = exchange.getRequestHeaders().getFirst("Authorization");
				String id = authorization.substring(("Bearer " + TOKEN_PREFIX).length());
				respondJson(exchange, "{\"id\":" + id + ",\"login\":\"octocat\",\"name\":\"홍길동\"}");
			});
			server.start();
			return server;
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	private static String formValue(String body, String key) {
		for (String pair : body.split("&")) {
			if (pair.startsWith(key + "=")) {
				return pair.substring(key.length() + 1);
			}
		}
		throw new IllegalStateException(key + " 없음");
	}

	private static void respondJson(HttpExchange exchange, String json) throws IOException {
		byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(200, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}

	// 시작 요청 → 가짜 GitHub 인증 단계 생략 → 콜백까지 실제 필터 체인을 지나는 로그인. 콜백 응답의 Location을 돌려준다
	private String loginThroughFilterChain(long githubId) throws Exception {
		HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
		String base = "http://localhost:" + serverPort;

		HttpResponse<Void> start = http.send(HttpRequest.newBuilder(URI.create(base + "/oauth2/authorization/github?client=web")).build(),
				HttpResponse.BodyHandlers.discarding());
		assertThat(start.statusCode()).isEqualTo(302);
		URI authorize = URI.create(start.headers().firstValue("Location").orElseThrow());
		assertThat(authorize.getPort()).isEqualTo(GITHUB.getAddress().getPort());
		String state = UriComponentsBuilder.fromUri(authorize).build().getQueryParams().getFirst("state");
		String sessionCookie = start.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];

		HttpResponse<Void> callback = http.send(
				HttpRequest.newBuilder(URI.create(base + "/login/oauth2/code/github?code=" + githubId + "&state=" + state))
						.header("Cookie", sessionCookie).build(),
				HttpResponse.BodyHandlers.discarding());
		assertThat(callback.statusCode()).isEqualTo(302);
		String location = callback.headers().firstValue("Location").orElseThrow();
		// 로그인 자체는 그대로 성공해 코드가 돌아와야 한다
		assertThat(location).startsWith(authProperties.webBaseUrl() + "/auth/callback?code=");
		return location;
	}

	@Test
	void 로그인을_마친_뒤_OAuth2AuthorizedClientService에_사용자_토큰이_남지_않는다() throws Exception {
		long githubId = members.nextGithubId();

		loginThroughFilterChain(githubId);

		OAuth2AuthorizedClient saved = authorizedClientService.loadAuthorizedClient("github", Long.toString(githubId));
		assertThat(saved).as("GitHub 사용자 액세스 토큰이 서버 메모리에 저장됨").isNull();
	}

	@Test
	void 로그인을_마친_뒤_OAuth2AuthorizedClientRepository에서도_사용자_토큰을_읽을_수_없다() throws Exception {
		long githubId = members.nextGithubId();

		loginThroughFilterChain(githubId);

		DefaultOAuth2User user = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_USER")),
				Map.of("id", githubId), "id");
		Authentication principal = new OAuth2AuthenticationToken(user, user.getAuthorities(), "github");
		OAuth2AuthorizedClient saved = authorizedClientRepository.loadAuthorizedClient("github", principal,
				new MockHttpServletRequest());
		assertThat(saved).as("GitHub 사용자 액세스 토큰을 저장소에서 읽을 수 있음").isNull();
	}

}
