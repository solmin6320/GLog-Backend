package com.jandilog.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// 로그인 복귀 주소 설정. webBaseUrl은 CORS 허용 출처에도 쓴다
@ConfigurationProperties(prefix = "jandilog.auth")
public record AuthProperties(String webBaseUrl, String appDeepLink) {

	public AuthProperties {
		if (webBaseUrl == null || !(webBaseUrl.startsWith("http://") || webBaseUrl.startsWith("https://"))) {
			throw new IllegalArgumentException("WEB_BASE_URL은 http:// 또는 https://로 시작해야 해요");
		}
		if (appDeepLink == null || appDeepLink.isBlank() || !appDeepLink.contains("://")) {
			throw new IllegalArgumentException("APP_DEEP_LINK는 scheme://host 형태여야 해요");
		}
		webBaseUrl = webBaseUrl.endsWith("/") ? webBaseUrl.substring(0, webBaseUrl.length() - 1) : webBaseUrl;
	}

}
