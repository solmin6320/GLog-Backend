package com.jandilog.judgment.service;

import java.net.http.HttpClient;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jandilog.common.config.GrassProperties;
import com.jandilog.judgment.domain.HoldReason;
import com.jandilog.judgment.dto.GrassDay;

// GitHub GraphQL API로 일자별 기여를 조회한다 (기능명세서 5장, 서버 토큰은 GITHUB_API_TOKEN).
//
// 날짜 경계: GitHub 기여 캘린더의 일자는 UTC 기준이라 그대로 쓰면 KST 00:00~09:00 활동이 전날로 잡힌다.
// 그래서 KST 하루마다 [00:00:00, 23:59:59] KST 구간을 contributionsCollection(from, to)로 따로 묻고
// 그 구간의 totalContributions를 그날 기여 수로 쓴다. 요청 하나에 별칭(d0, d1, ...)으로 묶어 호출 횟수는 늘지 않는다.
// 전제: from/to가 초 단위 시각 필터로 동작한다. 실제 호출로 확인하기 전까지는 가정이므로(기능명세서 5장
// "날짜 경계 개발 초기 확인") 어긋나면 이 클래스의 buildQuery/parse만 바꾸면 된다
@Component
public class GitHubGrassClient implements GrassClient {

	private static final Logger log = LoggerFactory.getLogger(GitHubGrassClient.class);
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	// 별칭 수와 GitHub 쿼리 비용을 묶어 두는 상한. 평소 14일, 서버가 오래 꺼졌던 뒤 늦은 판정도 이 안에 든다
	static final int MAX_DAYS = 62;
	private static final String NOT_FOUND = "NOT_FOUND";

	private final RestClient restClient;
	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	public GitHubGrassClient(GrassProperties properties) {
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().connectTimeout(properties.timeout()).build());
		requestFactory.setReadTimeout(properties.timeout());
		// 토큰은 헤더로만 보낸다. 리다이렉트는 따라가지 않는다(JDK 기본)
		this.restClient = RestClient.builder()
				.baseUrl(properties.githubGraphqlUrl())
				.requestFactory(requestFactory)
				.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.githubApiToken())
				.defaultHeader(HttpHeaders.USER_AGENT, "jandilog")
				.defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
				.build();
	}

	@Override
	public List<GrassDay> fetchDailyContributions(String githubLogin, LocalDate from, LocalDate to) {
		if (githubLogin == null || githubLogin.isBlank()) {
			throw new GrassFetchException(HoldReason.IDENTITY_MISMATCH, "GitHub 아이디가 비었어요");
		}
		long span = ChronoUnit.DAYS.between(from, to) + 1;
		if (span < 1 || span > MAX_DAYS) {
			throw new IllegalArgumentException("조회 구간은 1~" + MAX_DAYS + "일이어야 해요");
		}

		String responseBody;
		try {
			responseBody = restClient.post()
					.contentType(MediaType.APPLICATION_JSON)
					.body(Map.of("query", buildQuery(from, to), "variables", Map.of("login", githubLogin)))
					.retrieve()
					.body(String.class);
		}
		catch (RestClientResponseException e) {
			// 401(토큰 문제)·403·429(레이트리밋)·5xx 모두 재시도 대상 오류로 본다
			log.warn("GitHub 잔디 조회 HTTP 오류 status={}", e.getStatusCode().value());
			throw new GrassFetchException(HoldReason.API_ERROR, "GitHub 응답 상태 " + e.getStatusCode().value(), e);
		}
		catch (RestClientException e) {
			log.warn("GitHub 잔디 조회 통신 실패 type={}", e.getClass().getSimpleName());
			throw new GrassFetchException(HoldReason.API_ERROR, "GitHub 통신에 실패했어요", e);
		}
		return parse(responseBody, from, to);
	}

	// KST 일자마다 별칭 하나. 날짜 문자열은 LocalDate에서 만들므로 외부 입력이 쿼리에 섞이지 않는다
	static String buildQuery(LocalDate from, LocalDate to) {
		StringBuilder query = new StringBuilder("query($login: String!) { user(login: $login) {");
		int index = 0;
		for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
			query.append(" d").append(index++)
					.append(": contributionsCollection(from: \"").append(dayStart(day))
					.append("\", to: \"").append(dayEnd(day))
					.append("\") { contributionCalendar { totalContributions } }");
		}
		return query.append(" } }").toString();
	}

	// KST 00:00:00 을 UTC 시각 문자열로
	static Instant dayStart(LocalDate day) {
		return day.atStartOfDay(KST).toInstant();
	}

	// KST 23:59:59 (to는 그 시각을 포함한다)
	static Instant dayEnd(LocalDate day) {
		return day.plusDays(1).atStartOfDay(KST).toInstant().minusSeconds(1);
	}

	// 응답을 일자별 기여 수로 바꾼다. GraphQL 오류는 200 응답 안의 errors로 오기도 한다
	static List<GrassDay> parse(String body, LocalDate from, LocalDate to) {
		if (body == null || body.isBlank()) {
			throw new GrassFetchException(HoldReason.API_ERROR, "GitHub 응답이 비었어요");
		}
		JsonNode root;
		try {
			root = OBJECT_MAPPER.readTree(body);
		}
		catch (JsonProcessingException e) {
			throw new GrassFetchException(HoldReason.API_ERROR, "GitHub 응답을 읽지 못했어요", e);
		}

		JsonNode errors = root.path("errors");
		if (errors.isArray() && !errors.isEmpty()) {
			// 없는 사용자는 재시도해도 풀리지 않는다. 레이트리밋(RATE_LIMITED) 등 나머지는 재시도 대상
			boolean notFound = false;
			for (JsonNode error : errors) {
				if (NOT_FOUND.equals(error.path("type").asText())) {
					notFound = true;
				}
			}
			if (notFound) {
				throw new GrassFetchException(HoldReason.IDENTITY_MISMATCH, "GitHub에서 사용자를 찾지 못했어요");
			}
			log.warn("GitHub 잔디 조회 GraphQL 오류 type={}", errors.get(0).path("type").asText("unknown"));
			throw new GrassFetchException(HoldReason.API_ERROR, "GitHub가 오류를 돌려줬어요");
		}

		JsonNode user = root.path("data").path("user");
		if (!user.isObject()) {
			throw new GrassFetchException(HoldReason.API_ERROR, "GitHub 응답에 사용자 정보가 없어요");
		}
		List<GrassDay> days = new ArrayList<>();
		int index = 0;
		for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
			JsonNode total = user.path("d" + index++).path("contributionCalendar").path("totalContributions");
			if (!total.isIntegralNumber() || total.asLong() < 0 || total.asLong() > Integer.MAX_VALUE) {
				throw new GrassFetchException(HoldReason.API_ERROR, "GitHub 응답에 " + day + " 기여 수가 없어요");
			}
			days.add(new GrassDay(day, total.asInt()));
		}
		return days;
	}

}
