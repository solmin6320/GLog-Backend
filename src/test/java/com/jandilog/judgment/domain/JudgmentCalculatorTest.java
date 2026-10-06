package com.jandilog.judgment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

// 주간 판정 계산 규칙 (기능명세서 5장, DB명세서 1-6, FC-02). 시계는 월요일 07:00 KST로 고정한다.
// 판정 대상 주: 2026-09-28(월) ~ 10-04(일)
class JudgmentCalculatorTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final LocalDate WEEK = LocalDate.of(2026, 9, 28);
	private static final LocalDateTime JUDGE_TIME = LocalDateTime.of(2026, 10, 5, 7, 0);
	private static final long TEAM_A = 11L;
	private static final long TEAM_B = 22L;
	private static final long TEAM_C = 33L;

	private final JudgmentCalculator calculator = new JudgmentCalculator(clockAt(JUDGE_TIME));

	private static Clock clockAt(LocalDateTime kstTime) {
		return Clock.fixed(kstTime.atZone(KST).toInstant(), KST);
	}

	private static LocalDate day(int index) {
		return WEEK.plusDays(index);
	}

	// 판정 입력을 짧게 만드는 도우미. 기본값은 팀 하나·첫 주 아님·면제 없음·잔디 0칸·기록글 0개
	private static final class Case {

		Set<Long> teams = Set.of(TEAM_A);
		LocalDateTime firstTeamJoinedAt;
		boolean exemptionPeriod;
		boolean personalExemption;
		GrassLookup grass = GrassLookup.available(weekGrass());
		Map<LocalDate, Integer> records = new HashMap<>();

		Case teams(Long... ids) {
			this.teams = Set.of(ids);
			return this;
		}

		Case noTeam() {
			this.teams = Set.of();
			return this;
		}

		Case firstJoinedAt(LocalDateTime time) {
			this.firstTeamJoinedAt = time;
			return this;
		}

		Case exemptionPeriod() {
			this.exemptionPeriod = true;
			return this;
		}

		Case personalExemption() {
			this.personalExemption = true;
			return this;
		}

		Case grassOn(int... dayIndexes) {
			this.grass = GrassLookup.available(weekGrass(dayIndexes));
			return this;
		}

		Case grassFailed(HoldReason reason) {
			this.grass = GrassLookup.failed(reason);
			return this;
		}

		Case noGrassLookup() {
			this.grass = null;
			return this;
		}

		Case recordsOn(int... dayIndexes) {
			for (int index : dayIndexes) {
				records.merge(day(index), 1, Integer::sum);
			}
			return this;
		}

		Case postsOn(int dayIndex, int count) {
			records.put(day(dayIndex), count);
			return this;
		}

		JudgmentInput build() {
			return new JudgmentInput(WEEK, teams, firstTeamJoinedAt, exemptionPeriod, personalExemption, grass, records);
		}

	}

	// 월~일 7일. 지정한 날(0=월 ... 6=일)만 잔디 있음
	private static Map<LocalDate, Boolean> weekGrass(int... dayIndexes) {
		Map<LocalDate, Boolean> map = new HashMap<>();
		for (int i = 0; i < 7; i++) {
			map.put(day(i), false);
		}
		for (int index : dayIndexes) {
			map.put(day(index), true);
		}
		return map;
	}

	private JudgmentResult judge(Case c) {
		return calculator.judge(c.build());
	}

	// ---------- 통과 조건: 인증일 3일 이상 AND 기록글 1개 이상 ----------

	@Test
	void 잔디_0칸이어도_기록글이_서로_다른_3일이면_통과한다() {
		JudgmentResult result = judge(new Case().recordsOn(0, 2, 4));

		assertThat(result.status()).isEqualTo(JudgmentStatus.PASS);
		assertThat(result.verifiedDays()).isEqualTo(3);
		assertThat(result.recordCount()).isEqualTo(3);
		assertThat(result.days()).extracting(DayResult::hasGrass).containsOnly(false);
		assertThat(result.days()).filteredOn(DayResult::hasRecord).extracting(DayResult::day)
				.containsExactly(day(0), day(2), day(4));
	}

	@Test
	void 같은_날_잔디와_기록글이_함께_있어도_인증일은_1일이다() {
		JudgmentResult result = judge(new Case().grassOn(0).recordsOn(0));

		assertThat(result.verifiedDays()).isEqualTo(1);
		assertThat(result.recordCount()).isEqualTo(1);
		assertThat(result.status()).isEqualTo(JudgmentStatus.FAIL);
		DayResult monday = result.days().get(0);
		assertThat(monday.hasGrass()).isTrue();
		assertThat(monday.hasRecord()).isTrue();
		assertThat(monday.verified()).isTrue();
	}

	@Test
	void 인증일_2일이고_기록글_1개면_미달이다() {
		JudgmentResult result = judge(new Case().grassOn(0, 1).recordsOn(0));

		assertThat(result.verifiedDays()).isEqualTo(2);
		assertThat(result.recordCount()).isEqualTo(1);
		assertThat(result.status()).isEqualTo(JudgmentStatus.FAIL);
		assertThat(result.warningRequired()).isTrue();
	}

	@Test
	void 인증일_3일이고_기록글_0개면_미달이다() {
		JudgmentResult result = judge(new Case().grassOn(0, 1, 2));

		assertThat(result.verifiedDays()).isEqualTo(3);
		assertThat(result.recordCount()).isZero();
		assertThat(result.status()).isEqualTo(JudgmentStatus.FAIL);
		assertThat(result.warningRequired()).isTrue();
	}

	@Test
	void 인증일_정확히_3일과_기록글_정확히_1개면_통과한다() {
		JudgmentResult result = judge(new Case().grassOn(0, 1, 2).recordsOn(2));

		assertThat(result.verifiedDays()).isEqualTo(3);
		assertThat(result.recordCount()).isEqualTo(1);
		assertThat(result.status()).isEqualTo(JudgmentStatus.PASS);
		assertThat(result.warningRequired()).isFalse();
	}

	@Test
	void 기록글을_쓴_날이_3번째_인증일이어도_통과한다() {
		JudgmentResult result = judge(new Case().grassOn(0, 1).recordsOn(2));

		assertThat(result.verifiedDays()).isEqualTo(3);
		assertThat(result.recordCount()).isEqualTo(1);
		assertThat(result.status()).isEqualTo(JudgmentStatus.PASS);
	}

	@Test
	void 기록글이_충분해도_인증일이_2일이면_미달이다() {
		JudgmentResult result = judge(new Case().recordsOn(0, 1));

		assertThat(result.verifiedDays()).isEqualTo(2);
		assertThat(result.recordCount()).isEqualTo(2);
		assertThat(result.status()).isEqualTo(JudgmentStatus.FAIL);
	}

	@Test
	void 같은_날_기록글이_여러_개면_인증일은_1일이고_기록글_수는_합산된다() {
		JudgmentResult passing = judge(new Case().grassOn(1, 2).postsOn(0, 3));
		JudgmentResult failing = judge(new Case().postsOn(0, 3));

		assertThat(passing.verifiedDays()).isEqualTo(3);
		assertThat(passing.recordCount()).isEqualTo(3);
		assertThat(passing.status()).isEqualTo(JudgmentStatus.PASS);
		assertThat(failing.verifiedDays()).isEqualTo(1);
		assertThat(failing.recordCount()).isEqualTo(3);
		assertThat(failing.status()).isEqualTo(JudgmentStatus.FAIL);
	}

	@Test
	void 월요일과_일요일_기록글도_그_주에_센다() {
		JudgmentResult result = judge(new Case().recordsOn(0, 6).grassOn(3));

		assertThat(result.verifiedDays()).isEqualTo(3);
		assertThat(result.recordCount()).isEqualTo(2);
		assertThat(result.status()).isEqualTo(JudgmentStatus.PASS);
	}

	@Test
	void 판정_주_밖_날짜의_기록글은_세지_않는다() {
		Case c = new Case().grassOn(0, 1, 2);
		c.records.put(WEEK.minusDays(1), 1);
		c.records.put(WEEK.plusDays(7), 1);

		JudgmentResult result = judge(c);

		assertThat(result.recordCount()).isZero();
		assertThat(result.verifiedDays()).isEqualTo(3);
		assertThat(result.status()).isEqualTo(JudgmentStatus.FAIL);
	}

	@Test
	void 일자별_근거는_월요일부터_일요일까지_7일을_오름차순으로_담고_판정_시각을_남긴다() {
		JudgmentResult result = judge(new Case().grassOn(1, 3, 5).recordsOn(1));

		assertThat(result.days()).hasSize(7);
		for (int i = 0; i < 7; i++) {
			assertThat(result.days().get(i).day()).isEqualTo(day(i));
		}
		assertThat(result.days()).extracting(DayResult::hasGrass)
				.containsExactly(false, true, false, true, false, true, false);
		assertThat(result.judgedAt()).isEqualTo(JUDGE_TIME);
	}

	@Test
	void 잔디_맵에_판정_주_밖_날짜가_더_있어도_7일만_쓴다() {
		Map<LocalDate, Boolean> wide = new HashMap<>(weekGrass(0, 1, 2));
		wide.put(WEEK.minusDays(1), true);
		wide.put(WEEK.plusDays(7), true);
		Case c = new Case().recordsOn(0);
		c.grass = GrassLookup.available(wide);

		JudgmentResult result = judge(c);

		assertThat(result.days()).hasSize(7);
		assertThat(result.verifiedDays()).isEqualTo(3);
	}

	@Test
	void 소속이_여러_팀이어도_판정은_한_번이고_결과가_팀_수에_좌우되지_않는다() {
		JudgmentResult oneTeam = judge(new Case().teams(TEAM_A).grassOn(0, 1, 2).recordsOn(0));
		JudgmentResult threeTeams = judge(new Case().teams(TEAM_A, TEAM_B, TEAM_C).grassOn(0, 1, 2).recordsOn(0));

		assertThat(threeTeams).isEqualTo(oneTeam);
		assertThat(threeTeams.status()).isEqualTo(JudgmentStatus.PASS);
	}

	// ---------- 제외·면제·보류 ----------

	@Test
	void 소속_팀이_없으면_NO_TEAM으로_제외되고_수치와_일자별_근거가_비어_있다() {
		JudgmentResult result = judge(new Case().noTeam().grassOn(0, 1, 2, 3).recordsOn(0));

		assertThat(result.status()).isEqualTo(JudgmentStatus.EXCLUDED);
		assertThat(result.skipReason()).isEqualTo(SkipReason.NO_TEAM);
		assertSkippedShape(result);
	}

	@Test
	void 소속_팀_목록이_null이어도_팀_없음으로_본다() {
		Case c = new Case();
		JudgmentInput input = new JudgmentInput(WEEK, null, null, false, false, c.grass, Map.of());

		JudgmentResult result = calculator.judge(input);

		assertThat(result.skipReason()).isEqualTo(SkipReason.NO_TEAM);
	}

	@ParameterizedTest
	@ValueSource(strings = {"2026-09-28T00:00:00", "2026-09-30T10:00:00", "2026-10-04T23:59:59.999999999"})
	void 첫_팀_참가가_판정_주_안이면_FIRST_WEEK으로_제외된다(String joinedAt) {
		JudgmentResult result = judge(new Case().firstJoinedAt(LocalDateTime.parse(joinedAt)).grassOn(0, 1, 2).recordsOn(0));

		assertThat(result.status()).isEqualTo(JudgmentStatus.EXCLUDED);
		assertThat(result.skipReason()).isEqualTo(SkipReason.FIRST_WEEK);
		assertSkippedShape(result);
	}

	@ParameterizedTest
	@ValueSource(strings = {"2026-09-27T23:59:59", "2026-09-21T10:00:00", "2026-01-05T09:00:00", "2026-10-05T00:00:00"})
	void 첫_참가_주가_아니면_정상_판정한다(String joinedAt) {
		JudgmentResult result = judge(new Case().firstJoinedAt(LocalDateTime.parse(joinedAt)).grassOn(0, 1, 2).recordsOn(0));

		assertThat(result.status()).isEqualTo(JudgmentStatus.PASS);
		assertThat(result.skipReason()).isNull();
	}

	@Test
	void 첫_참가_시각이_없으면_첫_주로_보지_않는다() {
		JudgmentResult result = judge(new Case().firstJoinedAt(null).grassOn(0, 1, 2).recordsOn(0));

		assertThat(result.status()).isEqualTo(JudgmentStatus.PASS);
	}

	@Test
	void 면제_기간이면_EXEMPT이고_사유는_EXEMPTION_PERIOD이다() {
		JudgmentResult result = judge(new Case().exemptionPeriod());

		assertThat(result.status()).isEqualTo(JudgmentStatus.EXEMPT);
		assertThat(result.skipReason()).isEqualTo(SkipReason.EXEMPTION_PERIOD);
		assertSkippedShape(result);
	}

	@Test
	void 개인_면제면_EXEMPT이고_사유는_PERSONAL_EXEMPTION이다() {
		JudgmentResult result = judge(new Case().personalExemption());

		assertThat(result.status()).isEqualTo(JudgmentStatus.EXEMPT);
		assertThat(result.skipReason()).isEqualTo(SkipReason.PERSONAL_EXEMPTION);
		assertSkippedShape(result);
	}

	@Test
	void 면제_기간과_개인_면제가_겹쳐도_상태는_EXEMPT이고_경고가_붙지_않는다() {
		JudgmentResult result = judge(new Case().exemptionPeriod().personalExemption());

		assertThat(result.status()).isEqualTo(JudgmentStatus.EXEMPT);
		assertThat(result.warningRequired()).isFalse();
		assertThat(result.isConfirmed()).isTrue();
	}

	// 결정 대기: E-44는 두 면제를 독립 보존한다고만 하고 skip_reason 우선순위는 명세에 없다. 현재 구현은 면제 기간을 앞에 둔다
	@Test
	void 결정_대기_면제_기간과_개인_면제가_겹치면_사유를_EXEMPTION_PERIOD로_남긴다() {
		JudgmentResult result = judge(new Case().exemptionPeriod().personalExemption());

		assertThat(result.skipReason()).isEqualTo(SkipReason.EXEMPTION_PERIOD);
	}

	@Test
	void 제외_주와_면제_주는_잔디를_조회하지_않았어도_판정할_수_있다() {
		assertThat(judge(new Case().noTeam().noGrassLookup()).skipReason()).isEqualTo(SkipReason.NO_TEAM);
		assertThat(judge(new Case().firstJoinedAt(LocalDateTime.of(2026, 9, 29, 9, 0)).noGrassLookup()).skipReason())
				.isEqualTo(SkipReason.FIRST_WEEK);
		assertThat(judge(new Case().exemptionPeriod().noGrassLookup()).skipReason())
				.isEqualTo(SkipReason.EXEMPTION_PERIOD);
		assertThat(judge(new Case().personalExemption().noGrassLookup()).skipReason())
				.isEqualTo(SkipReason.PERSONAL_EXEMPTION);
	}

	@Test
	void 제외_주와_면제_주는_잔디_조회가_실패했어도_보류가_아니다() {
		assertThat(judge(new Case().noTeam().grassFailed(HoldReason.API_ERROR)).status())
				.isEqualTo(JudgmentStatus.EXCLUDED);
		assertThat(judge(new Case().exemptionPeriod().grassFailed(HoldReason.IDENTITY_MISMATCH)).status())
				.isEqualTo(JudgmentStatus.EXEMPT);
		assertThat(judge(new Case().personalExemption().grassFailed(HoldReason.API_ERROR)).status())
				.isEqualTo(JudgmentStatus.EXEMPT);
	}

	// FC-02 번호 순서: 소속 0개 → 첫 참가 주 → 면제 기간 → 개인 면제 → 조회 실패
	@Test
	void 소속이_없으면_첫_참가_주와_면제와_조회_실패가_겹쳐도_NO_TEAM이다() {
		JudgmentResult result = judge(new Case().noTeam().firstJoinedAt(LocalDateTime.of(2026, 9, 30, 9, 0))
				.exemptionPeriod().personalExemption().grassFailed(HoldReason.API_ERROR));

		assertThat(result.status()).isEqualTo(JudgmentStatus.EXCLUDED);
		assertThat(result.skipReason()).isEqualTo(SkipReason.NO_TEAM);
	}

	@Test
	void 첫_참가_주는_면제와_조회_실패보다_앞선다() {
		JudgmentResult result = judge(new Case().firstJoinedAt(LocalDateTime.of(2026, 9, 30, 9, 0))
				.exemptionPeriod().personalExemption().grassFailed(HoldReason.API_ERROR));

		assertThat(result.status()).isEqualTo(JudgmentStatus.EXCLUDED);
		assertThat(result.skipReason()).isEqualTo(SkipReason.FIRST_WEEK);
	}

	@Test
	void 면제는_조회_실패보다_앞선다() {
		JudgmentResult period = judge(new Case().exemptionPeriod().grassFailed(HoldReason.API_ERROR));
		JudgmentResult personal = judge(new Case().personalExemption().grassFailed(HoldReason.API_ERROR));

		assertThat(period.status()).isEqualTo(JudgmentStatus.EXEMPT);
		assertThat(personal.status()).isEqualTo(JudgmentStatus.EXEMPT);
	}

	@ParameterizedTest
	@EnumSource(HoldReason.class)
	void 잔디_조회가_실패하면_HOLD이고_사유를_그대로_담는다(HoldReason reason) {
		JudgmentResult result = judge(new Case().grassFailed(reason).recordsOn(0, 1, 2));

		assertThat(result.status()).isEqualTo(JudgmentStatus.HOLD);
		assertThat(result.holdReason()).isEqualTo(reason);
		assertThat(result.skipReason()).isNull();
		assertThat(result.verifiedDays()).isNull();
		assertThat(result.recordCount()).isNull();
		assertThat(result.days()).isEmpty();
		assertThat(result.judgedAt()).isNull();
		assertThat(result.isConfirmed()).isFalse();
		assertThat(result.warningRequired()).isFalse();
	}

	@Test
	void 제외_대상이_아닌데_잔디_조회_결과가_없으면_거부한다() {
		assertThatThrownBy(() -> judge(new Case().noGrassLookup()))
				.isInstanceOf(IllegalArgumentException.class);
	}

	// DB명세서 1-6: status와 skip_reason의 대응은 고정이다
	static Stream<Arguments> 상태_사유_대응표() {
		return Stream.of(
				Arguments.of("NO_TEAM", new Case().noTeam(), JudgmentStatus.EXCLUDED, SkipReason.NO_TEAM, null),
				Arguments.of("FIRST_WEEK", new Case().firstJoinedAt(LocalDateTime.of(2026, 9, 29, 9, 0)),
						JudgmentStatus.EXCLUDED, SkipReason.FIRST_WEEK, null),
				Arguments.of("EXEMPTION_PERIOD", new Case().exemptionPeriod(), JudgmentStatus.EXEMPT,
						SkipReason.EXEMPTION_PERIOD, null),
				Arguments.of("PERSONAL_EXEMPTION", new Case().personalExemption(), JudgmentStatus.EXEMPT,
						SkipReason.PERSONAL_EXEMPTION, null),
				Arguments.of("PASS", new Case().grassOn(0, 1, 2).recordsOn(0), JudgmentStatus.PASS, null, null),
				Arguments.of("FAIL", new Case().grassOn(0), JudgmentStatus.FAIL, null, null),
				Arguments.of("HOLD API_ERROR", new Case().grassFailed(HoldReason.API_ERROR), JudgmentStatus.HOLD, null,
						HoldReason.API_ERROR),
				Arguments.of("HOLD IDENTITY_MISMATCH", new Case().grassFailed(HoldReason.IDENTITY_MISMATCH),
						JudgmentStatus.HOLD, null, HoldReason.IDENTITY_MISMATCH));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("상태_사유_대응표")
	void 판정_결과의_status와_skip_reason_hold_reason은_DB_명세서_대응표를_따른다(String name, Case scenario,
			JudgmentStatus status, SkipReason skipReason, HoldReason holdReason) {
		JudgmentResult result = judge(scenario);

		assertThat(result.status()).isEqualTo(status);
		assertThat(result.skipReason()).isEqualTo(skipReason);
		assertThat(result.holdReason()).isEqualTo(holdReason);
		// 허용 조합 표를 독립적으로 다시 확인한다
		switch (result.status()) {
			case EXCLUDED -> assertThat(result.skipReason()).isIn(SkipReason.NO_TEAM, SkipReason.FIRST_WEEK);
			case EXEMPT -> assertThat(result.skipReason()).isIn(SkipReason.EXEMPTION_PERIOD,
					SkipReason.PERSONAL_EXEMPTION);
			case PASS, FAIL, HOLD -> assertThat(result.skipReason()).isNull();
		}
		if (result.status() != JudgmentStatus.HOLD) {
			assertThat(result.holdReason()).isNull();
		}
	}

	@Test
	void 제외_면제_통과_미달은_확정이고_판정_시각이_판정_실행_시각이다() {
		List<JudgmentResult> confirmed = new ArrayList<>();
		confirmed.add(judge(new Case().noTeam()));
		confirmed.add(judge(new Case().exemptionPeriod()));
		confirmed.add(judge(new Case().grassOn(0, 1, 2).recordsOn(0)));
		confirmed.add(judge(new Case()));

		assertThat(confirmed).allSatisfy(result -> {
			assertThat(result.isConfirmed()).isTrue();
			assertThat(result.judgedAt()).isEqualTo(JUDGE_TIME);
		});
	}

	private static void assertSkippedShape(JudgmentResult result) {
		assertThat(result.verifiedDays()).isNull();
		assertThat(result.recordCount()).isNull();
		assertThat(result.days()).isEmpty();
		assertThat(result.holdReason()).isNull();
		assertThat(result.judgedAt()).isEqualTo(JUDGE_TIME);
		assertThat(result.warningRequired()).isFalse();
	}

	// ---------- 주가 끝나기 전 호출 ----------

	@ParameterizedTest
	@ValueSource(strings = {"2026-09-28T00:00:00", "2026-10-01T12:00:00", "2026-10-04T12:00:00", "2026-10-04T23:59:59",
			"2026-10-04T23:59:59.999999999"})
	void 주가_끝나기_전에는_판정할_수_없다(String now) {
		JudgmentCalculator early = new JudgmentCalculator(clockAt(LocalDateTime.parse(now)));

		assertThatThrownBy(() -> early.judge(new Case().grassOn(0, 1, 2).recordsOn(0).build()))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void 다음_주_월요일_0시_정각부터_판정할_수_있다() {
		JudgmentCalculator onTime = new JudgmentCalculator(clockAt(LocalDateTime.of(2026, 10, 5, 0, 0)));

		JudgmentResult result = onTime.judge(new Case().grassOn(0, 1, 2).recordsOn(0).build());

		assertThat(result.status()).isEqualTo(JudgmentStatus.PASS);
	}

	@Test
	void 오래전_주도_판정할_수_있다() {
		JudgmentCalculator late = new JudgmentCalculator(clockAt(LocalDateTime.of(2026, 12, 25, 7, 0)));

		JudgmentResult result = late.judge(new Case().grassOn(0, 1, 2).recordsOn(0).build());

		assertThat(result.status()).isEqualTo(JudgmentStatus.PASS);
	}

	@Test
	void 시계의_시간대가_UTC여도_한국_시각으로_주_종료를_판단한다() {
		// 2026-10-04T14:59:59Z = 일요일 23:59:59 KST, 15:00:00Z = 월요일 00:00 KST
		JudgmentCalculator beforeEnd = new JudgmentCalculator(
				Clock.fixed(Instant.parse("2026-10-04T14:59:59Z"), ZoneOffset.UTC));
		JudgmentCalculator atEnd = new JudgmentCalculator(
				Clock.fixed(Instant.parse("2026-10-04T15:00:00Z"), ZoneOffset.UTC));
		JudgmentInput input = new Case().grassOn(0, 1, 2).recordsOn(0).build();

		assertThatThrownBy(() -> beforeEnd.judge(input)).isInstanceOf(IllegalStateException.class);
		assertThat(atEnd.judge(input).status()).isEqualTo(JudgmentStatus.PASS);
	}

	// ---------- 잔디 맵이 7일을 덮지 않을 때 ----------

	@ParameterizedTest
	@ValueSource(ints = {0, 1, 2, 3, 4, 5, 6})
	void 잔디_맵에서_하루라도_빠지면_0칸으로_보지_않고_거부한다(int missingIndex) {
		Map<LocalDate, Boolean> map = weekGrass(0, 1, 2);
		map.remove(day(missingIndex));
		Case c = new Case().recordsOn(0);
		c.grass = GrassLookup.available(map);

		assertThatThrownBy(() -> judge(c)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining(day(missingIndex).toString());
	}

	@Test
	void 잔디_맵이_비어_있으면_거부한다() {
		Case c = new Case().recordsOn(0, 1, 2);
		c.grass = GrassLookup.available(Map.of());

		assertThatThrownBy(() -> judge(c)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void 잔디_맵이_다른_주의_7일이면_거부한다() {
		Map<LocalDate, Boolean> otherWeek = new HashMap<>();
		for (int i = 0; i < 7; i++) {
			otherWeek.put(WEEK.minusDays(7).plusDays(i), true);
		}
		Case c = new Case().recordsOn(0);
		c.grass = GrassLookup.available(otherWeek);

		assertThatThrownBy(() -> judge(c)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void 소속_스냅샷_집합을_바꿔도_이미_만든_입력은_변하지_않는다() {
		Set<Long> teams = new HashSet<>(Set.of(TEAM_A, TEAM_B));
		Case c = new Case();
		JudgmentInput input = new JudgmentInput(WEEK, teams, null, false, false, c.grass, Map.of());

		teams.clear();

		assertThat(input.teamIdsAtWeekEnd()).containsExactlyInAnyOrder(TEAM_A, TEAM_B);
		assertThat(calculator.judge(input).skipReason()).isNull();
	}

}
