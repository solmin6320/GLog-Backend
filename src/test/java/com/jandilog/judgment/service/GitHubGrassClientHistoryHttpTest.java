package com.jandilog.judgment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jandilog.common.config.GrassProperties;
import com.jandilog.judgment.domain.HoldReason;
import com.jandilog.judgment.dto.GrassDay;
import com.sun.net.httpserver.HttpServer;

// 로컬 HttpServer를 GitHub 대신 세워 프로필 잔디 전체 이력의 실제 호출 순서를 확인한다.
// 가입일 쿼리에는 createdAt을, 연도 쿼리에는 요청한 별칭 구간의 달력을 돌려준다(기여 수 = 일자 % 4)
class GitHubGrassClientHistoryHttpTest {

	private static final String TOKEN = "ghp_unitTestOnlyToken0123456789AbCdEf";
	private static final LocalDate TODAY = LocalDate.of(2026, 10, 31);
	private static final ObjectMapper JSON = new ObjectMapper();
	private static final Pattern RANGE = Pattern.compile(
			"(\\w+): contributionsCollection\\(from: \"(\\d{4}-\\d{2}-\\d{2})T00:00:00Z\", to: \"(\\d{4}-\\d{2}-\\d{2})T23:59:59Z\"\\)");

	private HttpServer server;
	private ExecutorService executor;
	private final List<String> queries = new CopyOnWriteArrayList<>();
	private volatile String createdAt = "2024-11-20T08:00:00Z";
	private volatile long databaseId = 7;
	private volatile LocalDate skipDay;
	private volatile int failYearStatus;

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
		executor = Executors.newCachedThreadPool();
		server.setExecutor(executor);
		server.createContext("/graphql", exchange -> {
			JsonNode request = JSON.readTree(exchange.getRequestBody().readAllBytes());
			String query = request.path("query").asText();
			queries.add(query);
			int status = 200;
			String body;
			if (query.contains("createdAt")) {
				body = "{\"data\":{\"user\":{\"databaseId\":" + databaseId + ",\"createdAt\":\"" + createdAt + "\"}}}";
			}
			else if (failYearStatus != 0) {
				status = failYearStatus;
				body = "{}";
			}
			else {
				body = yearBody(query);
			}
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(status, bytes.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		});
		server.start();
	}

	@AfterEach
	void stopServer() {
		server.stop(0);
		executor.shutdownNow();
	}

	private String yearBody(String query) {
		StringBuilder user = new StringBuilder("{\"databaseId\":" + databaseId);
		Matcher matcher = RANGE.matcher(query);
		while (matcher.find()) {
			LocalDate from = LocalDate.parse(matcher.group(2));
			LocalDate to = LocalDate.parse(matcher.group(3));
			StringBuilder days = new StringBuilder();
			for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
				if (day.equals(skipDay)) {
					continue;
				}
				days.append(days.isEmpty() ? "" : ",")
						.append(GitHubGrassClientQueryParseTest.entry(day.toString(), String.valueOf(countOf(day))));
			}
			user.append(",\"").append(matcher.group(1)).append("\":{\"contributionCalendar\":{\"weeks\":[")
					.append("{\"contributionDays\":[").append(days).append("]}]}}");
		}
		return "{\"data\":{\"user\":" + user.append('}') + "}}";
	}

	private static int countOf(LocalDate day) {
		return day.getDayOfMonth() % 4;
	}

	private GitHubGrassClient client() {
		return new GitHubGrassClient(new GrassProperties(TOKEN, "http://127.0.0.1:" + server.getAddress().getPort()
				+ "/graphql", Duration.ofSeconds(5)));
	}

	private GrassFetchException failure(Runnable call) {
		Throwable thrown = catchThrowable(call::run);
		assertThat(thrown).isInstanceOf(GrassFetchException.class);
		return (GrassFetchException) thrown;
	}

	@Test
	void 가입일을_먼저_묻고_가입_연도부터_올해까지_연도마다_한_번씩_묻는다() {
		List<GrassDay> days = client().fetchAllDailyContributions(7L, "octocat", TODAY);

		// 가입일 1번 + 2024·2025·2026 3번
		assertThat(queries).hasSize(4);
		assertThat(queries.get(0)).contains("createdAt");
		assertThat(queries.get(1)).contains("from: \"2024-11-20T00:00:00Z\"").doesNotContain("a: ");
		assertThat(queries.get(2)).contains("a: ").contains("b: ");
		assertThat(queries.get(3)).contains("to: \"2026-10-31T23:59:59Z\"");

		assertThat(days).hasSize((int) ChronoUnit.DAYS.between(LocalDate.of(2024, 11, 20), TODAY) + 1);
		assertThat(days.get(0).date()).isEqualTo(LocalDate.of(2024, 11, 20));
		assertThat(days.get(days.size() - 1).date()).isEqualTo(TODAY);
		for (int i = 0; i < days.size(); i++) {
			GrassDay day = days.get(i);
			assertThat(day.date()).isEqualTo(LocalDate.of(2024, 11, 20).plusDays(i));
			assertThat(day.count()).isEqualTo(countOf(day.date()));
		}
	}

	@Test
	void 전체_이력_빈은_같은_방식으로_GitHub를_부른다() {
		GrassProperties properties = new GrassProperties(TOKEN, "http://127.0.0.1:" + server.getAddress().getPort()
				+ "/graphql", Duration.ofSeconds(5));

		List<GrassDay> days = new GitHubGrassHistoryClient(properties).fetchAllDailyContributions(7L, "octocat", TODAY);

		assertThat(days).isEqualTo(client().fetchAllDailyContributions(7L, "octocat", TODAY));
		assertThat(queries).hasSize(8);
	}

	@Test
	void 올해_가입했으면_가입일_조회와_올해_조회_두_번이다() {
		createdAt = "2026-10-30T15:00:00Z";

		List<GrassDay> days = client().fetchAllDailyContributions(7L, "octocat", TODAY);

		assertThat(queries).hasSize(2);
		assertThat(days).extracting(GrassDay::date).containsExactly(LocalDate.of(2026, 10, 30), TODAY);
	}

	@Test
	void 빠진_날이_있으면_API_ERROR이고_0칸으로_채우지_않는다() {
		skipDay = LocalDate.of(2025, 7, 1);

		assertThat(failure(() -> client().fetchAllDailyContributions(7L, "octocat", TODAY)).getReason())
				.isEqualTo(HoldReason.API_ERROR);
	}

	@Test
	void 다른_계정이면_가입일_조회에서_멈춘다() {
		databaseId = 8;

		assertThat(failure(() -> client().fetchAllDailyContributions(7L, "octocat", TODAY)).getReason())
				.isEqualTo(HoldReason.IDENTITY_MISMATCH);
		assertThat(queries).hasSize(1);
	}

	@Test
	void 연도_조회가_HTTP_오류면_API_ERROR() {
		failYearStatus = 502;

		assertThat(failure(() -> client().fetchAllDailyContributions(7L, "octocat", TODAY)).getReason())
				.isEqualTo(HoldReason.API_ERROR);
	}

	@Test
	void 아이디가_비었으면_GitHub를_부르지_않고_IDENTITY_MISMATCH() {
		assertThat(failure(() -> client().fetchAllDailyContributions(7L, " ", TODAY)).getReason())
				.isEqualTo(HoldReason.IDENTITY_MISMATCH);
		assertThat(queries).isEmpty();
	}

}
