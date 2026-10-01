package com.jandilog.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// 로그인 복귀 주소와 JWT 설정. webBaseUrl은 CORS 허용 출처에도 쓴다
@ConfigurationProperties(prefix = "jandilog.auth")
public record AuthProperties(String webBaseUrl, String appDeepLink, Jwt jwt) {

	public AuthProperties {
		if (webBaseUrl == null || !(webBaseUrl.startsWith("http://") || webBaseUrl.startsWith("https://"))) {
			throw new IllegalArgumentException("WEB_BASE_URL은 http:// 또는 https://로 시작해야 해요");
		}
		if (appDeepLink == null || appDeepLink.isBlank() || !appDeepLink.contains("://")) {
			throw new IllegalArgumentException("APP_DEEP_LINK는 scheme://host 형태여야 해요");
		}
		if (jwt == null) {
			throw new IllegalArgumentException("jandilog.auth.jwt 설정이 없어요");
		}
		webBaseUrl = webBaseUrl.endsWith("/") ? webBaseUrl.substring(0, webBaseUrl.length() - 1) : webBaseUrl;
	}

	// secret은 환경변수(JWT_SECRET)로만 받는다. 길이·문자 다양성·약한 값은 JwtSecretPolicy가 검사
	public record Jwt(String secret, String issuer, long expiresMinutes) {

		// expiresIn을 초 단위 GraphQL Int로 내려주므로 30일까지만 허용
		private static final long MAX_EXPIRES_MINUTES = 43_200;

		public Jwt {
			JwtSecretPolicy.validate(secret);
			if (issuer == null || issuer.isBlank()) {
				throw new IllegalArgumentException("jandilog.auth.jwt.issuer가 비었어요");
			}
			if (expiresMinutes < 1 || expiresMinutes > MAX_EXPIRES_MINUTES) {
				throw new IllegalArgumentException("JWT_EXPIRES_MINUTES는 1~" + MAX_EXPIRES_MINUTES + " 사이여야 해요");
			}
		}

		// 로그에 키가 찍히지 않게 가린다
		@Override
		public String toString() {
			return "Jwt[secret=****, issuer=" + issuer + ", expiresMinutes=" + expiresMinutes + "]";
		}

	}

}
