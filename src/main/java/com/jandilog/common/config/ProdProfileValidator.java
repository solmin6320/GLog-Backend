package com.jandilog.common.config;

import java.util.Locale;

import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

// prod 프로필 기동 검증. 잘못되면 컨텍스트가 뜨지 않는다.
// 로그인 복귀 주소·GitHub 콜백 주소·잔디 조회 주소는 https만, 세션 쿠키는 Secure 고정,
// Redis·MongoDB는 TLS 필수, DB 비밀번호는 개발 기본값·빈 값·짧은 값 거부. 오류 메시지에는 비밀값을 넣지 않는다
@Component
@Profile("prod")
public class ProdProfileValidator {

	private static final String REDIRECT_URI_KEY = "spring.security.oauth2.client.registration.github.redirect-uri";
	private static final String GRASS_URL_KEY = "jandilog.grass.github-graphql-url";
	private static final String REDIS_SSL_KEY = "spring.data.redis.ssl.enabled";
	private static final String MONGO_URI_KEY = "spring.data.mongodb.uri";
	private static final String DB_PASSWORD_KEY = "spring.datasource.password";

	// 로컬 docker-compose 기본 비밀번호
	private static final String DEV_DB_PASSWORD = "jandilog";
	static final int MIN_DB_PASSWORD_LENGTH = 12;

	public ProdProfileValidator(AuthProperties properties, ServerProperties serverProperties, Environment environment) {
		requireHttps("WEB_BASE_URL", properties.webBaseUrl());
		// 콜백 주소는 BACKEND_BASE_URL + /login/oauth2/code/{registrationId}로 만들어진다
		requireHttps("BACKEND_BASE_URL", environment.getProperty(REDIRECT_URI_KEY));
		// 잔디 조회 주소는 지정한 경우에만 검사한다. 지정하지 않으면 https인 기본값을 쓴다
		String grassUrl = environment.getProperty(GRASS_URL_KEY);
		if (grassUrl != null) {
			requireHttps(GRASS_URL_KEY, grassUrl);
		}
		if (!Boolean.TRUE.equals(serverProperties.getServlet().getSession().getCookie().getSecure())) {
			throw new IllegalStateException("운영에서는 세션 쿠키 Secure가 꺼져 있으면 안 돼요");
		}
		requireRedisTls(environment.getProperty(REDIS_SSL_KEY));
		requireMongoTls(environment.getProperty(MONGO_URI_KEY));
		requireStrongDbPassword(environment.getProperty(DB_PASSWORD_KEY));
	}

	private static void requireHttps(String name, String url) {
		if (url == null || !url.startsWith("https://")) {
			throw new IllegalStateException(name + "은 운영에서 https://로 시작해야 해요");
		}
	}

	private static void requireRedisTls(String ssl) {
		if (ssl == null || !"true".equalsIgnoreCase(ssl.strip())) {
			throw new IllegalStateException("REDIS_SSL은 운영에서 true여야 해요");
		}
	}

	// mongodb+srv://는 TLS가 기본이고, mongodb://는 tls=true(또는 옛 이름 ssl=true)가 있어야 한다.
	// 옵션을 직접 false로 준 경우는 srv여도 거부한다. URI 전체를 파싱하지 않고(DNS 조회·오류 메시지에 URI 노출 방지) 옵션만 읽는다
	private static void requireMongoTls(String uri) {
		if (uri == null || uri.isBlank()) {
			throw new IllegalStateException("MONGODB_URI가 비어 있어요");
		}
		String lower = uri.strip().toLowerCase(Locale.ROOT);
		boolean srv = lower.startsWith("mongodb+srv://");
		if (!srv && !lower.startsWith("mongodb://")) {
			throw new IllegalStateException("MONGODB_URI는 mongodb+srv:// 또는 mongodb://로 시작해야 해요");
		}
		Boolean explicit = tlsOption(lower);
		boolean tls = explicit != null ? explicit : srv;
		if (!tls) {
			throw new IllegalStateException("MONGODB_URI는 운영에서 TLS를 써야 해요 (mongodb+srv:// 또는 tls=true)");
		}
	}

	// 쿼리의 tls·ssl 옵션 값. 없으면 null, 여러 번 나오면 하나라도 false면 false
	private static Boolean tlsOption(String lowerUri) {
		int query = lowerUri.indexOf('?');
		if (query < 0) {
			return null;
		}
		Boolean result = null;
		for (String pair : lowerUri.substring(query + 1).split("[&;]")) {
			int eq = pair.indexOf('=');
			if (eq < 0) {
				continue;
			}
			String key = pair.substring(0, eq);
			if (key.equals("tls") || key.equals("ssl")) {
				boolean value = pair.substring(eq + 1).equals("true");
				result = result == null ? value : result && value;
			}
		}
		return result;
	}

	private static void requireStrongDbPassword(String password) {
		if (password == null || password.isBlank() || DEV_DB_PASSWORD.equalsIgnoreCase(password.strip())
				|| password.length() < MIN_DB_PASSWORD_LENGTH) {
			throw new IllegalStateException(
					"DB_PASSWORD는 운영에서 비어 있거나 개발 기본값이거나 " + MIN_DB_PASSWORD_LENGTH + "자 미만이면 안 돼요");
		}
	}

}
