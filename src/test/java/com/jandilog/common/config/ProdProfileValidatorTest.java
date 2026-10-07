package com.jandilog.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

// prod 프로필 기동 검증: 로그인 복귀 주소·GitHub 콜백 주소·잔디 조회 주소는 https만, 세션 쿠키는 Secure,
// Redis·MongoDB는 TLS, DB 비밀번호는 개발 기본값·빈 값·짧은 값 거부
class ProdProfileValidatorTest {

	private static final String REDIRECT_KEY = "spring.security.oauth2.client.registration.github.redirect-uri";
	private static final String GRASS_URL_KEY = "jandilog.grass.github-graphql-url";
	private static final String REDIS_SSL_KEY = "spring.data.redis.ssl.enabled";
	private static final String MONGO_URI_KEY = "spring.data.mongodb.uri";
	private static final String DB_PASSWORD_KEY = "spring.datasource.password";
	private static final String HTTPS_REDIRECT ="https://api.example.com/login/oauth2/code/{registrationId}";
	private static final String SECRET = "k3Jx9Qv7mW2pL8nR4tY6uB1cD5eF0gHaZsXoViNqMlPwKjTy";
	// 아래 두 값은 테스트 전용이고 실제 비밀값이 아니다
	private static final String MONGO_URI = "mongodb+srv://app:Mongo-Pw-7731@cluster0.example.mongodb.net/jandilog";
	private static final String DB_PASSWORD = "Xk9-mQ2vL7pR4tYz";

	private static AuthProperties auth(String webBaseUrl) {
		return new AuthProperties(webBaseUrl, "com.jandilog.app://auth", new AuthProperties.Jwt(SECRET, "jandilog", 60));
	}

	private static ServerProperties server(Boolean secure) {
		ServerProperties properties = new ServerProperties();
		properties.getServlet().getSession().getCookie().setSecure(secure);
		return properties;
	}

	// 새 검증 항목(Redis TLS·Mongo URI·DB 비밀번호)은 통과하는 값으로 채워 둔다
	private static MockEnvironment env(String redirectUri) {
		MockEnvironment env = new MockEnvironment();
		if (redirectUri != null) {
			env.setProperty(REDIRECT_KEY, redirectUri);
		}
		env.setProperty(REDIS_SSL_KEY, "true");
		env.setProperty(MONGO_URI_KEY, MONGO_URI);
		env.setProperty(DB_PASSWORD_KEY, DB_PASSWORD);
		return env;
	}

	private static ProdProfileValidator validate(MockEnvironment env) {
		return new ProdProfileValidator(auth("https://jandi.example.com"), server(true), env);
	}

	private static ApplicationContextRunner prodRunner(String webBaseUrl, Boolean secure) {
		return new ApplicationContextRunner()
				.withBean(AuthProperties.class, () -> auth(webBaseUrl))
				.withBean(ServerProperties.class, () -> server(secure))
				.withUserConfiguration(ProdProfileValidator.class);
	}

	@Test
	void 두_주소가_https이고_쿠키가_Secure면_통과한다() {
		assertThatCode(() -> new ProdProfileValidator(auth("https://jandi.example.com"), server(true),
				env(HTTPS_REDIRECT))).doesNotThrowAnyException();
	}

	@Test
	void 웹_주소가_http면_기동을_막는다() {
		assertThatThrownBy(() -> new ProdProfileValidator(auth("http://jandi.example.com"), server(true),
				env(HTTPS_REDIRECT))).isInstanceOf(IllegalStateException.class).hasMessageContaining("WEB_BASE_URL");
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"http://api.example.com/login/oauth2/code/{registrationId}",
			"api.example.com/login", "//api.example.com/login"})
	void GitHub_콜백_주소가_https가_아니면_기동을_막는다(String redirectUri) {
		assertThatThrownBy(() -> new ProdProfileValidator(auth("https://jandi.example.com"), server(true),
				env(redirectUri))).isInstanceOf(IllegalStateException.class).hasMessageContaining("BACKEND_BASE_URL");
	}

	@ParameterizedTest
	@ValueSource(strings = {"http://api.github.com/graphql", "api.github.com/graphql", ""})
	void 잔디_조회_주소를_지정했는데_https가_아니면_기동을_막는다(String grassUrl) {
		MockEnvironment env = env(HTTPS_REDIRECT);
		env.setProperty(GRASS_URL_KEY, grassUrl);
		assertThatThrownBy(() -> new ProdProfileValidator(auth("https://jandi.example.com"), server(true), env))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining(GRASS_URL_KEY);
	}

	@Test
	void 잔디_조회_주소가_https이거나_지정하지_않으면_통과한다() {
		MockEnvironment env = env(HTTPS_REDIRECT);
		env.setProperty(GRASS_URL_KEY, "https://github.example.com/graphql");
		assertThatCode(() -> new ProdProfileValidator(auth("https://jandi.example.com"), server(true), env))
				.doesNotThrowAnyException();
		// 지정하지 않으면 https인 기본값(https://api.github.com/graphql)을 쓴다
		assertThatCode(() -> new ProdProfileValidator(auth("https://jandi.example.com"), server(true),
				env(HTTPS_REDIRECT))).doesNotThrowAnyException();
	}

	@Test
	void 세션_쿠키_Secure가_꺼져_있거나_비어_있으면_기동을_막는다() {
		assertThatThrownBy(() -> new ProdProfileValidator(auth("https://jandi.example.com"), server(false),
				env(HTTPS_REDIRECT))).isInstanceOf(IllegalStateException.class).hasMessageContaining("Secure");
		assertThatThrownBy(() -> new ProdProfileValidator(auth("https://jandi.example.com"), server(null),
				env(HTTPS_REDIRECT))).isInstanceOf(IllegalStateException.class).hasMessageContaining("Secure");
	}

	@Test
	void prod_프로필이_켜졌을_때만_검증이_돈다() {
		ApplicationContextRunner base = prodRunner("http://localhost:5173", false);

		// prod가 아니면 http·Secure 꺼짐이어도 빈이 없고 그대로 뜬다 (로컬 개발)
		base.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).doesNotHaveBean(ProdProfileValidator.class);
		});

		// prod면 http 주소에서 컨텍스트가 뜨지 않는다
		base.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
				.withPropertyValues(REDIRECT_KEY + "=" + HTTPS_REDIRECT, REDIS_SSL_KEY + "=true",
						MONGO_URI_KEY + "=" + MONGO_URI, DB_PASSWORD_KEY + "=" + DB_PASSWORD)
				.run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class);
				});
	}

	@Test
	void prod에서_모든_조건이_맞으면_컨텍스트가_뜬다() {
		prodRunner("https://jandi.example.com", true)
				.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
				.withPropertyValues(REDIRECT_KEY + "=" + HTTPS_REDIRECT, REDIS_SSL_KEY + "=true",
						MONGO_URI_KEY + "=" + MONGO_URI, DB_PASSWORD_KEY + "=" + DB_PASSWORD)
				.run(context -> assertThat(context).hasNotFailed().hasSingleBean(ProdProfileValidator.class));
	}

	@Test
	void prod에서_Redis_TLS가_꺼져_있으면_컨텍스트가_뜨지_않는다() {
		prodRunner("https://jandi.example.com", true)
				.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
				.withPropertyValues(REDIRECT_KEY + "=" + HTTPS_REDIRECT, REDIS_SSL_KEY + "=false",
						MONGO_URI_KEY + "=" + MONGO_URI, DB_PASSWORD_KEY + "=" + DB_PASSWORD)
				.run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure()).rootCause().isInstanceOf(IllegalStateException.class)
							.hasMessageContaining("REDIS_SSL");
				});
	}

	@Test
	void 로컬_프로필에서는_개발_기본값이어도_새_검증이_돌지_않는다() {
		prodRunner("http://localhost:5173", false)
				.withPropertyValues(REDIS_SSL_KEY + "=false", MONGO_URI_KEY + "=mongodb://localhost:27017/jandilog",
						DB_PASSWORD_KEY + "=jandilog")
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context).doesNotHaveBean(ProdProfileValidator.class);
				});
	}

	// ----- Redis TLS -----

	@ParameterizedTest
	@ValueSource(strings = {"", "false", "FALSE", "no", "1", "ssl"})
	void REDIS_SSL이_true가_아니면_기동을_막는다(String ssl) {
		MockEnvironment env = env(HTTPS_REDIRECT);
		env.setProperty(REDIS_SSL_KEY, ssl);

		assertThatThrownBy(() -> validate(env)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("REDIS_SSL");
	}

	@Test
	void REDIS_SSL_속성이_없으면_기동을_막는다() {
		MockEnvironment env = new MockEnvironment();
		env.setProperty(REDIRECT_KEY, HTTPS_REDIRECT);
		env.setProperty(MONGO_URI_KEY, MONGO_URI);
		env.setProperty(DB_PASSWORD_KEY, DB_PASSWORD);

		assertThatThrownBy(() -> validate(env)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("REDIS_SSL");
	}

	@ParameterizedTest
	@ValueSource(strings = {"true", "TRUE", " true "})
	void REDIS_SSL이_true면_통과한다(String ssl) {
		MockEnvironment env = env(HTTPS_REDIRECT);
		env.setProperty(REDIS_SSL_KEY, ssl);

		assertThatCode(() -> validate(env)).doesNotThrowAnyException();
	}

	// ----- MongoDB TLS -----

	@ParameterizedTest
	@ValueSource(strings = {"", "   ", "mongodb://localhost:27017/jandilog",
			"mongodb://app:Mongo-Pw-7731@db.example.com:27017/jandilog?retryWrites=true",
			"mongodb://app:Mongo-Pw-7731@db.example.com/jandilog?tls=false",
			"mongodb://app:Mongo-Pw-7731@db.example.com/jandilog?ssl=false",
			"mongodb+srv://app:Mongo-Pw-7731@cluster0.example.mongodb.net/jandilog?tls=false",
			"mongodb://app:Mongo-Pw-7731@db.example.com/jandilog?tls=true&ssl=false",
			"mongodb://app:Mongo-Pw-7731@db.example.com/jandilog?tlsx=true",
			"http://app:Mongo-Pw-7731@db.example.com/jandilog?tls=true"})
	void MongoDB_URI가_TLS를_쓰지_않으면_기동을_막는다(String uri) {
		MockEnvironment env = env(HTTPS_REDIRECT);
		env.setProperty(MONGO_URI_KEY, uri);

		// 메시지에 URI(비밀번호 포함)가 실리지 않는다
		assertThatThrownBy(() -> validate(env)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("MONGODB_URI").hasMessageNotContaining("Mongo-Pw-7731");
	}

	@Test
	void MongoDB_URI_속성이_없으면_기동을_막는다() {
		MockEnvironment env = new MockEnvironment();
		env.setProperty(REDIRECT_KEY, HTTPS_REDIRECT);
		env.setProperty(REDIS_SSL_KEY, "true");
		env.setProperty(DB_PASSWORD_KEY, DB_PASSWORD);

		assertThatThrownBy(() -> validate(env)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("MONGODB_URI");
	}

	@ParameterizedTest
	@ValueSource(strings = {"mongodb+srv://app:Mongo-Pw-7731@cluster0.example.mongodb.net/jandilog",
			"mongodb+srv://app:Mongo-Pw-7731@cluster0.example.mongodb.net/jandilog?retryWrites=true&w=majority",
			"MONGODB+SRV://cluster0.example.mongodb.net/jandilog",
			"mongodb://app:Mongo-Pw-7731@db.example.com:27017/jandilog?tls=true",
			"mongodb://app:Mongo-Pw-7731@db.example.com:27017/jandilog?retryWrites=true&TLS=TRUE",
			"mongodb://app:Mongo-Pw-7731@db.example.com:27017/jandilog?ssl=true",
			"mongodb://a:b@h1:27017,h2:27017/jandilog?replicaSet=rs0&tls=true&authSource=admin"})
	void MongoDB_URI가_TLS를_쓰면_통과한다(String uri) {
		MockEnvironment env = env(HTTPS_REDIRECT);
		env.setProperty(MONGO_URI_KEY, uri);

		assertThatCode(() -> validate(env)).doesNotThrowAnyException();
	}

	// ----- DB 비밀번호 -----

	@ParameterizedTest
	@ValueSource(strings = {"", "   ", "jandilog", "JANDILOG", " jandilog ", "short", "elevenchars"})
	void DB_비밀번호가_비었거나_개발_기본값이거나_짧으면_기동을_막는다(String password) {
		MockEnvironment env = env(HTTPS_REDIRECT);
		env.setProperty(DB_PASSWORD_KEY, password);

		// 메시지에 비밀번호가 실리지 않는다
		assertThatThrownBy(() -> validate(env)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("DB_PASSWORD").hasMessageNotContaining("elevenchars");
	}

	@Test
	void DB_비밀번호는_12자부터_통과한다() {
		assertThat(ProdProfileValidator.MIN_DB_PASSWORD_LENGTH).isEqualTo(12);

		MockEnvironment twelve = env(HTTPS_REDIRECT);
		twelve.setProperty(DB_PASSWORD_KEY, "abcdefghijkl");
		assertThatCode(() -> validate(twelve)).doesNotThrowAnyException();

		MockEnvironment eleven = env(HTTPS_REDIRECT);
		eleven.setProperty(DB_PASSWORD_KEY, "abcdefghijk");
		assertThatThrownBy(() -> validate(eleven)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void DB_비밀번호_속성이_없으면_기동을_막는다() {
		MockEnvironment env = new MockEnvironment();
		env.setProperty(REDIRECT_KEY, HTTPS_REDIRECT);
		env.setProperty(REDIS_SSL_KEY, "true");
		env.setProperty(MONGO_URI_KEY, MONGO_URI);

		assertThatThrownBy(() -> validate(env)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("DB_PASSWORD");
	}

	@Test
	void 오류_메시지에_비밀값이_들어가지_않는다() {
		MockEnvironment env = env(HTTPS_REDIRECT);
		env.setProperty(REDIS_SSL_KEY, "false");
		env.setProperty(MONGO_URI_KEY, "mongodb://app:Mongo-Pw-7731@db.example.com:27017/jandilog");
		env.setProperty(DB_PASSWORD_KEY, "shortpw");

		// Redis → Mongo → DB 순으로 막히며, 어느 단계의 메시지에도 값이 없다
		assertThatThrownBy(() -> validate(env)).hasMessageNotContaining("Mongo-Pw-7731")
				.hasMessageNotContaining("shortpw");
		env.setProperty(REDIS_SSL_KEY, "true");
		assertThatThrownBy(() -> validate(env)).hasMessageContaining("MONGODB_URI")
				.hasMessageNotContaining("Mongo-Pw-7731").hasMessageNotContaining("shortpw");
		env.setProperty(MONGO_URI_KEY, MONGO_URI);
		assertThatThrownBy(() -> validate(env)).hasMessageContaining("DB_PASSWORD")
				.hasMessageNotContaining("shortpw");
	}

}
