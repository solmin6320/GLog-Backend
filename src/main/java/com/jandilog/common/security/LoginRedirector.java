package com.jandilog.common.security;

import java.io.IOException;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import com.jandilog.common.config.AuthProperties;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// 로그인 결과를 웹 주소(/auth/callback) 또는 앱 딥링크로 돌려보낸다. 토큰은 싣지 않고 코드·오류 구분만 싣는다
@Component
public class LoginRedirector {

	private static final String WEB_CALLBACK_PATH = "/auth/callback";

	private final AuthProperties properties;

	public LoginRedirector(AuthProperties properties) {
		this.properties = properties;
	}

	public void sendCode(HttpServletRequest request, HttpServletResponse response, String code) throws IOException {
		send(request, response, "code", code);
	}

	// error: cancelled(E-04) / rejected(E-02) / failed
	public void sendError(HttpServletRequest request, HttpServletResponse response, String error) throws IOException {
		send(request, response, "error", error);
	}

	private void send(HttpServletRequest request, HttpServletResponse response, String name, String value)
			throws IOException {
		UriComponentsBuilder target = switch (ClientAwareAuthorizationRequestRepository.clientOf(request)) {
			case APP -> UriComponentsBuilder.fromUriString(properties.appDeepLink());
			case WEB -> UriComponentsBuilder.fromUriString(properties.webBaseUrl()).path(WEB_CALLBACK_PATH);
		};
		String url = target.queryParam(name, value).build().encode().toUriString();
		response.setStatus(HttpServletResponse.SC_FOUND);
		response.setHeader(HttpHeaders.LOCATION, url);
		// 코드가 든 주소가 Referer나 캐시로 새지 않게 한다
		response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
		response.setHeader("Referrer-Policy", "no-referrer");
	}

}
