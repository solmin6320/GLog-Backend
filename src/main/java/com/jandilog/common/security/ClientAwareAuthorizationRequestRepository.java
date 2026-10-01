package com.jandilog.common.security;

import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// 시작 요청의 client 파라미터를 인증 요청에 실어 두었다가, 콜백 때 request 속성으로 꺼내 준다
public class ClientAwareAuthorizationRequestRepository
		implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

	public static final String CLIENT_ATTRIBUTE = LoginClient.class.getName();

	private final HttpSessionOAuth2AuthorizationRequestRepository delegate =
			new HttpSessionOAuth2AuthorizationRequestRepository();

	public static LoginClient clientOf(HttpServletRequest request) {
		Object value = request.getAttribute(CLIENT_ATTRIBUTE);
		return LoginClient.from(value instanceof String s ? s : null);
	}

	@Override
	public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
		return delegate.loadAuthorizationRequest(request);
	}

	@Override
	public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest,
			HttpServletRequest request, HttpServletResponse response) {
		if (authorizationRequest == null) {
			delegate.saveAuthorizationRequest(null, request, response);
			return;
		}
		LoginClient client = LoginClient.from(request.getParameter("client"));
		OAuth2AuthorizationRequest withClient = OAuth2AuthorizationRequest.from(authorizationRequest)
				.attributes(attributes -> attributes.put(CLIENT_ATTRIBUTE, client.name()))
				.build();
		delegate.saveAuthorizationRequest(withClient, request, response);
	}

	@Override
	public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,
			HttpServletResponse response) {
		return remember(delegate.removeAuthorizationRequest(request, response), request);
	}

	private OAuth2AuthorizationRequest remember(OAuth2AuthorizationRequest authorizationRequest,
			HttpServletRequest request) {
		if (authorizationRequest != null) {
			request.setAttribute(CLIENT_ATTRIBUTE, authorizationRequest.getAttribute(CLIENT_ATTRIBUTE));
		}
		return authorizationRequest;
	}

}
