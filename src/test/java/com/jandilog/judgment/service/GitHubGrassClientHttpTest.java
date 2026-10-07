package com.jandilog.judgment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jandilog.common.config.GrassProperties;
import com.jandilog.judgment.domain.HoldReason;
import com.jandilog.judgment.dto.GrassDay;
import com.sun.net.httpserver.HttpServer;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;

// 로컬 HttpServer를 GitHub 대신 세워 실제 HTTP 호출 경로를 확인한다 (jandilog.grass.github-graphql-url 대체)
class GitHubGrassClientHttpTest {

	private static final String TOKEN = "ghp_unitTestOnlyToken0123456789AbCdEf";
	private static final LocalDate FROM = LocalDate.of(2026, 9, 28);
	private static final LocalDate TO = FROM.plusDays(6);
	private static final ObjectMapper JSON = new ObjectMapper();
	private static final Pattern WINDOW = Pattern
			.compile("d(\\d+): contributionsCollection\\(from: \"([^\"]+)\", to: \"([^\"]+)\"\\)");

	private record Recorded(String method, String path, String rawQuery, String authorization, String userAgent,
			String contentType, String body) {
	}

	private HttpServer server;
	private ExecutorService executor;
	private final List<Recorded> requests = new CopyOnWriteArrayList<>();
	private volatile int status = 200;
	private volatile String responseBody = GitHubGrassClientQueryParseTest.responseOf(0, 0, 0, 0, 0, 0, 0);
	private volatile long delayMillis;
	private volatile String redirectTo;
	private Logger clientLogger;
	private ListAppender<ILoggingEvent> logs;

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
		executor = Executors.newCachedThreadPool();
		server.setExecutor(executor);
		server.createContext("/graphql", exchange -> {
			byte[] requestBody = exchange.getRequestBody().readAllBytes();
			requests.add(new Recorded(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
					exchange.getRequestURI().getRawQuery(), exchange.getRequestHeaders().getFirst("Authorization"),
					exchange.getRequestHeaders().getFirst("User-Agent"),
					exchange.getRequestHeaders().getFirst("Content-Type"),
					new String(requestBody, StandardCharsets.UTF_8)));
			if (delayMillis > 0) {
				try {
					Thread.sleep(delayMillis);
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
			if (redirectTo != null) {
				exchange.getResponseHeaders().add("Location", redirectTo);
				exchange.sendResponseHeaders(302, -1);
				exchange.close();
				return;
			}
			byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(status, bytes.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		});
		server.start();

		clientLogger = (Logger) LoggerFactory.getLogger(GitHubGrassClient.class);
		logs = new ListAppender<>();
		logs.start();
		clientLogger.addAppender(logs);
	}

	@AfterEach
	void stopServer() {
		clientLogger.detachAppender(logs);
		server.stop(0);
		executor.shutdownNow();
	}

	private String url() {
		return "http://127.0.0.1:" + server.getAddress().getPort() + "/graphql";
	}

	private GitHubGrassClient client() {
		return client(Duration.ofSeconds(5));
	}

	private GitHubGrassClient client(Duration timeout) {
		return new GitHubGrassClient(new GrassProperties(TOKEN, url(), timeout));
	}

	private GrassFetchException failure(Runnable call) {
		Throwable thrown = catchThrowable(call::run);
		assertThat(thrown).isInstanceOf(GrassFetchException.class);
		return (GrassFetchException) thrown;
	}

	private void assertTokenAbsent(Throwable thrown) {
		for (Throwable t = thrown; t != null; t = t.getCause()) {
			assertThat(String.valueOf(t.getMessage())).doesNotContain(TOKEN);
		}
		for (ILoggingEvent event : logs.list) {
			assertThat(event.getFormattedMessage()).doesNotContain(TOKEN);
			if (event.getThrowableProxy() != null) {
				assertThat(ThrowableProxyUtil.asString(event.getThrowableProxy())).doesNotContain(TOKEN);
			}
		}
	}

	// ---------- 정상 호출 ----------

	@Test
	void 정상_응답을_일자별_기여로_돌려준다() {
		responseBody = GitHubGrassClientQueryParseTest.responseOf(0, 3, 0, 1, 0, 0, 12);

		List<GrassDay> days = client().fetchDailyContributions("octocat", FROM, TO);

		assertThat(days).extracting(GrassDay::count).containsExactly(0, 3, 0, 1, 0, 0, 12);
		assertThat(days.get(0).date()).isEqualTo(FROM);
		assertThat(days.get(6).date()).isEqualTo(TO);
		assertThat(days.get(1).hasGrass()).isTrue();
		assertThat(days.get(2).hasGrass()).isFalse();
	}

	@Test
	void 토큰은_Authorization_헤더로만_가고_본문과_주소에는_없다() {
		client().fetchDailyContributions("octocat", FROM, TO);

		assertThat(requests).hasSize(1);
		Recorded request = requests.get(0);
		assertThat(request.method()).isEqualTo("POST");
		assertThat(request.path()).isEqualTo("/graphql");
		assertThat(request.authorization()).isEqualTo("Bearer " + TOKEN);
		assertThat(request.userAgent()).isEqualTo("jandilog");
		assertThat(request.contentType()).startsWith("application/json");
		assertThat(request.body()).doesNotContain(TOKEN);
		assertThat(String.valueOf(request.rawQuery())).doesNotContain(TOKEN);
	}

	@Test
	void 로그인은_쿼리_문자열이_아니라_variables로만_간다() throws IOException {
		String hostile = "a\"}) { viewer { email } } #";

		client().fetchDailyContributions(hostile, FROM, TO);

		JsonNode payload = JSON.readTree(requests.get(0).body());
		assertThat(payload.path("variables").path("login").asText()).isEqualTo(hostile);
		assertThat(payload.path("query").asText()).doesNotContain("viewer").doesNotContain("email")
				.contains("user(login: $login)");
	}

	@Test
	void 요청_구간은_KST_하루씩_UTC로_바꿔서_보낸다() throws IOException {
		client().fetchDailyContributions("octocat", FROM, TO);

		String query = JSON.readTree(requests.get(0).body()).path("query").asText();
		List<Instant[]> windows = new ArrayList<>();
		Matcher matcher = WINDOW.matcher(query);
		while (matcher.find()) {
			windows.add(new Instant[] {Instant.parse(matcher.group(2)), Instant.parse(matcher.group(3))});
		}
		assertThat(windows).hasSize(7);
		assertThat(windows.get(0)[0]).isEqualTo(Instant.parse("2026-09-27T15:00:00Z"));
		assertThat(windows.get(0)[1]).isEqualTo(Instant.parse("2026-09-28T14:59:59Z"));
		assertThat(windows.get(6)[0]).isEqualTo(Instant.parse("2026-10-03T15:00:00Z"));
		assertThat(windows.get(6)[1]).isEqualTo(Instant.parse("2026-10-04T14:59:59Z"));
	}

	@Test
	void 호출은_구간_전체를_요청_한_번으로_묶는다() {
		client().fetchDailyContributions("octocat", FROM, TO);

		assertThat(requests).hasSize(1);
	}

	// ---------- 구간 상한 ----------

	@Test
	void 구간이_62일이면_허용하고_별칭_62개로_한_번에_조회한다() {
		LocalDate to = FROM.plusDays(61);
		int[] totals = new int[62];
		for (int i = 0; i < totals.length; i++) {
			totals[i] = i % 3;
		}
		responseBody = GitHubGrassClientQueryParseTest.responseOf(totals);

		List<GrassDay> days = client().fetchDailyContributions("octocat", FROM, to);

		assertThat(days).hasSize(62);
		assertThat(days.get(61).date()).isEqualTo(to);
		assertThat(requests).hasSize(1);
	}

	@Test
	void 구간이_63일이면_요청_없이_거부한다() {
		Throwable thrown = catchThrowable(() -> client().fetchDailyContributions("octocat", FROM, FROM.plusDays(62)));

		assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
		assertThat(requests).isEmpty();
	}

	@Test
	void 시작일이_종료일보다_늦으면_요청_없이_거부한다() {
		Throwable thrown = catchThrowable(() -> client().fetchDailyContributions("octocat", TO, FROM));

		assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
		assertThat(requests).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "   "})
	void 로그인이_비었으면_요청_없이_IDENTITY_MISMATCH다(String login) {
		GrassFetchException e = failure(() -> client().fetchDailyContributions(login, FROM, TO));

		assertThat(e.getReason()).isEqualTo(HoldReason.IDENTITY_MISMATCH);
		assertThat(requests).isEmpty();
	}

	@Test
	void 로그인이_null이어도_요청_없이_IDENTITY_MISMATCH다() {
		GrassFetchException e = failure(() -> client().fetchDailyContributions(null, FROM, TO));

		assertThat(e.getReason()).isEqualTo(HoldReason.IDENTITY_MISMATCH);
		assertThat(requests).isEmpty();
	}

	// ---------- 실패 분류 ----------

	@ParameterizedTest
	@ValueSource(ints = {400, 401, 403, 404, 429, 500, 502, 503})
	void HTTP_오류_상태는_모두_재시도_대상_API_ERROR이고_본문과_토큰을_메시지에_싣지_않는다(int httpStatus) {
		status = httpStatus;
		responseBody = "SECRET-BODY-0123456789";

		GrassFetchException e = failure(() -> client().fetchDailyContributions("octocat", FROM, TO));

		assertThat(e.getReason()).isEqualTo(HoldReason.API_ERROR);
		assertThat(e.isRetryable()).isTrue();
		assertThat(e.getMessage()).contains(String.valueOf(httpStatus)).doesNotContain("SECRET-BODY");
		assertTokenAbsent(e);
	}

	@Test
	void 응답_200의_NOT_FOUND는_IDENTITY_MISMATCH다() {
		responseBody = "{\"data\":{\"user\":null},\"errors\":[{\"type\":\"NOT_FOUND\",\"path\":[\"user\"],"
				+ "\"message\":\"Could not resolve to a User with the login of 'octocat'.\"}]}";

		GrassFetchException e = failure(() -> client().fetchDailyContributions("octocat", FROM, TO));

		assertThat(e.getReason()).isEqualTo(HoldReason.IDENTITY_MISMATCH);
		assertThat(e.isRetryable()).isFalse();
		assertTokenAbsent(e);
	}

	@Test
	void 응답_200의_RATE_LIMITED는_API_ERROR다() {
		responseBody = "{\"data\":null,\"errors\":[{\"type\":\"RATE_LIMITED\",\"message\":\"API rate limit exceeded\"}]}";

		GrassFetchException e = failure(() -> client().fetchDailyContributions("octocat", FROM, TO));

		assertThat(e.getReason()).isEqualTo(HoldReason.API_ERROR);
		assertTokenAbsent(e);
	}

	@ParameterizedTest
	@ValueSource(strings = {"<html>bad gateway</html>", "{}", "[]", "{\"data\":{\"user\":null}}", ""})
	void 응답_200이어도_형식이_이상하면_API_ERROR다(String body) {
		responseBody = body;

		GrassFetchException e = failure(() -> client().fetchDailyContributions("octocat", FROM, TO));

		assertThat(e.getReason()).isEqualTo(HoldReason.API_ERROR);
		assertTokenAbsent(e);
	}

	@Test
	void 연결할_수_없으면_API_ERROR다() {
		GitHubGrassClient client = client();
		server.stop(0);

		GrassFetchException e = failure(() -> client.fetchDailyContributions("octocat", FROM, TO));

		assertThat(e.getReason()).isEqualTo(HoldReason.API_ERROR);
		assertThat(e.isRetryable()).isTrue();
		assertTokenAbsent(e);
	}

	@Test
	void 응답이_제한_시간을_넘기면_API_ERROR다() {
		delayMillis = 1_500;
		GitHubGrassClient slowClient = client(Duration.ofMillis(300));

		GrassFetchException e = failure(() -> slowClient.fetchDailyContributions("octocat", FROM, TO));

		assertThat(e.getReason()).isEqualTo(HoldReason.API_ERROR);
		assertTokenAbsent(e);
	}

	@Test
	void 리다이렉트는_따라가지_않아서_토큰이_다른_주소로_새지_않는다() throws IOException {
		HttpServer other = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
		List<String> otherAuthorizations = new CopyOnWriteArrayList<>();
		other.createContext("/capture", exchange -> {
			otherAuthorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
			exchange.sendResponseHeaders(200, -1);
			exchange.close();
		});
		other.start();
		try {
			redirectTo = "http://127.0.0.1:" + other.getAddress().getPort() + "/capture";

			GrassFetchException e = failure(() -> client().fetchDailyContributions("octocat", FROM, TO));

			assertThat(e.getReason()).isEqualTo(HoldReason.API_ERROR);
			assertThat(otherAuthorizations).isEmpty();
			assertTokenAbsent(e);
		}
		finally {
			other.stop(0);
		}
	}

	@Test
	void 성공_경로에서도_토큰이_로그에_남지_않는다() {
		client().fetchDailyContributions("octocat", FROM, TO);

		assertThat(logs.list).allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain(TOKEN));
	}

	@Test
	void 실패_경로_로그에도_토큰이_없다() {
		status = 401;
		failure(() -> client().fetchDailyContributions("octocat", FROM, TO));
		status = 200;
		responseBody = "{\"errors\":[{\"type\":\"RATE_LIMITED\"}]}";
		failure(() -> client().fetchDailyContributions("octocat", FROM, TO));

		assertThat(logs.list).isNotEmpty();
		assertThat(logs.list).allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain(TOKEN));
	}

}
