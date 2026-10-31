package com.jandilog.judgment.service;

import java.net.http.HttpClient;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
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
// 날짜 경계(2026-10-12 실제 호출로 확인): contributionsCollection의 from/to는 초 단위 시각 필터가 아니라
// UTC 일자 단위로 올림된다(1초짜리 구간도 그날 전체, KST 하루 구간은 이틀 합). 그래서 시각 구간을 쓰지 않고
// 구간의 일자만 보내 contributionCalendar.contributionDays의 date·contributionCount를 그대로 읽는다.
// 이 일자는 프로필 잔디 칸과 같은 값이라 기능명세서 5장 "프로필 잔디와 판정 결과가 일치"를 만족한다.
// 한국시간 커밋은 +09:00 기준 일자로 잡히는 것을 확인했다. 요청 한 번으로 구간 전체를 묻는다.
// 프로필 전체 이력은 가입일을 먼저 묻고, 연도마다 요청 한 번(상·하반기 두 구간)으로 나눠 묻는다(구간 한도 1년).
// 전체 이력은 GitHubGrassHistoryClient 빈이 이 클래스를 따로 만들어 부른다(테스트가 GrassClient 빈만 바꿔도 깨지지 않게)
@Component
public class GitHubGrassClient implements GrassClient {

	private static final Logger log = LoggerFactory.getLogger(GitHubGrassClient.class);
	// GitHub 쿼리 비용을 묶어 두는 상한. 평소 14일, 서버가 오래 꺼졌던 뒤 늦은 판정도 이 안에 든다
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
	public List<GrassDay> fetchDailyContributions(Long githubId, String githubLogin, LocalDate from, LocalDate to) {
		if (githubLogin == null || githubLogin.isBlank()) {
			throw new GrassFetchException(HoldReason.IDENTITY_MISMATCH, "GitHub 아이디가 비었어요");
		}
		long span = ChronoUnit.DAYS.between(from, to) + 1;
		if (span < 1 || span > MAX_DAYS) {
			throw new IllegalArgumentException("조회 구간은 1~" + MAX_DAYS + "일이어야 해요");
		}
		return parse(post(buildQuery(from, to), githubLogin), githubId, from, to);
	}

	// 가입일 조회 한 번 + 가입 연도부터 올해까지 연도마다 한 번. 계약은 GrassHistoryClient와 같다
	public List<GrassDay> fetchAllDailyContributions(Long githubId, String githubLogin, LocalDate today) {
		if (githubLogin == null || githubLogin.isBlank()) {
			throw new GrassFetchException(HoldReason.IDENTITY_MISMATCH, "GitHub 아이디가 비었어요");
		}
		LocalDate start = parseJoinedDate(post(JOINED_QUERY, githubLogin), githubId, today);
		Map<LocalDate, Integer> counts = new HashMap<>();
		for (int year = start.getYear(); year <= today.getYear(); year++) {
			List<DayRange> ranges = halvesOf(year, start, today);
			collectHalves(post(buildYearQuery(ranges), githubLogin), githubId, ranges, counts);
		}
		return daysBetween(counts, start, today);
	}

	private String post(String query, String githubLogin) {
		try {
			return restClient.post()
					.contentType(MediaType.APPLICATION_JSON)
					.body(Map.of("query", query, "variables", Map.of("login", githubLogin)))
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
	}

	// 구간 일자를 UTC 일자 경계로 보낸다. 날짜 문자열은 LocalDate에서 만들므로 외부 입력이 쿼리에 섞이지 않는다
	static String buildQuery(LocalDate from, LocalDate to) {
		return "query($login: String!) { user(login: $login) { databaseId contributionsCollection(from: \"" + from
				+ "T00:00:00Z\", to: \"" + to + "T23:59:59Z\") { contributionCalendar { weeks { contributionDays {"
				+ " date contributionCount } } } } } }";
	}

	static List<GrassDay> parse(String body, LocalDate from, LocalDate to) {
		return parse(body, null, from, to);
	}

	// 응답을 일자별 기여 수로 바꾼다. GraphQL 오류는 200 응답 안의 errors로 오기도 한다.
	// githubId가 있으면 그 아이디의 현재 주인이 같은 계정인지 확인한다(아이디를 바꾼 뒤 남이 가져간 경우)
	static List<GrassDay> parse(String body, Long githubId, LocalDate from, LocalDate to) {
		JsonNode weeks = userOf(body, githubId).path("contributionsCollection").path("contributionCalendar")
				.path("weeks");
		if (!weeks.isArray()) {
			throw new GrassFetchException(HoldReason.API_ERROR, "GitHub 응답에 잔디 달력이 없어요");
		}
		Map<LocalDate, Integer> counts = new HashMap<>();
		for (JsonNode week : weeks) {
			for (JsonNode entry : week.path("contributionDays")) {
				counts.put(parseDate(entry.path("date")), parseCount(entry.path("contributionCount")));
			}
		}
		return daysBetween(counts, from, to);
	}

	// 응답의 user 객체. GraphQL 오류·사용자 없음·다른 계정이면 GrassFetchException
	private static JsonNode userOf(String body, Long githubId) {
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
		JsonNode databaseId = user.path("databaseId");
		if (githubId != null && databaseId.isIntegralNumber() && databaseId.asLong() != githubId) {
			throw new GrassFetchException(HoldReason.IDENTITY_MISMATCH, "GitHub 아이디의 주인이 회원 계정과 달라요");
		}
		return user;
	}

	// from~to를 빠짐없이 날짜 오름차순으로. 빠진 날이 있으면 0칸으로 오해하지 않게 실패로 본다
	private static List<GrassDay> daysBetween(Map<LocalDate, Integer> counts, LocalDate from, LocalDate to) {
		List<GrassDay> days = new ArrayList<>();
		for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
			Integer count = counts.get(day);
			if (count == null) {
				throw new GrassFetchException(HoldReason.API_ERROR, "GitHub 응답에 " + day + " 기여 수가 없어요");
			}
			days.add(new GrassDay(day, count));
		}
		return days;
	}

	// 전체 이력 한 구간. alias는 쿼리의 별칭(a: 상반기, b: 하반기)
	record DayRange(String alias, LocalDate from, LocalDate to) {
	}

	static final String JOINED_QUERY = "query($login: String!) { user(login: $login) { databaseId createdAt } }";

	// 가입일(UTC 일자). 오늘보다 뒤면 오늘로 본다
	static LocalDate parseJoinedDate(String body, Long githubId, LocalDate today) {
		JsonNode createdAt = userOf(body, githubId).path("createdAt");
		LocalDate joined;
		try {
			joined = OffsetDateTime.parse(createdAt.asText("")).toLocalDate();
		}
		catch (DateTimeParseException e) {
			throw new GrassFetchException(HoldReason.API_ERROR, "GitHub 응답의 가입일을 읽지 못했어요");
		}
		return joined.isAfter(today) ? today : joined;
	}

	// year의 상반기(1~6월)·하반기(7~12월) 중 start~today에 걸치는 구간. 반년씩 나눠 1년 한도에 닿지 않게 한다
	static List<DayRange> halvesOf(int year, LocalDate start, LocalDate today) {
		List<DayRange> ranges = new ArrayList<>();
		addClipped(ranges, "a", LocalDate.of(year, 1, 1), LocalDate.of(year, 6, 30), start, today);
		addClipped(ranges, "b", LocalDate.of(year, 7, 1), LocalDate.of(year, 12, 31), start, today);
		return ranges;
	}

	private static void addClipped(List<DayRange> ranges, String alias, LocalDate from, LocalDate to,
			LocalDate start, LocalDate today) {
		LocalDate clippedFrom = from.isBefore(start) ? start : from;
		LocalDate clippedTo = to.isAfter(today) ? today : to;
		if (!clippedFrom.isAfter(clippedTo)) {
			ranges.add(new DayRange(alias, clippedFrom, clippedTo));
		}
	}

	// 구간마다 별칭을 붙인 contributionsCollection. 날짜 문자열은 LocalDate에서 만든다
	static String buildYearQuery(List<DayRange> ranges) {
		StringBuilder query = new StringBuilder("query($login: String!) { user(login: $login) { databaseId");
		for (DayRange range : ranges) {
			query.append(' ').append(range.alias()).append(": contributionsCollection(from: \"").append(range.from())
					.append("T00:00:00Z\", to: \"").append(range.to())
					.append("T23:59:59Z\") { contributionCalendar { weeks { contributionDays {")
					.append(" date contributionCount } } } }");
		}
		return query.append(" } }").toString();
	}

	// 구간마다 그 구간 안의 일자만 담는다. 달력이 구간 밖 날을 함께 돌려줘도 다른 구간 값을 덮지 않게 한다
	static void collectHalves(String body, Long githubId, List<DayRange> ranges, Map<LocalDate, Integer> counts) {
		JsonNode user = userOf(body, githubId);
		for (DayRange range : ranges) {
			JsonNode weeks = user.path(range.alias()).path("contributionCalendar").path("weeks");
			if (!weeks.isArray()) {
				throw new GrassFetchException(HoldReason.API_ERROR, "GitHub 응답에 잔디 달력이 없어요");
			}
			for (JsonNode week : weeks) {
				for (JsonNode entry : week.path("contributionDays")) {
					LocalDate date = parseDate(entry.path("date"));
					if (!date.isBefore(range.from()) && !date.isAfter(range.to())) {
						counts.put(date, parseCount(entry.path("contributionCount")));
					}
				}
			}
		}
	}

	private static LocalDate parseDate(JsonNode node) {
		try {
			return LocalDate.parse(node.asText(""));
		}
		catch (DateTimeParseException e) {
			// 원인 예외 메시지에 응답 문자열이 담기므로 붙이지 않는다
			throw new GrassFetchException(HoldReason.API_ERROR, "GitHub 응답의 일자를 읽지 못했어요");
		}
	}

	private static int parseCount(JsonNode node) {
		if (!node.isIntegralNumber() || node.asLong() < 0 || node.asLong() > Integer.MAX_VALUE) {
			throw new GrassFetchException(HoldReason.API_ERROR, "GitHub 응답의 기여 수가 올바르지 않아요");
		}
		return node.asInt();
	}

}
