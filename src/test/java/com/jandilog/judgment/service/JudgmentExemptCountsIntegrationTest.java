package com.jandilog.judgment.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.jandilog.testsupport.auth.AuthIntegrationTest;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;

// 면제·제외·보류 주는 지난 판정·한 주 판정·내 주간 활동에서 인증일·기록글을 "—"(null)로 둔다 (화면설계서 AC-01 ⑤).
// 정정·소급 면제로 통과·미달에서 면제가 된 주는 DB에 예전 수치가 남아 있어도 응답에서만 숨긴다
class JudgmentExemptCountsIntegrationTest extends AuthIntegrationTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	// 2026-09-28은 월요일. 아래 주차는 모두 지난 주다
	private static final LocalDate PASS_WEEK = LocalDate.of(2026, 9, 28);
	private static final LocalDate RETRO_EXEMPT_FROM_PASS = LocalDate.of(2026, 9, 21);
	private static final LocalDate RETRO_EXEMPT_FROM_FAIL = LocalDate.of(2026, 9, 14);
	private static final LocalDate EXEMPT_FROM_START = LocalDate.of(2026, 9, 7);
	private static final LocalDate EXCLUDED_WEEK = LocalDate.of(2026, 8, 31);
	private static final LocalDate FAIL_WEEK = LocalDate.of(2026, 8, 24);
	private static final LocalDate HOLD_API_ERROR_WEEK = LocalDate.of(2026, 8, 17);
	private static final LocalDate CORRECTED_PASS_WEEK = LocalDate.of(2026, 8, 10);
	private static final LocalDate CORRECTED_FAIL_WEEK = LocalDate.of(2026, 8, 3);
	private static final LocalDate HOLD_MISMATCH_WEEK = LocalDate.of(2026, 7, 27);
	// 잔디 캐시를 시험용으로 채울 때 가장 이른 주 이전의 월요일부터 덮는다
	private static final LocalDate CACHE_FROM = LocalDate.of(2026, 7, 27);

	private static final String HISTORY = """
			{ myJudgmentHistory { items { weekStart status skipReason holdReason verifiedDays recordCount corrected } nextCursor } }
			""";
	private static final String ACTIVITY = """
			query($week: String) {
			  myWeeklyActivity(weekStart: $week) {
			    verifiedDays recordCount grassAvailable
			    days { date hasGrass grassCount hasRecord recordCount }
			    judgment { status skipReason holdReason verifiedDays recordCount corrected }
			  }
			}
			""";

	private long memberId;

	@BeforeEach
	void setUpScenario() {
		memberId = members.active();
		// 통과 주: 저장값 인증일 4·기록글 2, 일자 근거 7행(매일 잔디 있음)
		judgment(PASS_WEEK, "PASS", null, 4, 2, false, true);
		// 통과였다가 소급 면제된 주: 예전 수치(4·2)와 일자 근거가 DB에 남아 있다
		judgment(RETRO_EXEMPT_FROM_PASS, "EXEMPT", "PERSONAL_EXEMPTION", 4, 2, true, true);
		// 미달이었다가 정정으로 면제가 된 주: 예전 수치(1·0)가 남아 있다
		judgment(RETRO_EXEMPT_FROM_FAIL, "EXEMPT", "EXEMPTION_PERIOD", 1, 0, true, true);
		// 처음부터 면제·제외·보류로 저장된 주: 수치가 없다
		judgment(EXEMPT_FROM_START, "EXEMPT", "EXEMPTION_PERIOD", null, null, false, false);
		judgment(EXCLUDED_WEEK, "EXCLUDED", "FIRST_WEEK", null, null, false, false);
		judgment(FAIL_WEEK, "FAIL", null, 2, 0, false, true);
		hold(HOLD_API_ERROR_WEEK, "API_ERROR");
		hold(HOLD_MISMATCH_WEEK, "IDENTITY_MISMATCH");
		// 정정으로 결과가 바뀌었지만 여전히 통과·미달인 주: 저장값을 그대로 보여준다
		judgment(CORRECTED_PASS_WEEK, "PASS", null, 3, 1, true, true);
		judgment(CORRECTED_FAIL_WEEK, "FAIL", null, 2, 1, true, true);
	}

	@AfterEach
	void cleanUpScenario() {
		jdbc.update("delete from judgment_day where judgment_id in (select id from weekly_judgment where member_id = ?)",
				memberId);
		jdbc.update("delete from weekly_judgment where member_id = ?", memberId);
		jdbc.update("delete from post_index where author_id = ?", memberId);
		redis.delete(GrassCacheService.key(memberId, LocalDate.now(clock.withZone(KST))));
	}

	// ---- 지난 판정 목록 ----

	@Test
	void 소급_면제나_정정으로_면제가_된_주는_지난_판정에서_인증일과_기록글이_null이다() {
		Map<String, JsonNode> weeks = history();

		for (LocalDate week : new LocalDate[] {RETRO_EXEMPT_FROM_PASS, RETRO_EXEMPT_FROM_FAIL}) {
			JsonNode item = weeks.get(week.toString());
			assertThat(item.path("status").asText()).isEqualTo("EXEMPT");
			assertThat(item.path("corrected").asBoolean()).isTrue();
			assertThat(item.path("verifiedDays").isNull()).as("%s 인증일", week).isTrue();
			assertThat(item.path("recordCount").isNull()).as("%s 기록글", week).isTrue();
		}
		// 면제 사유는 그대로 내려간다
		assertThat(weeks.get(RETRO_EXEMPT_FROM_PASS.toString()).path("skipReason").asText())
				.isEqualTo("PERSONAL_EXEMPTION");
		assertThat(weeks.get(RETRO_EXEMPT_FROM_FAIL.toString()).path("skipReason").asText())
				.isEqualTo("EXEMPTION_PERIOD");
		// 저장값은 지우지 않는다: 통과·미달로 되돌리면 다시 보여야 한다
		assertThat(stored("verified_days", RETRO_EXEMPT_FROM_PASS)).isEqualTo(4);
		assertThat(stored("record_count", RETRO_EXEMPT_FROM_PASS)).isEqualTo(2);
		assertThat(stored("verified_days", RETRO_EXEMPT_FROM_FAIL)).isEqualTo(1);
		assertThat(stored("record_count", RETRO_EXEMPT_FROM_FAIL)).isZero();
	}

	@Test
	void 처음부터_면제나_제외이거나_보류인_주도_인증일과_기록글이_null이다() {
		Map<String, JsonNode> weeks = history();

		assertThat(weeks.get(EXEMPT_FROM_START.toString()).path("status").asText()).isEqualTo("EXEMPT");
		assertThat(weeks.get(EXCLUDED_WEEK.toString()).path("status").asText()).isEqualTo("EXCLUDED");
		assertThat(weeks.get(HOLD_API_ERROR_WEEK.toString()).path("status").asText()).isEqualTo("HOLD");
		assertThat(weeks.get(HOLD_API_ERROR_WEEK.toString()).path("holdReason").asText()).isEqualTo("API_ERROR");
		assertThat(weeks.get(HOLD_MISMATCH_WEEK.toString()).path("status").asText()).isEqualTo("HOLD");
		assertThat(weeks.get(HOLD_MISMATCH_WEEK.toString()).path("holdReason").asText()).isEqualTo("IDENTITY_MISMATCH");
		for (LocalDate week : new LocalDate[] {EXEMPT_FROM_START, EXCLUDED_WEEK, HOLD_API_ERROR_WEEK,
				HOLD_MISMATCH_WEEK}) {
			assertThat(weeks.get(week.toString()).path("verifiedDays").isNull()).as("%s 인증일", week).isTrue();
			assertThat(weeks.get(week.toString()).path("recordCount").isNull()).as("%s 기록글", week).isTrue();
		}
	}

	@Test
	void 통과와_미달_주는_저장된_인증일과_기록글을_그대로_보여준다() {
		Map<String, JsonNode> weeks = history();

		assertCounts(weeks.get(PASS_WEEK.toString()), "PASS", 4, 2);
		assertCounts(weeks.get(FAIL_WEEK.toString()), "FAIL", 2, 0);
	}

	@Test
	void 정정을_거쳤어도_통과나_미달이면_저장값과_정정_표시가_그대로_내려간다() {
		Map<String, JsonNode> weeks = history();

		assertCounts(weeks.get(CORRECTED_PASS_WEEK.toString()), "PASS", 3, 1);
		assertThat(weeks.get(CORRECTED_PASS_WEEK.toString()).path("corrected").asBoolean()).isTrue();
		assertCounts(weeks.get(CORRECTED_FAIL_WEEK.toString()), "FAIL", 2, 1);
		assertThat(weeks.get(CORRECTED_FAIL_WEEK.toString()).path("corrected").asBoolean()).isTrue();
		// 정정 안 한 주는 표시가 꺼져 있다
		assertThat(weeks.get(PASS_WEEK.toString()).path("corrected").asBoolean()).isFalse();
	}

	@Test
	void 정정으로_상태가_바뀌면_지난_판정의_보이는_값도_따라_바뀐다() {
		// 소급 면제가 취소되어 통과로 돌아간 주는 남아 있던 수치가 다시 보인다
		changeStatus(RETRO_EXEMPT_FROM_PASS, "PASS", null);
		// 통과였던 주가 정정으로 면제가 되면 저장값이 남아 있어도 숨긴다
		changeStatus(PASS_WEEK, "EXEMPT", "PERSONAL_EXEMPTION");
		// 미달이었던 주가 정정으로 통과가 되면 저장값을 보인다
		changeStatus(FAIL_WEEK, "PASS", null);

		Map<String, JsonNode> weeks = history();

		assertCounts(weeks.get(RETRO_EXEMPT_FROM_PASS.toString()), "PASS", 4, 2);
		JsonNode nowExempt = weeks.get(PASS_WEEK.toString());
		assertThat(nowExempt.path("status").asText()).isEqualTo("EXEMPT");
		assertThat(nowExempt.path("corrected").asBoolean()).isTrue();
		assertThat(nowExempt.path("verifiedDays").isNull()).isTrue();
		assertThat(nowExempt.path("recordCount").isNull()).isTrue();
		assertCounts(weeks.get(FAIL_WEEK.toString()), "PASS", 2, 0);
		assertThat(stored("verified_days", PASS_WEEK)).isEqualTo(4);
	}

	// ---- 내 주간 활동 (한 주 판정 포함) ----

	@Test
	void 내_주간_활동에서_통과와_미달_주는_저장된_판정값과_일자_근거를_쓴다() {
		// 잔디 캐시와 기록글이 저장값과 달라도 판정이 끝난 통과·미달 주는 판정 시점 값이 나온다
		seedGrassCache(Map.of());

		JsonNode pass = activity(PASS_WEEK);

		assertThat(pass.path("judgment").path("status").asText()).isEqualTo("PASS");
		assertThat(pass.path("judgment").path("verifiedDays").asInt()).isEqualTo(4);
		assertThat(pass.path("judgment").path("recordCount").asInt()).isEqualTo(2);
		assertThat(pass.path("verifiedDays").asInt()).isEqualTo(4);
		assertThat(pass.path("recordCount").asInt()).isEqualTo(2);
		assertThat(pass.path("days")).hasSize(7);
		assertThat(pass.path("days").get(0).path("hasGrass").asBoolean()).isTrue();
		// 저장한 일자 근거에는 잔디·기록글 수가 없다
		assertThat(pass.path("days").get(0).path("grassCount").isNull()).isTrue();

		JsonNode fail = activity(FAIL_WEEK);

		assertThat(fail.path("judgment").path("status").asText()).isEqualTo("FAIL");
		assertThat(fail.path("judgment").path("verifiedDays").asInt()).isEqualTo(2);
		assertThat(fail.path("judgment").path("recordCount").asInt()).isZero();
		assertThat(fail.path("verifiedDays").asInt()).isEqualTo(2);
		assertThat(fail.path("recordCount").asInt()).isZero();
	}

	@Test
	void 내_주간_활동에서_정정을_거친_통과와_미달_주도_저장값과_정정_표시를_쓴다() {
		seedGrassCache(Map.of());

		JsonNode pass = activity(CORRECTED_PASS_WEEK);
		JsonNode fail = activity(CORRECTED_FAIL_WEEK);

		assertThat(pass.path("judgment").path("verifiedDays").asInt()).isEqualTo(3);
		assertThat(pass.path("judgment").path("recordCount").asInt()).isEqualTo(1);
		assertThat(pass.path("judgment").path("corrected").asBoolean()).isTrue();
		assertThat(fail.path("judgment").path("verifiedDays").asInt()).isEqualTo(2);
		assertThat(fail.path("judgment").path("recordCount").asInt()).isEqualTo(1);
		assertThat(fail.path("judgment").path("corrected").asBoolean()).isTrue();
	}

	@Test
	void 내_주간_활동에서_면제가_된_주는_예전_수치를_내리지_않고_새로_계산한다() {
		// 소급 면제 주(09-21): 월요일에만 잔디 5개와 기록글 1개. 저장돼 있던 값은 인증일 4·기록글 2·매일 잔디
		// 정정 면제 주(09-14): 수·목 잔디, 기록글 없음. 저장돼 있던 값은 인증일 1·기록글 0
		seedGrassCache(Map.of(RETRO_EXEMPT_FROM_PASS, 5, RETRO_EXEMPT_FROM_FAIL.plusDays(2), 2,
				RETRO_EXEMPT_FROM_FAIL.plusDays(3), 1));
		recordPost(RETRO_EXEMPT_FROM_PASS);

		JsonNode retroPass = activity(RETRO_EXEMPT_FROM_PASS);

		assertThat(retroPass.path("judgment").path("status").asText()).isEqualTo("EXEMPT");
		assertThat(retroPass.path("judgment").path("skipReason").asText()).isEqualTo("PERSONAL_EXEMPTION");
		assertThat(retroPass.path("judgment").path("corrected").asBoolean()).isTrue();
		assertThat(retroPass.path("judgment").path("verifiedDays").isNull()).isTrue();
		assertThat(retroPass.path("judgment").path("recordCount").isNull()).isTrue();
		// 화면 본문 수치도 예전 값(4·2)이 아니라 지금 계산한 값(1·1)이다
		assertThat(retroPass.path("verifiedDays").asInt()).isEqualTo(1);
		assertThat(retroPass.path("recordCount").asInt()).isEqualTo(1);
		assertThat(retroPass.path("days").get(0).path("hasGrass").asBoolean()).isTrue();
		assertThat(retroPass.path("days").get(0).path("grassCount").asInt()).isEqualTo(5);
		assertThat(retroPass.path("days").get(0).path("hasRecord").asBoolean()).isTrue();
		assertThat(retroPass.path("days").get(1).path("hasGrass").asBoolean()).isFalse();

		JsonNode retroFail = activity(RETRO_EXEMPT_FROM_FAIL);

		assertThat(retroFail.path("judgment").path("status").asText()).isEqualTo("EXEMPT");
		assertThat(retroFail.path("judgment").path("verifiedDays").isNull()).isTrue();
		assertThat(retroFail.path("judgment").path("recordCount").isNull()).isTrue();
		assertThat(retroFail.path("verifiedDays").asInt()).isEqualTo(2);
		assertThat(retroFail.path("recordCount").asInt()).isZero();
		assertThat(retroFail.path("days").get(2).path("hasGrass").asBoolean()).isTrue();
	}

	@Test
	void 내_주간_활동에서_면제_주는_잔디_캐시가_없으면_인증일을_모르는_값으로_둔다() {
		JsonNode exempt = activity(RETRO_EXEMPT_FROM_PASS);

		assertThat(exempt.path("judgment").path("status").asText()).isEqualTo("EXEMPT");
		assertThat(exempt.path("judgment").path("verifiedDays").isNull()).isTrue();
		assertThat(exempt.path("judgment").path("recordCount").isNull()).isTrue();
		// 예전 판정 시점 값(인증일 4·기록글 2·잔디 있음)이 아니라 지금 계산한 값이다
		assertThat(exempt.path("grassAvailable").asBoolean()).isFalse();
		assertThat(exempt.path("verifiedDays").isNull()).isTrue();
		assertThat(exempt.path("recordCount").asInt()).isZero();
		assertThat(exempt.path("days").get(0).path("hasGrass").isNull()).isTrue();
	}

	@Test
	void 내_주간_활동에서_처음부터_면제나_제외이거나_보류인_주는_판정_수치가_null이다() {
		for (Object[] row : new Object[][] {{EXEMPT_FROM_START, "EXEMPT", "EXEMPTION_PERIOD", null},
				{EXCLUDED_WEEK, "EXCLUDED", "FIRST_WEEK", null}, {HOLD_API_ERROR_WEEK, "HOLD", null, "API_ERROR"},
				{HOLD_MISMATCH_WEEK, "HOLD", null, "IDENTITY_MISMATCH"}}) {
			LocalDate week = (LocalDate) row[0];

			JsonNode judgment = activity(week).path("judgment");

			assertThat(judgment.path("status").asText()).as("%s 상태", week).isEqualTo(row[1]);
			assertThat(judgment.path("skipReason").isNull() ? null : judgment.path("skipReason").asText())
					.as("%s 사유", week).isEqualTo(row[2]);
			assertThat(judgment.path("holdReason").isNull() ? null : judgment.path("holdReason").asText())
					.as("%s 보류 사유", week).isEqualTo(row[3]);
			assertThat(judgment.path("verifiedDays").isNull()).as("%s 인증일", week).isTrue();
			assertThat(judgment.path("recordCount").isNull()).as("%s 기록글", week).isTrue();
		}
	}

	@Test
	void 정정으로_상태가_바뀌면_내_주간_활동의_보이는_값도_따라_바뀐다() {
		seedGrassCache(Map.of());
		// 소급 면제가 취소되어 통과로 돌아간 주는 저장된 판정값과 일자 근거가 다시 쓰인다
		changeStatus(RETRO_EXEMPT_FROM_PASS, "PASS", null);
		// 통과였던 주가 정정으로 면제가 되면 저장값이 남아 있어도 새로 계산한 값을 쓴다
		changeStatus(PASS_WEEK, "EXEMPT", "PERSONAL_EXEMPTION");

		JsonNode back = activity(RETRO_EXEMPT_FROM_PASS);

		assertThat(back.path("judgment").path("status").asText()).isEqualTo("PASS");
		assertThat(back.path("judgment").path("verifiedDays").asInt()).isEqualTo(4);
		assertThat(back.path("judgment").path("recordCount").asInt()).isEqualTo(2);
		assertThat(back.path("verifiedDays").asInt()).isEqualTo(4);
		assertThat(back.path("days").get(0).path("hasGrass").asBoolean()).isTrue();

		JsonNode hidden = activity(PASS_WEEK);

		assertThat(hidden.path("judgment").path("status").asText()).isEqualTo("EXEMPT");
		assertThat(hidden.path("judgment").path("corrected").asBoolean()).isTrue();
		assertThat(hidden.path("judgment").path("verifiedDays").isNull()).isTrue();
		assertThat(hidden.path("judgment").path("recordCount").isNull()).isTrue();
		// 잔디 캐시는 전부 0칸이고 기록글도 없으니 새로 계산한 값은 인증일 0·기록글 0이다
		assertThat(hidden.path("verifiedDays").asInt()).isZero();
		assertThat(hidden.path("recordCount").asInt()).isZero();
		assertThat(hidden.path("days").get(0).path("hasGrass").asBoolean()).isFalse();
	}

	// ---- 도우미 ----

	private Map<String, JsonNode> history() {
		GraphQlResponse response = graphQl.post(bearerFor(memberId), HISTORY);
		assertThat(response.hasErrors()).as(response.rawBody()).isFalse();
		Map<String, JsonNode> byWeek = new LinkedHashMap<>();
		response.data().path("myJudgmentHistory").path("items")
				.forEach(item -> byWeek.put(item.path("weekStart").asText(), item));
		assertThat(byWeek).containsOnlyKeys(PASS_WEEK.toString(), RETRO_EXEMPT_FROM_PASS.toString(),
				RETRO_EXEMPT_FROM_FAIL.toString(), EXEMPT_FROM_START.toString(), EXCLUDED_WEEK.toString(),
				FAIL_WEEK.toString(), HOLD_API_ERROR_WEEK.toString(), CORRECTED_PASS_WEEK.toString(),
				CORRECTED_FAIL_WEEK.toString(), HOLD_MISMATCH_WEEK.toString());
		return byWeek;
	}

	private JsonNode activity(LocalDate week) {
		GraphQlResponse response = graphQl.post(bearerFor(memberId), ACTIVITY, Map.of("week", week.toString()));
		assertThat(response.hasErrors()).as(response.rawBody()).isFalse();
		return response.data().path("myWeeklyActivity");
	}

	private static void assertCounts(JsonNode item, String status, int verifiedDays, int recordCount) {
		assertThat(item.path("status").asText()).isEqualTo(status);
		assertThat(item.path("verifiedDays").asInt()).isEqualTo(verifiedDays);
		assertThat(item.path("recordCount").asInt()).isEqualTo(recordCount);
	}

	// 저장된 판정 한 행과 (withDays이면) 일자 근거 7행. 일자 근거는 매일 잔디 있음·기록글 없음
	private void judgment(LocalDate week, String status, String skipReason, Integer verifiedDays, Integer recordCount,
			boolean corrected, boolean withDays) {
		jdbc.update("insert into weekly_judgment (member_id, week_start, status, skip_reason, retry_count,"
				+ " verified_days, record_count, corrected, judged_at) values (?, ?, ?, ?, 0, ?, ?, ?, ?)", memberId,
				week, status, skipReason, verifiedDays, recordCount, corrected, week.plusDays(7).atTime(7, 0));
		if (withDays) {
			Long id = jdbc.queryForObject("select id from weekly_judgment where member_id = ? and week_start = ?",
					Long.class, memberId, week);
			for (int i = 0; i < 7; i++) {
				jdbc.update("insert into judgment_day (judgment_id, day, has_grass, has_record) values (?, ?, true, false)",
						id, week.plusDays(i));
			}
		}
	}

	private void hold(LocalDate week, String holdReason) {
		jdbc.update("insert into weekly_judgment (member_id, week_start, status, hold_reason, retry_count, corrected)"
				+ " values (?, ?, 'HOLD', ?, 0, false)", memberId, week, holdReason);
	}

	// 관리자 정정·소급 면제로 상태가 바뀐 것을 DB에 반영한다. 인증일·기록글 저장값은 건드리지 않는다
	private void changeStatus(LocalDate week, String status, String skipReason) {
		jdbc.update("update weekly_judgment set status = ?, skip_reason = ?, corrected = true"
				+ " where member_id = ? and week_start = ?", status, skipReason, memberId, week);
	}

	// 저장값이 지워지지 않았는지 보는 단일 정수 조회
	private int stored(String column, LocalDate week) {
		Integer value = jdbc.queryForObject(
				"select " + column + " from weekly_judgment where member_id = ? and week_start = ?", Integer.class,
				memberId, week);
		return value == null ? -1 : value;
	}

	// 오늘 키의 잔디 캐시. CACHE_FROM부터 오늘까지 빠짐없이 채우고 지정하지 않은 날은 0칸이다
	private void seedGrassCache(Map<LocalDate, Integer> counts) {
		LocalDate today = LocalDate.now(clock.withZone(KST));
		List<Map<String, Object>> days = new ArrayList<>();
		for (LocalDate day = CACHE_FROM; !day.isAfter(today); day = day.plusDays(1)) {
			days.add(Map.of("date", day.toString(), "count", counts.getOrDefault(day, 0)));
		}
		Map<String, Object> entry = Map.of("memberId", memberId, "from", CACHE_FROM.toString(), "to", today.toString(),
				"fetchedAt", LocalDateTime.now(clock.withZone(KST)).toString(), "days", days);
		try {
			redis.opsForValue().set(GrassCacheService.key(memberId, today), objectMapper.writeValueAsString(entry),
					Duration.ofHours(1));
		}
		catch (JsonProcessingException e) {
			throw new IllegalStateException("잔디 캐시 JSON 변환 실패", e);
		}
	}

	// 기록글(post_index) 한 건. 팀 없이 작성자와 작성일만 둔다
	private void recordPost(LocalDate writtenDate) {
		jdbc.update("insert into post_index (mongo_post_id, author_id, post_type, is_record, written_date, created_at)"
				+ " values (?, ?, 'DEVLOG', true, ?, ?)", UUID.randomUUID().toString().replace("-", "").substring(0, 24),
				memberId, writtenDate, writtenDate.atTime(12, 0));
	}

}
