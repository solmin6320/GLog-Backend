package com.jandilog.judgment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.jandilog.judgment.domain.HoldReason;
import com.jandilog.judgment.dto.GrassDay;

// GitHubGrassClient의 쿼리 생성(구간 일자 → UTC 일자 경계)과 달력 응답 해석. 네트워크 없이 package-private 메서드를 직접 부른다
class GitHubGrassClientQueryParseTest {

	private static final LocalDate FROM = LocalDate.of(2026, 9, 28);

	// from부터 하루씩 기여 수를 채운 달력 응답. 7일씩 주로 나눈다
	static String responseOf(LocalDate from, int... counts) {
		StringBuilder weeks = new StringBuilder();
		for (int i = 0; i < counts.length; i++) {
			if (i % 7 == 0) {
				weeks.append(i > 0 ? "]}," : "").append("{\"contributionDays\":[");
			}
			else {
				weeks.append(',');
			}
			weeks.append(entry(from.plusDays(i).toString(), String.valueOf(counts[i])));
		}
		if (counts.length > 0) {
			weeks.append("]}");
		}
		return calendarOf(weeks.toString());
	}

	static String entry(String date, String count) {
		return "{\"date\":\"" + date + "\",\"contributionCount\":" + count + "}";
	}

	// weeks 배열 안쪽을 그대로 넣는 달력 응답
	static String calendarOf(String weeksInner) {
		return "{\"data\":{\"user\":{\"contributionsCollection\":{\"contributionCalendar\":{\"weeks\":[" + weeksInner
				+ "]}}}}}";
	}

	private static GrassFetchException failureOf(String body, LocalDate from, LocalDate to) {
		Throwable thrown = catchThrowable(() -> GitHubGrassClient.parse(body, from, to));
		assertThat(thrown).isInstanceOf(GrassFetchException.class);
		return (GrassFetchException) thrown;
	}

	// ---------- 구간 일자 → UTC 일자 경계 ----------

	@Test
	void 하루_구간은_그날_UTC_0시부터_23시_59분_59초까지다() {
		String query = GitHubGrassClient.buildQuery(FROM, FROM);

		assertThat(query).contains("from: \"2026-09-28T00:00:00Z\", to: \"2026-09-28T23:59:59Z\"");
	}

	@Test
	void 일곱_날은_시작일_0시부터_종료일_23시_59분_59초까지_한_구간이다() {
		String query = GitHubGrassClient.buildQuery(FROM, FROM.plusDays(6));

		assertThat(query).contains("from: \"2026-09-28T00:00:00Z\", to: \"2026-10-04T23:59:59Z\"");
		assertThat(query.split("contributionsCollection", -1)).hasSize(2);
	}

	@Test
	void 연말연시와_윤일에서도_일자를_그대로_보낸다() {
		String newYear = GitHubGrassClient.buildQuery(LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 1));
		String leapDay = GitHubGrassClient.buildQuery(LocalDate.of(2028, 2, 29), LocalDate.of(2028, 2, 29));

		assertThat(newYear).contains("from: \"2026-12-31T00:00:00Z\", to: \"2027-01-01T23:59:59Z\"");
		assertThat(leapDay).contains("from: \"2028-02-29T00:00:00Z\", to: \"2028-02-29T23:59:59Z\"");
	}

	// ---------- 쿼리 모양·상한 ----------

	@Test
	void 쿼리는_로그인을_변수로만_받고_일자별_날짜와_기여_수만_묻는다() {
		String query = GitHubGrassClient.buildQuery(FROM, FROM.plusDays(6));

		assertThat(query).startsWith("query($login: String!) { user(login: $login) {");
		assertThat(query).endsWith(" } }");
		assertThat(query).contains("contributionDays { date contributionCount }");
		assertThat(query).doesNotContain("totalContributions");
	}

	@Test
	void 구간_상한은_62일이다() {
		assertThat(GitHubGrassClient.MAX_DAYS).isEqualTo(62);
	}

	// ---------- 응답 해석 ----------

	@Test
	void 정상_응답은_일자별_기여_수로_바꾼다() {
		List<GrassDay> days = GitHubGrassClient.parse(responseOf(FROM, 0, 3, 0, 1, 0, 0, 12), FROM, FROM.plusDays(6));

		assertThat(days).hasSize(7);
		assertThat(days).extracting(GrassDay::date).containsExactly(FROM, FROM.plusDays(1), FROM.plusDays(2),
				FROM.plusDays(3), FROM.plusDays(4), FROM.plusDays(5), FROM.plusDays(6));
		assertThat(days).extracting(GrassDay::count).containsExactly(0, 3, 0, 1, 0, 0, 12);
		assertThat(days).extracting(GrassDay::hasGrass).containsExactly(false, true, false, true, false, false, true);
	}

	@Test
	void 요청한_하루만_돌려주고_전날_기여를_합치지_않는다() {
		// 10-05=16, 10-06=39 달력에서 10-06만 물으면 39다 (KST 하루 시각 구간을 쓰던 때는 55로 합쳐졌다)
		LocalDate day = LocalDate.of(2026, 10, 6);
		String body = responseOf(day.minusDays(1), 16, 39);

		List<GrassDay> days = GitHubGrassClient.parse(body, day, day);

		assertThat(days).containsExactly(new GrassDay(day, 39));
	}

	@Test
	void 요청_구간_밖_일자가_섞여_있어도_구간만_돌려준다() {
		// 주 단위로 앞뒤가 채워져 오는 경우
		String body = responseOf(FROM.minusDays(3), 9, 9, 9, 2, 0, 5, 9, 9);

		List<GrassDay> days = GitHubGrassClient.parse(body, FROM, FROM.plusDays(2));

		assertThat(days).extracting(GrassDay::date).containsExactly(FROM, FROM.plusDays(1), FROM.plusDays(2));
		assertThat(days).extracting(GrassDay::count).containsExactly(2, 0, 5);
	}

	@Test
	void 여러_주에_걸쳐도_일자로_맞춰_읽는다() {
		int[] counts = new int[14];
		counts[0] = 1;
		counts[7] = 4;
		counts[13] = 2;

		List<GrassDay> days = GitHubGrassClient.parse(responseOf(FROM, counts), FROM, FROM.plusDays(13));

		assertThat(days).hasSize(14);
		assertThat(days.get(0).count()).isEqualTo(1);
		assertThat(days.get(7).count()).isEqualTo(4);
		assertThat(days.get(13).count()).isEqualTo(2);
	}

	@Test
	void 오늘_이후_일자가_0으로_와도_그대로_읽는다() {
		List<GrassDay> days = GitHubGrassClient.parse(responseOf(FROM, 3, 0, 0), FROM, FROM.plusDays(2));

		assertThat(days).extracting(GrassDay::hasGrass).containsExactly(true, false, false);
	}

	@Test
	void 빈_errors_배열이_있어도_data가_정상이면_읽는다() {
		String body = responseOf(FROM, 2).replaceFirst("^\\{", "{\"errors\":[],");

		assertThat(GitHubGrassClient.parse(body, FROM, FROM)).extracting(GrassDay::count).containsExactly(2);
	}

	@Test
	void NOT_FOUND_오류는_재시도_없이_IDENTITY_MISMATCH다() {
		String body = "{\"data\":{\"user\":null},\"errors\":[{\"type\":\"NOT_FOUND\",\"path\":[\"user\"],"
				+ "\"message\":\"Could not resolve to a User with the login of 'gone'.\"}]}";

		GrassFetchException e = failureOf(body, FROM, FROM);

		assertThat(e.getReason()).isEqualTo(HoldReason.IDENTITY_MISMATCH);
		assertThat(e.isRetryable()).isFalse();
	}

	@Test
	void NOT_FOUND가_다른_오류와_섞여도_IDENTITY_MISMATCH다() {
		String body = "{\"errors\":[{\"type\":\"RATE_LIMITED\"},{\"type\":\"NOT_FOUND\"}]}";

		assertThat(failureOf(body, FROM, FROM).getReason()).isEqualTo(HoldReason.IDENTITY_MISMATCH);
	}

	@ParameterizedTest
	@ValueSource(strings = {"RATE_LIMITED", "FORBIDDEN", "INTERNAL", "SOMETHING_NEW"})
	void 레이트리밋_등_나머지_GraphQL_오류는_재시도_대상_API_ERROR다(String type) {
		String body = "{\"data\":null,\"errors\":[{\"type\":\"" + type + "\",\"message\":\"x\"}]}";

		GrassFetchException e = failureOf(body, FROM, FROM);

		assertThat(e.getReason()).isEqualTo(HoldReason.API_ERROR);
		assertThat(e.isRetryable()).isTrue();
	}

	@Test
	void type이_없는_errors도_API_ERROR다() {
		assertThat(failureOf("{\"errors\":[{\"message\":\"boom\"}]}", FROM, FROM).getReason())
				.isEqualTo(HoldReason.API_ERROR);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "   ", "not json", "{", "[]", "{}", "null", "\"text\"",
			"{\"data\":null}", "{\"data\":{}}", "{\"data\":{\"user\":null}}", "{\"data\":{\"user\":[]}}",
			"{\"data\":{\"user\":{}}}", "{\"data\":{\"user\":{\"contributionsCollection\":{}}}}",
			"{\"data\":{\"user\":{\"contributionsCollection\":{\"contributionCalendar\":{\"weeks\":{}}}}}}"})
	void 비었거나_형식이_이상한_응답은_API_ERROR다(String body) {
		GrassFetchException e = failureOf(body, FROM, FROM);

		assertThat(e.getReason()).isEqualTo(HoldReason.API_ERROR);
		assertThat(e.isRetryable()).isTrue();
	}

	@Test
	void 응답_본문이_null이어도_API_ERROR다() {
		assertThat(failureOf(null, FROM, FROM).getReason()).isEqualTo(HoldReason.API_ERROR);
	}

	@Test
	void 요청한_일자가_하나라도_빠지면_API_ERROR다() {
		// 3일을 물었는데 앞 두 날만 온 경우
		assertThat(failureOf(responseOf(FROM, 1, 1), FROM, FROM.plusDays(2)).getReason())
				.isEqualTo(HoldReason.API_ERROR);
	}

	@Test
	void 요청한_일자가_없고_다른_일자만_있어도_API_ERROR다() {
		assertThat(failureOf(responseOf(FROM.plusDays(10), 1, 1), FROM, FROM).getReason())
				.isEqualTo(HoldReason.API_ERROR);
	}

	@ParameterizedTest
	@ValueSource(strings = {"\"5\"", "1.5", "null", "-1", "true", "{}", "3000000000"})
	void 기여_수가_정수가_아니거나_범위를_벗어나면_API_ERROR다(String count) {
		String body = calendarOf("{\"contributionDays\":[" + entry(FROM.toString(), count) + "]}");

		assertThat(failureOf(body, FROM, FROM).getReason()).isEqualTo(HoldReason.API_ERROR);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "2026-9-28", "28/09/2026", "not-a-date", "2026-02-30"})
	void 일자_형식이_이상하면_API_ERROR다(String date) {
		String body = calendarOf("{\"contributionDays\":[" + entry(date, "1") + "]}");

		GrassFetchException e = failureOf(body, FROM, FROM);

		assertThat(e.getReason()).isEqualTo(HoldReason.API_ERROR);
		assertThat(e.isRetryable()).isTrue();
	}

	@Test
	void 예외_메시지에_응답_본문을_싣지_않는다() {
		String body = "{\"errors\":[{\"type\":\"RATE_LIMITED\",\"message\":\"SECRET-ECHO-ghp_leaked_value\"}]}";
		String garbage = "SECRET-ECHO-ghp_leaked_value <html>";
		String badDate = calendarOf("{\"contributionDays\":[" + entry("SECRET-ECHO-ghp_leaked_value", "1") + "]}");

		GrassFetchException typed = failureOf(body, FROM, FROM);
		assertThatThrownBy(() -> GitHubGrassClient.parse(garbage, FROM, FROM))
				.isInstanceOf(GrassFetchException.class)
				.hasMessageNotContaining("SECRET-ECHO");
		assertThatThrownBy(() -> GitHubGrassClient.parse(badDate, FROM, FROM))
				.isInstanceOf(GrassFetchException.class)
				.hasMessageNotContaining("SECRET-ECHO");

		assertThat(typed.getMessage()).doesNotContain("SECRET-ECHO");
	}

}
