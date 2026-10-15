package com.jandilog.judgment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.jandilog.judgment.domain.HoldReason;
import com.jandilog.judgment.dto.GrassDay;

// GitHubGrassClient의 쿼리 생성(KST→UTC 구간)과 응답 해석. 네트워크 없이 package-private 메서드를 직접 부른다
class GitHubGrassClientQueryParseTest {

	private static final Pattern WINDOW = Pattern
			.compile("d(\\d+): contributionsCollection\\(from: \"([^\"]+)\", to: \"([^\"]+)\"\\)");
	private static final LocalDate FROM = LocalDate.of(2026, 9, 28);

	private record Window(int alias, Instant from, Instant to) {
	}

	private static List<Window> windows(String query) {
		List<Window> windows = new ArrayList<>();
		Matcher matcher = WINDOW.matcher(query);
		while (matcher.find()) {
			windows.add(new Window(Integer.parseInt(matcher.group(1)), Instant.parse(matcher.group(2)),
					Instant.parse(matcher.group(3))));
		}
		return windows;
	}

	// d0..dN 별칭마다 totalContributions를 채운 GitHub 응답
	static String responseOf(int... totals) {
		StringBuilder body = new StringBuilder("{\"data\":{\"user\":{");
		for (int i = 0; i < totals.length; i++) {
			if (i > 0) {
				body.append(',');
			}
			body.append("\"d").append(i).append("\":{\"contributionCalendar\":{\"totalContributions\":")
					.append(totals[i]).append("}}");
		}
		return body.append("}}}").toString();
	}

	private static GrassFetchException failureOf(String body, LocalDate from, LocalDate to) {
		Throwable thrown = catchThrowable(() -> GitHubGrassClient.parse(body, from, to));
		assertThat(thrown).isInstanceOf(GrassFetchException.class);
		return (GrassFetchException) thrown;
	}

	// ---------- KST 일자 → UTC 구간 ----------

	@Test
	void 하루_구간은_KST_0시부터_23시_59분_59초까지를_UTC로_바꾼다() {
		String query = GitHubGrassClient.buildQuery(FROM, FROM);

		assertThat(windows(query)).containsExactly(new Window(0, Instant.parse("2026-09-27T15:00:00Z"),
				Instant.parse("2026-09-28T14:59:59Z")));
	}

	@Test
	void 일곱_날은_별칭_d0부터_d6까지_하루씩_순서대로_만든다() {
		List<Window> windows = windows(GitHubGrassClient.buildQuery(FROM, FROM.plusDays(6)));

		assertThat(windows).hasSize(7);
		assertThat(windows).extracting(Window::alias).containsExactly(0, 1, 2, 3, 4, 5, 6);
		assertThat(windows.get(0).from()).isEqualTo(Instant.parse("2026-09-27T15:00:00Z"));
		assertThat(windows.get(0).to()).isEqualTo(Instant.parse("2026-09-28T14:59:59Z"));
		assertThat(windows.get(6).from()).isEqualTo(Instant.parse("2026-10-03T15:00:00Z"));
		assertThat(windows.get(6).to()).isEqualTo(Instant.parse("2026-10-04T14:59:59Z"));
	}

	@Test
	void 이웃한_날의_구간은_빈틈도_겹침도_없다() {
		List<Window> windows = windows(GitHubGrassClient.buildQuery(FROM, FROM.plusDays(13)));

		for (int i = 0; i + 1 < windows.size(); i++) {
			assertThat(windows.get(i).to().plusSeconds(1)).isEqualTo(windows.get(i + 1).from());
		}
	}

	@Test
	void 연말연시와_윤일에서도_KST_하루를_정확히_자른다() {
		List<Window> newYear = windows(GitHubGrassClient.buildQuery(LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 1)));
		List<Window> leapDay = windows(GitHubGrassClient.buildQuery(LocalDate.of(2028, 2, 29), LocalDate.of(2028, 2, 29)));

		assertThat(newYear.get(0).from()).isEqualTo(Instant.parse("2026-12-30T15:00:00Z"));
		assertThat(newYear.get(0).to()).isEqualTo(Instant.parse("2026-12-31T14:59:59Z"));
		assertThat(newYear.get(1).from()).isEqualTo(Instant.parse("2026-12-31T15:00:00Z"));
		assertThat(newYear.get(1).to()).isEqualTo(Instant.parse("2027-01-01T14:59:59Z"));
		assertThat(leapDay.get(0).from()).isEqualTo(Instant.parse("2028-02-28T15:00:00Z"));
		assertThat(leapDay.get(0).to()).isEqualTo(Instant.parse("2028-02-29T14:59:59Z"));
	}

	@Test
	void 경계_시각은_KST_0시는_그날이고_전날_23시_59분_59초는_전날이다() {
		List<Window> windows = windows(GitHubGrassClient.buildQuery(FROM, FROM.plusDays(1)));
		Window monday = windows.get(0);
		Window tuesday = windows.get(1);
		Instant mondayStartKst = Instant.parse("2026-09-27T15:00:00Z");
		Instant mondayLastSecondKst = Instant.parse("2026-09-28T14:59:59Z");
		Instant sundayLastSecondKst = Instant.parse("2026-09-27T14:59:59Z");

		assertThat(contains(monday, mondayStartKst)).isTrue();
		assertThat(contains(monday, mondayLastSecondKst)).isTrue();
		assertThat(contains(monday, sundayLastSecondKst)).isFalse();
		assertThat(contains(monday, tuesday.from())).isFalse();
		assertThat(contains(tuesday, tuesday.from())).isTrue();
	}

	private static boolean contains(Window window, Instant instant) {
		return !instant.isBefore(window.from()) && !instant.isAfter(window.to());
	}

	@Test
	void 시각_변환_도우미는_KST_0시와_다음날_0시_1초_전을_돌려준다() {
		assertThat(GitHubGrassClient.dayStart(LocalDate.of(2026, 1, 1))).isEqualTo(Instant.parse("2025-12-31T15:00:00Z"));
		assertThat(GitHubGrassClient.dayEnd(LocalDate.of(2026, 1, 1))).isEqualTo(Instant.parse("2026-01-01T14:59:59Z"));
	}

	// ---------- 쿼리 모양·상한 ----------

	@Test
	void 쿼리는_로그인을_변수로만_받고_일자_수만큼_별칭을_둔다() {
		String query = GitHubGrassClient.buildQuery(FROM, FROM.plusDays(6));

		assertThat(query).startsWith("query($login: String!) { user(login: $login) {");
		assertThat(query).endsWith(" } }");
		assertThat(query.split("contributionsCollection", -1)).hasSize(8);
		assertThat(query).contains("totalContributions");
	}

	@Test
	void 별칭_상한은_62일이고_62일_구간은_d0부터_d61까지_만든다() {
		assertThat(GitHubGrassClient.MAX_DAYS).isEqualTo(62);

		List<Window> windows = windows(GitHubGrassClient.buildQuery(FROM, FROM.plusDays(61)));

		assertThat(windows).hasSize(62);
		assertThat(windows.get(61).alias()).isEqualTo(61);
	}

	// ---------- 응답 해석 ----------

	@Test
	void 정상_응답은_일자별_기여_수로_바꾼다() {
		List<GrassDay> days = GitHubGrassClient.parse(responseOf(0, 3, 0, 1, 0, 0, 12), FROM, FROM.plusDays(6));

		assertThat(days).hasSize(7);
		assertThat(days).extracting(GrassDay::date).containsExactly(FROM, FROM.plusDays(1), FROM.plusDays(2),
				FROM.plusDays(3), FROM.plusDays(4), FROM.plusDays(5), FROM.plusDays(6));
		assertThat(days).extracting(GrassDay::count).containsExactly(0, 3, 0, 1, 0, 0, 12);
		assertThat(days).extracting(GrassDay::hasGrass).containsExactly(false, true, false, true, false, false, true);
	}

	@Test
	void 빈_errors_배열이_있어도_data가_정상이면_읽는다() {
		String body = responseOf(2).replaceFirst("^\\{", "{\"errors\":[],");

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
			"{\"data\":null}", "{\"data\":{}}", "{\"data\":{\"user\":null}}", "{\"data\":{\"user\":[]}}"})
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
	void 일자_별칭이_하나라도_빠지면_API_ERROR다() {
		// d0, d1만 있고 d2가 없는 3일 요청
		assertThat(failureOf(responseOf(1, 1), FROM, FROM.plusDays(2)).getReason()).isEqualTo(HoldReason.API_ERROR);
	}

	@ParameterizedTest
	@ValueSource(strings = {"\"5\"", "1.5", "null", "-1", "true", "{}", "3000000000"})
	void 기여_수가_정수가_아니거나_범위를_벗어나면_API_ERROR다(String total) {
		String body = "{\"data\":{\"user\":{\"d0\":{\"contributionCalendar\":{\"totalContributions\":" + total + "}}}}}";

		assertThat(failureOf(body, FROM, FROM).getReason()).isEqualTo(HoldReason.API_ERROR);
	}

	@Test
	void 예외_메시지에_응답_본문을_싣지_않는다() {
		String body = "{\"errors\":[{\"type\":\"RATE_LIMITED\",\"message\":\"SECRET-ECHO-ghp_leaked_value\"}]}";
		String garbage = "SECRET-ECHO-ghp_leaked_value <html>";

		GrassFetchException typed = failureOf(body, FROM, FROM);
		assertThatThrownBy(() -> GitHubGrassClient.parse(garbage, FROM, FROM))
				.isInstanceOf(GrassFetchException.class)
				.hasMessageNotContaining("SECRET-ECHO");

		assertThat(typed.getMessage()).doesNotContain("SECRET-ECHO");
	}

}
