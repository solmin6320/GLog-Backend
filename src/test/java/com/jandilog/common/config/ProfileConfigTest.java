package com.jandilog.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import com.zaxxer.hikari.HikariDataSource;

// application.yml(기본·로컬)과 application-prod.yml(운영)의 값을 실제 기동 없이 확인한다.
// MockEnvironment는 OS 환경변수와 .env를 읽지 않아서 "환경변수 누락"을 그대로 재현할 수 있다
class ProfileConfigTest {

	private static final YamlPropertySourceLoader LOADER = new YamlPropertySourceLoader();
	private static final String SECRET = "k3Jx9Qv7mW2pL8nR4tY6uB1cD5eF0gHaZsXoViNqMlPwKjTy";

	// prod를 앞에 둬서 기본 설정을 덮어쓴다 (프로필 파일이 우선)
	private static MockEnvironment environment(boolean prod, Map<String, String> variables) {
		MockEnvironment env = new MockEnvironment();
		variables.forEach(env::setProperty);
		try {
			if (prod) {
				for (PropertySource<?> source : LOADER.load("prod", new ClassPathResource("application-prod.yml"))) {
					env.getPropertySources().addLast(source);
				}
			}
			for (PropertySource<?> source : LOADER.load("base", new ClassPathResource("application.yml"))) {
				env.getPropertySources().addLast(source);
			}
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		return env;
	}

	private static Map<String, String> allProdVariables() {
		return Map.ofEntries(
				Map.entry("DB_HOST", "db.internal"), Map.entry("DB_PORT", "3306"), Map.entry("DB_NAME", "jandilog"),
				Map.entry("DB_USERNAME", "app"), Map.entry("DB_PASSWORD", "db-pass"),
				Map.entry("MONGODB_URI", "mongodb+srv://atlas.example/jandilog"),
				Map.entry("REDIS_HOST", "redis.example"), Map.entry("REDIS_PORT", "6379"),
				Map.entry("REDIS_PASSWORD", "redis-pass"), Map.entry("REDIS_SSL", "true"),
				Map.entry("BACKEND_BASE_URL", "https://api.example.com"),
				Map.entry("WEB_BASE_URL", "https://jandi.example.com"),
				Map.entry("GITHUB_CLIENT_ID", "id"), Map.entry("GITHUB_CLIENT_SECRET", "secret"),
				Map.entry("JWT_SECRET", SECRET));
	}

	private static Map<String, String> prodVariablesWithout(String missing) {
		Map<String, String> variables = new HashMap<>(allProdVariables());
		variables.remove(missing);
		return variables;
	}

	// ----- 기본(로컬) 프로필: 동작이 바뀌지 않았는지 -----

	@Test
	void 기본_프로필의_로컬_접속_기본값은_그대로다() {
		MockEnvironment env = environment(false, Map.of());

		assertThat(env.getProperty("spring.datasource.url")).startsWith("jdbc:mariadb://localhost:3306/jandilog");
		assertThat(env.getProperty("spring.datasource.username")).isEqualTo("jandilog");
		assertThat(env.getProperty("spring.datasource.password")).isEqualTo("jandilog");
		assertThat(env.getProperty("spring.data.redis.host")).isEqualTo("localhost");
		assertThat(env.getProperty("spring.data.redis.ssl.enabled")).isEqualTo("false");
		assertThat(env.getProperty("spring.data.mongodb.uri")).isEqualTo("mongodb://localhost:27017/jandilog");
		assertThat(env.getProperty("jandilog.auth.web-base-url")).isEqualTo("http://localhost:5173");
		assertThat(env.getProperty("spring.security.oauth2.client.registration.github.redirect-uri"))
				.isEqualTo("http://localhost:8080/login/oauth2/code/{registrationId}");
	}

	@Test
	void 기본_프로필은_introspection_설정을_건드리지_않는다() {
		MockEnvironment env = environment(false, Map.of());

		assertThat(env.getProperty("spring.graphql.schema.introspection.enabled")).isNull();
		assertThat(env.getProperty("spring.graphql.graphiql.enabled")).isNull();
	}

	@Test
	void JWT_만료_기본값은_720분_12시간이다() {
		assertThat(environment(false, Map.of()).getProperty("jandilog.auth.jwt.expires-minutes")).isEqualTo("720");
		assertThat(environment(true, allProdVariables()).getProperty("jandilog.auth.jwt.expires-minutes"))
				.isEqualTo("720");
	}

	@Test
	void JWT_만료는_환경변수로_바꿀_수_있다() {
		assertThat(environment(false, Map.of("JWT_EXPIRES_MINUTES", "60"))
				.getProperty("jandilog.auth.jwt.expires-minutes")).isEqualTo("60");
		Map<String, String> prod = new HashMap<>(allProdVariables());
		prod.put("JWT_EXPIRES_MINUTES", "1440");
		assertThat(environment(true, prod).getProperty("jandilog.auth.jwt.expires-minutes")).isEqualTo("1440");
	}

	@Test
	void 기본_만료로_발급한_토큰은_12시간_뒤에_끝나고_범위_검증은_그대로다() {
		long minutes = Long.parseLong(environment(false, Map.of()).getProperty("jandilog.auth.jwt.expires-minutes"));
		AuthProperties.Jwt jwt = new AuthProperties.Jwt(SECRET, "jandilog", minutes);

		assertThat(Duration.ofMinutes(jwt.expiresMinutes())).isEqualTo(Duration.ofHours(12));
		assertThatThrownBy(() -> new AuthProperties.Jwt(SECRET, "jandilog", 0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AuthProperties.Jwt(SECRET, "jandilog", 43_201))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void JWT_키는_어느_프로필에도_기본값이_없다() {
		MockEnvironment local = environment(false, Map.of());
		MockEnvironment prod = environment(true, prodVariablesWithout("JWT_SECRET"));

		assertThatThrownBy(() -> local.getProperty("jandilog.auth.jwt.secret"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> prod.getProperty("jandilog.auth.jwt.secret"))
				.isInstanceOf(IllegalArgumentException.class);
	}

	// ----- 첫 관리자 지정(ADMIN_GITHUB_IDS) -----

	@Test
	void ADMIN_GITHUB_IDS_기본값은_비어_있고_환경변수로_받는다() {
		assertThat(environment(false, Map.of()).getProperty("jandilog.admin.github-ids")).isEmpty();
		assertThat(environment(false, Map.of("ADMIN_GITHUB_IDS", "11,22")).getProperty("jandilog.admin.github-ids"))
				.isEqualTo("11,22");
	}

	@Test
	void prod에서도_ADMIN_GITHUB_IDS는_선택값이라_없어도_읽을_수_있다() {
		assertThat(environment(true, allProdVariables()).getProperty("jandilog.admin.github-ids")).isEmpty();

		Map<String, String> variables = new HashMap<>(allProdVariables());
		variables.put("ADMIN_GITHUB_IDS", "12345678");
		assertThat(environment(true, variables).getProperty("jandilog.admin.github-ids")).isEqualTo("12345678");
	}

	// ----- 새 기본값 -----

	@Test
	void 세션_쿠키_Secure_기본값은_true이고_환경변수로_낮출_수_있다() {
		assertThat(environment(false, Map.of()).getProperty("server.servlet.session.cookie.secure")).isEqualTo("true");
		assertThat(environment(false, Map.of("SESSION_COOKIE_SECURE", "false"))
				.getProperty("server.servlet.session.cookie.secure")).isEqualTo("false");
	}

	@Test
	void 앱_딥링크_기본값은_고유_스킴이고_환경변수로_바꿀_수_있다() {
		assertThat(environment(false, Map.of()).getProperty("jandilog.auth.app-deep-link"))
				.isEqualTo("com.jandilog.app://auth");
		assertThat(environment(false, Map.of("APP_DEEP_LINK", "kr.club.jandi://auth"))
				.getProperty("jandilog.auth.app-deep-link")).isEqualTo("kr.club.jandi://auth");
	}

	@Test
	void 기본_딥링크는_AuthProperties_검증을_통과한다() {
		String deepLink = environment(false, Map.of()).getProperty("jandilog.auth.app-deep-link");

		assertThat(new AuthProperties("https://jandi.example.com", deepLink,
				new AuthProperties.Jwt(SECRET, "jandilog", 60)).appDeepLink()).isEqualTo("com.jandilog.app://auth");
	}

	// ----- prod 프로필 -----

	@ParameterizedTest
	@CsvSource({
			"spring.datasource.url, DB_HOST",
			"spring.datasource.url, DB_PORT",
			"spring.datasource.url, DB_NAME",
			"spring.datasource.username, DB_USERNAME",
			"spring.datasource.password, DB_PASSWORD",
			"spring.data.mongodb.uri, MONGODB_URI",
			"spring.data.redis.host, REDIS_HOST",
			"spring.data.redis.port, REDIS_PORT",
			"spring.data.redis.password, REDIS_PASSWORD",
			"spring.data.redis.ssl.enabled, REDIS_SSL",
			"spring.security.oauth2.client.registration.github.redirect-uri, BACKEND_BASE_URL",
			"jandilog.auth.web-base-url, WEB_BASE_URL",
			"spring.security.oauth2.client.registration.github.client-id, GITHUB_CLIENT_ID",
			"spring.security.oauth2.client.registration.github.client-secret, GITHUB_CLIENT_SECRET"})
	void prod에서는_환경변수가_하나라도_빠지면_해당_설정을_읽을_수_없다(String property, String missing) {
		MockEnvironment env = environment(true, prodVariablesWithout(missing));

		assertThatThrownBy(() -> env.getProperty(property)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining(missing);
	}

	@Test
	void prod에서_환경변수를_모두_주면_그_값을_쓴다() {
		MockEnvironment env = environment(true, allProdVariables());

		assertThat(env.getProperty("spring.datasource.url")).startsWith("jdbc:mariadb://db.internal:3306/jandilog?");
		assertThat(env.getProperty("spring.datasource.password")).isEqualTo("db-pass");
		assertThat(env.getProperty("spring.data.mongodb.uri")).isEqualTo("mongodb+srv://atlas.example/jandilog");
		assertThat(env.getProperty("spring.data.redis.host")).isEqualTo("redis.example");
		assertThat(env.getProperty("spring.data.redis.ssl.enabled")).isEqualTo("true");
		assertThat(env.getProperty("jandilog.auth.web-base-url")).isEqualTo("https://jandi.example.com");
		assertThat(env.getProperty("spring.security.oauth2.client.registration.github.redirect-uri"))
				.isEqualTo("https://api.example.com/login/oauth2/code/{registrationId}");
	}

	@Test
	void prod는_introspection과_GraphiQL을_끈다() {
		MockEnvironment env = environment(true, allProdVariables());

		assertThat(env.getProperty("spring.graphql.schema.introspection.enabled")).isEqualTo("false");
		assertThat(env.getProperty("spring.graphql.graphiql.enabled")).isEqualTo("false");
	}

	@Test
	void prod는_세션_쿠키_Secure를_환경변수로_낮출_수_없다() {
		Map<String, String> variables = new HashMap<>(allProdVariables());
		variables.put("SESSION_COOKIE_SECURE", "false");

		assertThat(environment(true, variables).getProperty("server.servlet.session.cookie.secure")).isEqualTo("true");
	}

	@Test
	void prod는_actuator에서_health만_노출하고_상세는_숨긴다() {
		MockEnvironment env = environment(true, allProdVariables());

		assertThat(env.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health");
		assertThat(env.getProperty("management.endpoint.health.show-details")).isEqualTo("never");
		assertThat(env.getProperty("management.health.redis.enabled")).isEqualTo("false");
	}

	@Test
	void 기본_프로필도_actuator에서_health만_노출한다() {
		MockEnvironment env = environment(false, Map.of());

		assertThat(env.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health");
		assertThat(env.getProperty("management.endpoint.health.show-details")).isEqualTo("never");
	}

	// ----- 타임아웃·graceful shutdown (환경변수로 덮어쓸 수 있고, 기본값은 로컬·prod 같음) -----

	private static Duration duration(MockEnvironment env, String key) {
		return Binder.get(env).bind(key, Duration.class).get();
	}

	private static HikariDataSource bindHikari(MockEnvironment env) {
		return Binder.get(env).bind("spring.datasource.hikari", Bindable.of(HikariDataSource.class)).get();
	}

	@Test
	void DB_풀_타임아웃_기본값은_10초_연결_10초_응답_60초다() {
		for (MockEnvironment env : List.of(environment(false, Map.of()), environment(true, allProdVariables()))) {
			HikariDataSource hikari = bindHikari(env);

			assertThat(hikari.getConnectionTimeout()).isEqualTo(10_000);
			assertThat(hikari.getDataSourceProperties().getProperty("connectTimeout")).isEqualTo("10000");
			assertThat(hikari.getDataSourceProperties().getProperty("socketTimeout")).isEqualTo("60000");
		}
	}

	@Test
	void DB_풀_타임아웃은_환경변수로_바꿀_수_있다() {
		Map<String, String> variables = Map.of("DB_POOL_WAIT_TIMEOUT_MS", "3000", "DB_CONNECT_TIMEOUT_MS", "4000",
				"DB_SOCKET_TIMEOUT_MS", "5000");
		Map<String, String> prod = new HashMap<>(allProdVariables());
		prod.putAll(variables);

		for (MockEnvironment env : List.of(environment(false, variables), environment(true, prod))) {
			HikariDataSource hikari = bindHikari(env);

			assertThat(hikari.getConnectionTimeout()).isEqualTo(3_000);
			assertThat(hikari.getDataSourceProperties().getProperty("connectTimeout")).isEqualTo("4000");
			assertThat(hikari.getDataSourceProperties().getProperty("socketTimeout")).isEqualTo("5000");
		}
	}

	@Test
	void JDBC_응답_상한은_InnoDB_락_대기_기본값_50초보다_길다() {
		long socketTimeout = Long.parseLong(
				bindHikari(environment(false, Map.of())).getDataSourceProperties().getProperty("socketTimeout"));

		assertThat(socketTimeout).isGreaterThan(50_000);
	}

	@Test
	void Redis_타임아웃_기본값은_명령_5초_연결_5초이고_환경변수로_바꿀_수_있다() {
		for (MockEnvironment env : List.of(environment(false, Map.of()), environment(true, allProdVariables()))) {
			assertThat(duration(env, "spring.data.redis.timeout")).isEqualTo(Duration.ofSeconds(5));
			assertThat(duration(env, "spring.data.redis.connect-timeout"))
					.isEqualTo(Duration.ofSeconds(5));
		}
		Map<String, String> prod = new HashMap<>(allProdVariables());
		prod.put("REDIS_COMMAND_TIMEOUT", "2s");
		prod.put("REDIS_CONNECT_TIMEOUT", "3s");
		MockEnvironment env = environment(true, prod);

		assertThat(duration(env, "spring.data.redis.timeout")).isEqualTo(Duration.ofSeconds(2));
		assertThat(duration(env, "spring.data.redis.connect-timeout")).isEqualTo(Duration.ofSeconds(3));
	}

	@Test
	void Mongo_타임아웃_기본값과_환경변수_덮어쓰기() {
		MongoTimeoutProperties defaults = Binder.get(environment(true, allProdVariables()))
				.bind("jandilog.mongo", MongoTimeoutProperties.class).get();

		assertThat(defaults.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
		assertThat(defaults.socketTimeout()).isEqualTo(Duration.ofSeconds(30));
		assertThat(defaults.serverSelectionTimeout()).isEqualTo(Duration.ofSeconds(10));
		assertThat(defaults.poolWaitTimeout()).isEqualTo(Duration.ofSeconds(10));

		MongoTimeoutProperties custom = Binder.get(environment(false, Map.of("MONGO_CONNECT_TIMEOUT", "2s",
				"MONGO_SOCKET_TIMEOUT", "20s", "MONGO_SERVER_SELECTION_TIMEOUT", "4s", "MONGO_POOL_WAIT_TIMEOUT", "6s")))
				.bind("jandilog.mongo", MongoTimeoutProperties.class).get();

		assertThat(custom.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
		assertThat(custom.socketTimeout()).isEqualTo(Duration.ofSeconds(20));
		assertThat(custom.serverSelectionTimeout()).isEqualTo(Duration.ofSeconds(4));
		assertThat(custom.poolWaitTimeout()).isEqualTo(Duration.ofSeconds(6));
	}

	@Test
	void graceful_shutdown이_켜져_있고_대기_시간은_8초_기본이다() {
		for (MockEnvironment env : List.of(environment(false, Map.of()), environment(true, allProdVariables()))) {
			assertThat(env.getProperty("server.shutdown")).isEqualTo("graceful");
			assertThat(duration(env, "spring.lifecycle.timeout-per-shutdown-phase"))
					.isEqualTo(Duration.ofSeconds(8));
		}
		MockEnvironment custom = environment(false, Map.of("SHUTDOWN_TIMEOUT", "25s"));
		assertThat(duration(custom, "spring.lifecycle.timeout-per-shutdown-phase")).isEqualTo(Duration.ofSeconds(25));
	}

	@Test
	void prod_파일에는_환경변수_기본값_표기가_하나도_없다() throws IOException {
		String yaml = new String(new ClassPathResource("application-prod.yml").getInputStream().readAllBytes(),
				StandardCharsets.UTF_8);

		// ${NAME:default} 형태가 있으면 환경변수 누락이 조용히 넘어간다
		Matcher withDefault = Pattern.compile("\\$\\{[^}]*:[^}]*}").matcher(yaml);
		List<String> found = withDefault.results().map(java.util.regex.MatchResult::group).toList();

		assertThat(found).isEmpty();
	}

}
