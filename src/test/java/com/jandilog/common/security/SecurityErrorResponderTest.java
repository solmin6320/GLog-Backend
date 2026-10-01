package com.jandilog.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

// 필터 단계 인증·권한 오류를 GraphQL 오류 모양 JSON으로 내린다. 토큰 오류 사유는 알리지 않는다 (E-03)
class SecurityErrorResponderTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private final SecurityErrorResponder responder = new SecurityErrorResponder(mapper);

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	private JsonNode firstError(MockHttpServletResponse response) throws IOException {
		return mapper.readTree(response.getContentAsString()).path("errors").path(0);
	}

	@Test
	void 인증_실패는_401과_E03_문구와_Bearer_스킴만_내린다() throws IOException {
		MockHttpServletResponse response = new MockHttpServletResponse();

		responder.commence(new MockHttpServletRequest(), response,
				new InvalidBearerTokenException("Jwt expired at 2026-01-01T00:00:00Z"));

		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(response.getContentType()).startsWith("application/json");
		assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
		assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
		JsonNode error = firstError(response);
		assertThat(error.path("message").asText()).isEqualTo("로그인이 만료됐어요. 다시 로그인해 주세요.");
		assertThat(error.path("extensions").path("code").asText()).isEqualTo("UNAUTHENTICATED");
		assertThat(error.path("extensions").path("classification").asText()).isEqualTo("UNAUTHORIZED");
	}

	@Test
	void 인증_실패_사유는_응답에_싣지_않는다() throws IOException {
		MockHttpServletResponse response = new MockHttpServletResponse();

		responder.commence(new MockHttpServletRequest(), response,
				new InvalidBearerTokenException("Jwt expired at 2026-01-01T00:00:00Z"));

		assertThat(response.getContentAsString()).doesNotContain("expired", "Jwt", "2026-01-01");
		assertThat(response.getHeader("WWW-Authenticate")).doesNotContain("error", "invalid_token");
	}

	@Test
	void 승인_대기_계정의_권한_거부는_403_E01이다() throws IOException {
		SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("p", "c",
				AuthorityUtils.createAuthorityList("ROLE_PENDING")));
		MockHttpServletResponse response = new MockHttpServletResponse();

		responder.handle(new MockHttpServletRequest(), response, new AccessDeniedException("denied"));

		assertThat(response.getStatus()).isEqualTo(403);
		assertThat(response.getHeader("WWW-Authenticate")).isNull();
		JsonNode error = firstError(response);
		assertThat(error.path("extensions").path("code").asText()).isEqualTo("ACCOUNT_PENDING");
		assertThat(error.path("extensions").path("classification").asText()).isEqualTo("FORBIDDEN");
	}

	@Test
	void 거절된_계정의_권한_거부는_403_E02다() throws IOException {
		SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("p", "c",
				AuthorityUtils.createAuthorityList("ROLE_REJECTED")));
		MockHttpServletResponse response = new MockHttpServletResponse();

		responder.handle(new MockHttpServletRequest(), response, new AccessDeniedException("denied"));

		assertThat(response.getStatus()).isEqualTo(403);
		assertThat(firstError(response).path("extensions").path("code").asText()).isEqualTo("ACCOUNT_REJECTED");
	}

	@Test
	void 활성_회원의_권한_거부는_403_E09다() throws IOException {
		SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("p", "c",
				AuthorityUtils.createAuthorityList("ROLE_MEMBER")));
		MockHttpServletResponse response = new MockHttpServletResponse();

		responder.handle(new MockHttpServletRequest(), response, new AccessDeniedException("denied"));

		assertThat(response.getStatus()).isEqualTo(403);
		JsonNode error = firstError(response);
		assertThat(error.path("extensions").path("code").asText()).isEqualTo("FORBIDDEN");
		assertThat(error.path("message").asText()).isEqualTo("이 페이지에 들어올 권한이 없어요.");
	}

	@Test
	void 로그인하지_않은_채_권한_거부가_나면_401이다() throws IOException {
		MockHttpServletResponse response = new MockHttpServletResponse();

		responder.handle(new MockHttpServletRequest(), response, new AccessDeniedException("denied"));

		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
		assertThat(firstError(response).path("extensions").path("code").asText()).isEqualTo("UNAUTHENTICATED");
	}

}
