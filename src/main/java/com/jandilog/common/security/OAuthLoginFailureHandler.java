package com.jandilog.common.security;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

// GitHub 인증 실패·취소 시 웹/앱으로 오류 구분만 싣고 돌려보낸다 (E-04)
@Component
public class OAuthLoginFailureHandler implements AuthenticationFailureHandler {

	private static final Logger log = LoggerFactory.getLogger(OAuthLoginFailureHandler.class);

	private final LoginRedirector redirector;

	public OAuthLoginFailureHandler(LoginRedirector redirector) {
		this.redirector = redirector;
	}

	@Override
	public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException exception) throws IOException {
		HttpSession session = request.getSession(false);
		if (session != null) {
			session.invalidate();
		}
		// OAuth 오류 코드만 기록한다. 사용자 입력이나 GitHub 응답 본문은 남기지 않는다
		String errorCode = exception instanceof OAuth2AuthenticationException oauth
				? oauth.getError().getErrorCode() : exception.getClass().getSimpleName();
		log.warn("GitHub 로그인 실패: {}", errorCode);

		boolean cancelled = "access_denied".equals(errorCode);
		redirector.sendError(request, response, cancelled ? "cancelled" : "failed");
	}

}
