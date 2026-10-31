package com.jandilog.judgment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.jandilog.judgment.domain.HoldReason;
import com.jandilog.judgment.service.GitHubGrassClient.DayRange;

// 프로필 잔디 전체 이력: 가입일 해석, 연도별 상·하반기 구간, 별칭 쿼리, 구간별 응답 모으기
class GitHubGrassClientHistoryQueryTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 10, 31);

	private static GrassFetchException failure(Runnable call) {
		Throwable thrown = catchThrowable(call::run);
		assertThat(thrown).isInstanceOf(GrassFetchException.class);
		return (GrassFetchException) thrown;
	}

	// ---------- 가입일 ----------

	@Test
	void 가입일은_createdAt의_UTC_일자다() {
		String body = "{\"data\":{\"user\":{\"databaseId\":7,\"createdAt\":\"2019-06-14T23:30:00Z\"}}}";

		assertThat(GitHubGrassClient.parseJoinedDate(body, 7L, TODAY)).isEqualTo(LocalDate.of(2019, 6, 14));
		assertThat(GitHubGrassClient.parseJoinedDate(body, null, TODAY)).isEqualTo(LocalDate.of(2019, 6, 14));
	}

	@Test
	void 가입일이_오늘보다_뒤면_오늘로_본다() {
		String body = "{\"data\":{\"user\":{\"databaseId\":7,\"createdAt\":\"2026-11-02T00:00:00Z\"}}}";

		assertThat(GitHubGrassClient.parseJoinedDate(body, 7L, TODAY)).isEqualTo(TODAY);
	}

	@Test
	void 가입일_조회에서_다른_계정이면_IDENTITY_MISMATCH() {
		String body = "{\"data\":{\"user\":{\"databaseId\":8,\"createdAt\":\"2019-06-14T00:00:00Z\"}}}";

		assertThat(failure(() -> GitHubGrassClient.parseJoinedDate(body, 7L, TODAY)).getReason())
				.isEqualTo(HoldReason.IDENTITY_MISMATCH);
	}

	@Test
	void 가입일_조회에서_사용자가_없으면_IDENTITY_MISMATCH() {
		String body = "{\"data\":{\"user\":null},\"errors\":[{\"type\":\"NOT_FOUND\",\"message\":\"x\"}]}";

		assertThat(failure(() -> GitHubGrassClient.parseJoinedDate(body, 7L, TODAY)).getReason())
				.isEqualTo(HoldReason.IDENTITY_MISMATCH);
	}

	@ParameterizedTest
	@ValueSource(strings = { "{\"data\":{\"user\":{\"databaseId\":7}}}",
			"{\"data\":{\"user\":{\"databaseId\":7,\"createdAt\":\"어제\"}}}",
			"{\"data\":{\"user\":{\"databaseId\":7,\"createdAt\":\"2019-06-14\"}}}", "" })
	void 가입일을_읽지_못하면_API_ERROR(String body) {
		assertThat(failure(() -> GitHubGrassClient.parseJoinedDate(body, 7L, TODAY)).getReason())
				.isEqualTo(HoldReason.API_ERROR);
	}

	// ---------- 상·하반기 구간 ----------

	@Test
	void 가입한_해는_가입일부터_자르고_올해는_오늘까지_자른다() {
		LocalDate start = LocalDate.of(2019, 6, 14);

		assertThat(GitHubGrassClient.halvesOf(2019, start, TODAY)).containsExactly(
				new DayRange("a", LocalDate.of(2019, 6, 14), LocalDate.of(2019, 6, 30)),
				new DayRange("b", LocalDate.of(2019, 7, 1), LocalDate.of(2019, 12, 31)));
		assertThat(GitHubGrassClient.halvesOf(2023, start, TODAY)).containsExactly(
				new DayRange("a", LocalDate.of(2023, 1, 1), LocalDate.of(2023, 6, 30)),
				new DayRange("b", LocalDate.of(2023, 7, 1), LocalDate.of(2023, 12, 31)));
		assertThat(GitHubGrassClient.halvesOf(2026, start, TODAY)).containsExactly(
				new DayRange("a", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30)),
				new DayRange("b", LocalDate.of(2026, 7, 1), TODAY));
	}

	@Test
	void 하반기에_가입했거나_상반기가_오늘이면_한_구간만_묻는다() {
		assertThat(GitHubGrassClient.halvesOf(2019, LocalDate.of(2019, 9, 1), TODAY))
				.containsExactly(new DayRange("b", LocalDate.of(2019, 9, 1), LocalDate.of(2019, 12, 31)));
		LocalDate march = LocalDate.of(2026, 3, 10);
		assertThat(GitHubGrassClient.halvesOf(2026, LocalDate.of(2020, 1, 1), march))
				.containsExactly(new DayRange("a", LocalDate.of(2026, 1, 1), march));
		assertThat(GitHubGrassClient.halvesOf(2026, TODAY, TODAY)).containsExactly(new DayRange("b", TODAY, TODAY));
	}

	@ParameterizedTest
	@ValueSource(ints = { 2008, 2023, 2024, 2025, 2028 })
	void 구간은_반년을_넘지_않고_빈틈없이_이어진다(int year) {
		List<DayRange> ranges = GitHubGrassClient.halvesOf(year, LocalDate.of(2000, 1, 1), LocalDate.of(2100, 1, 1));

		assertThat(ranges).hasSize(2);
		assertThat(ranges.get(0).from()).isEqualTo(LocalDate.of(year, 1, 1));
		assertThat(ranges.get(1).from()).isEqualTo(ranges.get(0).to().plusDays(1));
		assertThat(ranges.get(1).to()).isEqualTo(LocalDate.of(year, 12, 31));
		for (DayRange range : ranges) {
			assertThat(ChronoUnit.DAYS.between(range.from(), range.to()) + 1).isLessThanOrEqualTo(184);
		}
	}

	// ---------- 쿼리 ----------

	@Test
	void 연도_쿼리는_구간마다_별칭을_붙이고_아이디는_변수로만_보낸다() {
		String query = GitHubGrassClient.buildYearQuery(GitHubGrassClient.halvesOf(2026, LocalDate.of(2019, 6, 14),
				TODAY));

		assertThat(query).startsWith("query($login: String!) { user(login: $login) { databaseId");
		assertThat(query).contains(
				"a: contributionsCollection(from: \"2026-01-01T00:00:00Z\", to: \"2026-06-30T23:59:59Z\")");
		assertThat(query).contains(
				"b: contributionsCollection(from: \"2026-07-01T00:00:00Z\", to: \"2026-10-31T23:59:59Z\")");
		assertThat(query).contains("contributionDays { date contributionCount }");
		assertThat(query).endsWith("} } } } } }");
	}

	@Test
	void 가입일_쿼리는_숫자_id와_가입_시각만_묻는다() {
		assertThat(GitHubGrassClient.JOINED_QUERY)
				.isEqualTo("query($login: String!) { user(login: $login) { databaseId createdAt } }");
	}

	// ---------- 응답 모으기 ----------

	private static String yearResponse(long databaseId, String aWeeks, String bWeeks) {
		return "{\"data\":{\"user\":{\"databaseId\":" + databaseId + ",\"a\":{\"contributionCalendar\":{\"weeks\":["
				+ aWeeks + "]}},\"b\":{\"contributionCalendar\":{\"weeks\":[" + bWeeks + "]}}}}}";
	}

	private static String week(String... entries) {
		return "{\"contributionDays\":[" + String.join(",", entries) + "]}";
	}

	@Test
	void 구간_밖_날짜는_다른_구간_값을_덮지_않는다() {
		List<DayRange> ranges = List.of(new DayRange("a", LocalDate.of(2026, 6, 29), LocalDate.of(2026, 6, 30)),
				new DayRange("b", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 2)));
		// a 달력이 7월 1일을 0으로 함께 돌려줘도 b의 5가 남는다
		String body = yearResponse(7,
				week(GitHubGrassClientQueryParseTest.entry("2026-06-29", "1"),
						GitHubGrassClientQueryParseTest.entry("2026-06-30", "2"),
						GitHubGrassClientQueryParseTest.entry("2026-07-01", "0")),
				week(GitHubGrassClientQueryParseTest.entry("2026-07-01", "5"),
						GitHubGrassClientQueryParseTest.entry("2026-07-02", "3")));
		Map<LocalDate, Integer> counts = new HashMap<>();

		GitHubGrassClient.collectHalves(body, 7L, ranges, counts);

		assertThat(counts).containsExactlyInAnyOrderEntriesOf(Map.of(LocalDate.of(2026, 6, 29), 1,
				LocalDate.of(2026, 6, 30), 2, LocalDate.of(2026, 7, 1), 5, LocalDate.of(2026, 7, 2), 3));
	}

	@Test
	void 별칭_달력이_없으면_API_ERROR() {
		List<DayRange> ranges = List.of(new DayRange("a", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1)));
		String body = "{\"data\":{\"user\":{\"databaseId\":7}}}";

		assertThat(failure(() -> GitHubGrassClient.collectHalves(body, 7L, ranges, new HashMap<>())).getReason())
				.isEqualTo(HoldReason.API_ERROR);
	}

	@Test
	void 연도_조회에서_다른_계정이면_IDENTITY_MISMATCH() {
		List<DayRange> ranges = List.of(new DayRange("a", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1)));
		String body = yearResponse(8, week(GitHubGrassClientQueryParseTest.entry("2026-01-01", "1")), "");

		assertThat(failure(() -> GitHubGrassClient.collectHalves(body, 7L, ranges, new HashMap<>())).getReason())
				.isEqualTo(HoldReason.IDENTITY_MISMATCH);
	}

	@Test
	void 기여_수가_음수면_API_ERROR() {
		List<DayRange> ranges = List.of(new DayRange("a", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1)));
		String body = yearResponse(7, week(GitHubGrassClientQueryParseTest.entry("2026-01-01", "-1")), "");

		assertThat(failure(() -> GitHubGrassClient.collectHalves(body, 7L, ranges, new HashMap<>())).getReason())
				.isEqualTo(HoldReason.API_ERROR);
	}

}
