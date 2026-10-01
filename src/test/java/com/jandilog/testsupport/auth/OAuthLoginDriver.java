package com.jandilog.testsupport.auth;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

import com.jandilog.common.security.ClientAwareAuthorizationRequestRepository;
import com.jandilog.common.security.LoginClient;
import com.jandilog.common.security.OAuthLoginSuccessHandler;

// 실제 GitHub를 부르지 않고 성공 핸들러에 GitHub 사용자 정보를 직접 넣어 "GitHub 인증을 마친 로그인"을 흉내 낸다
public class OAuthLoginDriver {

	public record LoginResult(String location, String code, String error) {
	}

	private final OAuthLoginSuccessHandler handler;

	public OAuthLoginDriver(OAuthLoginSuccessHandler handler) {
		this.handler = handler;
	}

	public LoginResult login(long githubId, String login, String name, LoginClient client) {
		Map<String, Object> attributes = new HashMap<>();
		attributes.put("id", githubId);
		attributes.put("login", login);
		if (name != null) {
			attributes.put("name", name);
		}
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setAttribute(ClientAwareAuthorizationRequestRepository.CLIENT_ATTRIBUTE, client.name());
		request.getSession(true);
		MockHttpServletResponse response = new MockHttpServletResponse();
		OAuth2User user = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_USER")), attributes, "id");
		try {
			handler.onAuthenticationSuccess(request, response,
					new OAuth2AuthenticationToken(user, user.getAuthorities(), "github"));
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		String location = response.getHeader("Location");
		MultiValueMap<String, String> params = UriComponentsBuilder.fromUriString(location).build().getQueryParams();
		return new LoginResult(location, params.getFirst("code"), params.getFirst("error"));
	}

	public LoginResult login(long githubId, String login, String name) {
		return login(githubId, login, name, LoginClient.WEB);
	}

}
