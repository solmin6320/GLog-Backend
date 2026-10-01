package com.jandilog.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.util.ReflectionTestUtils;

import com.jandilog.common.config.AuthProperties;
import com.jandilog.member.domain.Member;
import com.jandilog.member.domain.MemberStatus;
import com.jandilog.member.service.AuthCodeService;
import com.jandilog.member.service.MemberService;

// 성공 핸들러의 분기: 첫 로그인 동시 충돌 재시도, 후처리 실패, 거절 계정 (E-02)
@ExtendWith(MockitoExtension.class)
class OAuthLoginSuccessHandlerTest {

	@Mock
	private MemberService memberService;
	@Mock
	private AuthCodeService authCodeService;

	private OAuthLoginSuccessHandler handler;

	@BeforeEach
	void setUp() {
		LoginRedirector redirector = new LoginRedirector(new AuthProperties("https://jandi.example.com",
				"jandilog://auth", new AuthProperties.Jwt("unit-test-secret-key-0123456789-abcdefghijklmnopqrstuvwxyz", "jandilog", 60)));
		handler = new OAuthLoginSuccessHandler(memberService, authCodeService, redirector);
	}

	private Member member(long id, MemberStatus status) {
		Member member = Member.createPending(100L, "octocat", "홍길동", LocalDateTime.of(2026, 1, 1, 0, 0));
		ReflectionTestUtils.setField(member, "id", id);
		ReflectionTestUtils.setField(member, "status", status);
		return member;
	}

	private String run(Map<String, Object> attributes) throws IOException {
		MockHttpServletResponse response = new MockHttpServletResponse();
		OAuth2User user = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_USER")), attributes, "id");
		handler.onAuthenticationSuccess(new MockHttpServletRequest(), response,
				new OAuth2AuthenticationToken(user, user.getAuthorities(), "github"));
		return response.getHeader("Location");
	}

	private Map<String, Object> attributes() {
		Map<String, Object> attributes = new HashMap<>();
		attributes.put("id", 100L);
		attributes.put("login", "octocat");
		attributes.put("name", "홍길동");
		return attributes;
	}

	@Test
	void 첫_로그인_저장이_유니크_충돌하면_한_번_다시_조회해서_코드를_발급한다() throws IOException {
		when(memberService.loginWithGithub(100L, "octocat", "홍길동"))
				.thenThrow(new DataIntegrityViolationException("duplicate github_id"))
				.thenReturn(member(7L, MemberStatus.PENDING));
		when(authCodeService.issue(7L)).thenReturn("CODE");

		String location = run(attributes());

		assertThat(location).isEqualTo("https://jandi.example.com/auth/callback?code=CODE");
		verify(memberService, times(2)).loginWithGithub(100L, "octocat", "홍길동");
	}

	@Test
	void 재시도까지_실패하면_failed로_돌려보내고_코드를_발급하지_않는다() throws IOException {
		when(memberService.loginWithGithub(anyLong(), anyString(), anyString()))
				.thenThrow(new DataIntegrityViolationException("duplicate"));

		String location = run(attributes());

		assertThat(location).isEqualTo("https://jandi.example.com/auth/callback?error=failed");
		verify(memberService, times(2)).loginWithGithub(anyLong(), anyString(), anyString());
		verify(authCodeService, never()).issue(anyLong());
	}

	@Test
	void 회원_처리_중_예상하지_못한_오류도_failed로_돌려보낸다() throws IOException {
		when(memberService.loginWithGithub(anyLong(), anyString(), anyString()))
				.thenThrow(new IllegalStateException("db down"));

		String location = run(attributes());

		assertThat(location).isEqualTo("https://jandi.example.com/auth/callback?error=failed");
		// 충돌이 아닌 오류는 재시도하지 않는다
		verify(memberService, times(1)).loginWithGithub(anyLong(), anyString(), anyString());
		verify(authCodeService, never()).issue(anyLong());
	}

	@Test
	void 코드_발급이_실패해도_failed로_돌려보낸다() throws IOException {
		when(memberService.loginWithGithub(100L, "octocat", "홍길동")).thenReturn(member(7L, MemberStatus.PENDING));
		when(authCodeService.issue(7L)).thenThrow(new IllegalStateException("redis down"));

		assertThat(run(attributes())).isEqualTo("https://jandi.example.com/auth/callback?error=failed");
	}

	@Test
	void 거절된_회원은_코드를_발급하지_않고_rejected로_돌려보낸다() throws IOException {
		when(memberService.loginWithGithub(100L, "octocat", "홍길동")).thenReturn(member(7L, MemberStatus.REJECTED));

		String location = run(attributes());

		assertThat(location).isEqualTo("https://jandi.example.com/auth/callback?error=rejected");
		verify(authCodeService, never()).issue(anyLong());
	}

	@Test
	void 표시_이름이_없으면_null로_넘긴다() throws IOException {
		Map<String, Object> attributes = attributes();
		attributes.remove("name");
		when(memberService.loginWithGithub(eq(100L), eq("octocat"), isNull())).thenReturn(member(7L, MemberStatus.ACTIVE));
		when(authCodeService.issue(7L)).thenReturn("CODE");

		assertThat(run(attributes)).endsWith("code=CODE");
	}

	@Test
	void 표시_이름이_문자열이_아니어도_null로_넘긴다() throws IOException {
		Map<String, Object> attributes = attributes();
		attributes.put("name", 12345);
		when(memberService.loginWithGithub(eq(100L), eq("octocat"), isNull())).thenReturn(member(7L, MemberStatus.ACTIVE));
		when(authCodeService.issue(7L)).thenReturn("CODE");

		assertThat(run(attributes)).endsWith("code=CODE");
	}

}
