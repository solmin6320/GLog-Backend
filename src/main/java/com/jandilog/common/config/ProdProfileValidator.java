package com.jandilog.common.config;

import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

// prod 프로필 기동 검증. 잘못되면 컨텍스트가 뜨지 않는다.
// 로그인 복귀 주소·GitHub 콜백 주소는 https만, 세션 쿠키는 Secure 고정
@Component
@Profile("prod")
public class ProdProfileValidator {

	private static final String REDIRECT_URI_KEY = "spring.security.oauth2.client.registration.github.redirect-uri";

	public ProdProfileValidator(AuthProperties properties, ServerProperties serverProperties, Environment environment) {
		requireHttps("WEB_BASE_URL", properties.webBaseUrl());
		// 콜백 주소는 BACKEND_BASE_URL + /login/oauth2/code/{registrationId}로 만들어진다
		requireHttps("BACKEND_BASE_URL", environment.getProperty(REDIRECT_URI_KEY));
		if (!Boolean.TRUE.equals(serverProperties.getServlet().getSession().getCookie().getSecure())) {
			throw new IllegalStateException("운영에서는 세션 쿠키 Secure가 꺼져 있으면 안 돼요");
		}
	}

	private static void requireHttps(String name, String url) {
		if (url == null || !url.startsWith("https://")) {
			throw new IllegalStateException(name + "은 운영에서 https://로 시작해야 해요");
		}
	}

}
