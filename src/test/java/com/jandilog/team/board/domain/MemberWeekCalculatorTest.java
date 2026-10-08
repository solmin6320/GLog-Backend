package com.jandilog.team.board.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.jandilog.judgment.domain.DayResult;
import com.jandilog.judgment.domain.JudgmentDay;
import com.jandilog.judgment.domain.JudgmentResult;
import com.jandilog.judgment.domain.JudgmentStatus;
import com.jandilog.judgment.domain.SkipReason;
import com.jandilog.judgment.domain.WeeklyJudgment;
import com.jandilog.judgment.dto.GrassDay;
import com.jandilog.judgment.dto.GrassSnapshot;

// 현황판 한 주 계산 규칙 (기능명세서 2장·5장, 화면설계서 TM-05 ⑥). Spring·DB 없이 값만으로 본다.
// 주는 2026-12-07(월)~12-13(일)
class MemberWeekCalculatorTest {

	private static final LocalDate MON = LocalDate.of(2026, 12, 7);
	private static final LocalDate TUE = MON.plusDays(1);
	private static final LocalDate WED = MON.plusDays(2);
	private static final LocalDate THU = MON.plusDays(3);
	private static final LocalDate SUN = MON.plusDays(6);
	private static final LocalDateTime FETCHED = LocalDateTime.of(2026, 12, 9, 6, 0);

	// from~to를 빠짐없이 채우고 grassDays에 든 날만 1칸
	private static GrassSnapshot snapshot(LocalDate from, LocalDate to, Set<LocalDate> grassDays) {
		List<GrassDay> days = new ArrayList<>();
		for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
			days.add(new GrassDay(day, grassDays.contains(day) ? 3 : 0));
		}
		return new GrassSnapshot(1L, from, to, FETCHED, days);
	}

	// 지난 주 월요일부터 일요일까지 덮는 캐시
	private static GrassSnapshot fullWeek(Set<LocalDate> grassDays) {
		return snapshot(MON.minusDays(7), SUN, grassDays);
	}

	// ----- 진행 중인 주: 인증일·기록글 기준 -----

	@Test
	void 인증일_3일과_기록글_1개를_정확히_채우면_통과다() {
		MemberWeek week = MemberWeekCalculator.live(MON, fullWeek(Set.of(MON, TUE)), Map.of(WED, 1));

		assertThat(week.verifiedDays()).isEqualTo(3);
		assertThat(week.recordCount()).isEqualTo(1);
		assertThat(week.status()).isEqualTo(TeamBoardStatus.PASS);
	}

	@Test
	void 인증일이_2일이면_기록글이_있어도_아직이다() {
		MemberWeek week = MemberWeekCalculator.live(MON, fullWeek(Set.of(MON, TUE)), Map.of(MON, 1));

		assertThat(week.verifiedDays()).isEqualTo(2);
		assertThat(week.recordCount()).isEqualTo(1);
		assertThat(week.status()).isEqualTo(TeamBoardStatus.NOT_YET);
	}

	@Test
	void 잔디만_3일이고_기록글이_없으면_아직이다() {
		MemberWeek week = MemberWeekCalculator.live(MON, fullWeek(Set.of(MON, TUE, WED)), Map.of());

		assertThat(week.verifiedDays()).isEqualTo(3);
		assertThat(week.recordCount()).isZero();
		assertThat(week.status()).isEqualTo(TeamBoardStatus.NOT_YET);
	}

	@Test
	void 잔디가_0칸이어도_기록글을_3일_쓰면_통과다() {
		MemberWeek week = MemberWeekCalculator.live(MON, fullWeek(Set.of()), Map.of(MON, 1, WED, 1, THU, 1));

		assertThat(week.verifiedDays()).isEqualTo(3);
		assertThat(week.recordCount()).isEqualTo(3);
		assertThat(week.status()).isEqualTo(TeamBoardStatus.PASS);
	}

	@Test
	void 같은_날_잔디와_기록글이_둘_다_있어도_인증일은_하루다() {
		MemberWeek week = MemberWeekCalculator.live(MON, fullWeek(Set.of(MON, TUE)), Map.of(MON, 1, TUE, 1));

		assertThat(week.verifiedDays()).isEqualTo(2);
		assertThat(week.recordCount()).isEqualTo(2);
		assertThat(week.days().get(0).verified()).isTrue();
		assertThat(week.status()).isEqualTo(TeamBoardStatus.NOT_YET);
	}

	@Test
	void 같은_날_기록글이_여럿이면_기록글_수는_그대로_세고_인증일은_하루다() {
		MemberWeek week = MemberWeekCalculator.live(MON, fullWeek(Set.of(MON, TUE)), Map.of(WED, 3));

		assertThat(week.recordCount()).isEqualTo(3);
		assertThat(week.verifiedDays()).isEqualTo(3);
		assertThat(week.days().get(2).hasRecord()).isTrue();
	}

	@Test
	void 일요일에_세_번째_인증일이_생기면_통과다() {
		MemberWeek week = MemberWeekCalculator.live(MON, fullWeek(Set.of(MON, TUE, SUN)), Map.of(SUN, 1));

		assertThat(week.days()).hasSize(7);
		assertThat(week.days().get(0).date()).isEqualTo(MON);
		assertThat(week.days().get(6).date()).isEqualTo(SUN);
		assertThat(week.verifiedDays()).isEqualTo(3);
		assertThat(week.status()).isEqualTo(TeamBoardStatus.PASS);
	}

	@Test
	void 이번_주_밖의_날짜에_쓴_기록글은_세지_않는다() {
		MemberWeek week = MemberWeekCalculator.live(MON, fullWeek(Set.of(MON, TUE, WED)),
				Map.of(MON.minusDays(1), 2, SUN.plusDays(1), 2));

		assertThat(week.recordCount()).isZero();
		assertThat(week.status()).isEqualTo(TeamBoardStatus.NOT_YET);
	}

	@Test
	void 진행_중인_주는_미달로_내지_않는다() {
		MemberWeek week = MemberWeekCalculator.live(MON, fullWeek(Set.of()), Map.of());

		assertThat(week.verifiedDays()).isZero();
		assertThat(week.status()).isEqualTo(TeamBoardStatus.NOT_YET);
	}

	// ----- 잔디 캐시가 없거나 구간이 모자랄 때 -----

	@Test
	void 잔디_캐시가_없으면_인증일은_알_수_없고_기록글이_있는_날만_인증일로_보인다() {
		MemberWeek week = MemberWeekCalculator.live(MON, null, Map.of(TUE, 1));

		assertThat(week.verifiedDays()).isNull();
		assertThat(week.grassFetchedAt()).isNull();
		assertThat(week.days()).allSatisfy(day -> assertThat(day.hasGrass()).isNull());
		assertThat(week.days().get(1).verified()).isTrue();
		assertThat(week.days().get(0).verified()).isNull();
		assertThat(week.recordCount()).isEqualTo(1);
		assertThat(week.status()).isEqualTo(TeamBoardStatus.NOT_YET);
	}

	@Test
	void 잔디_캐시가_없어도_기록글만으로_이미_채웠으면_통과다() {
		MemberWeek week = MemberWeekCalculator.live(MON, null, Map.of(MON, 1, TUE, 1, WED, 1));

		assertThat(week.verifiedDays()).isNull();
		assertThat(week.status()).isEqualTo(TeamBoardStatus.PASS);
	}

	@Test
	void 캐시_구간이_이번_주_월요일보다_늦게_시작하면_없는_것으로_본다() {
		GrassSnapshot late = snapshot(TUE, SUN, Set.of(TUE, WED, THU));

		MemberWeek week = MemberWeekCalculator.live(MON, late, Map.of());

		assertThat(week.verifiedDays()).isNull();
		assertThat(week.grassFetchedAt()).isNull();
		assertThat(week.days()).allSatisfy(day -> assertThat(day.hasGrass()).isNull());
	}

	@Test
	void 캐시_구간_끝_뒤의_날은_잔디를_알_수_없어_null이고_인증일에_세지_않는다() {
		// 수요일까지 갱신된 캐시: 목~일은 아직 모르는 날
		GrassSnapshot untilWed = snapshot(MON.minusDays(7), WED, Set.of(MON, TUE));

		MemberWeek week = MemberWeekCalculator.live(MON, untilWed, Map.of());

		assertThat(week.days().get(1).hasGrass()).isTrue();
		assertThat(week.days().get(2).hasGrass()).isFalse();
		assertThat(week.days().get(3).hasGrass()).isNull();
		assertThat(week.days().get(3).verified()).isNull();
		assertThat(week.verifiedDays()).isEqualTo(2);
		assertThat(week.grassFetchedAt()).isEqualTo(FETCHED);
	}

	@Test
	void 캐시_구간_끝_뒤의_날에_쓴_기록글은_인증일로_센다() {
		GrassSnapshot untilWed = snapshot(MON.minusDays(7), WED, Set.of(MON, TUE));

		MemberWeek week = MemberWeekCalculator.live(MON, untilWed, Map.of(THU, 1));

		assertThat(week.days().get(3).hasGrass()).isNull();
		assertThat(week.days().get(3).verified()).isTrue();
		assertThat(week.verifiedDays()).isEqualTo(3);
		assertThat(week.status()).isEqualTo(TeamBoardStatus.PASS);
	}

	// ----- BoardDay 인증 표기 -----

	@Test
	void 요일_칸의_인증_표기는_잔디_또는_기록글_둘_다_없는데_모르면_null이다() {
		assertThat(new BoardDay(MON, true, false).verified()).isTrue();
		assertThat(new BoardDay(MON, false, true).verified()).isTrue();
		assertThat(new BoardDay(MON, null, true).verified()).isTrue();
		assertThat(new BoardDay(MON, false, false).verified()).isFalse();
		assertThat(new BoardDay(MON, null, false).verified()).isNull();
	}

	@Test
	void 상태만_바꾸면_나머지_값은_그대로다() {
		MemberWeek week = MemberWeekCalculator.live(MON, fullWeek(Set.of(MON)), Map.of(TUE, 1));

		MemberWeek changed = week.withStatus(TeamBoardStatus.EXCLUDED);

		assertThat(changed.status()).isEqualTo(TeamBoardStatus.EXCLUDED);
		assertThat(changed.days()).isEqualTo(week.days());
		assertThat(changed.verifiedDays()).isEqualTo(week.verifiedDays());
		assertThat(changed.recordCount()).isEqualTo(week.recordCount());
		assertThat(changed.grassFetchedAt()).isEqualTo(week.grassFetchedAt());
	}

	// ----- 확정된 주 -----

	private static WeeklyJudgment judged(boolean pass, int verified, int records) {
		return WeeklyJudgment.of(1L, MON, JudgmentResult.judged(pass, verified, records, List.of(),
				SUN.plusDays(1).atTime(7, 0)));
	}

	private static List<JudgmentDay> storedDays(Set<LocalDate> grassDays, Set<LocalDate> recordDays) {
		List<JudgmentDay> days = new ArrayList<>();
		for (int i = 0; i < 7; i++) {
			LocalDate day = MON.plusDays(i);
			days.add(JudgmentDay.of(1L, new DayResult(day, grassDays.contains(day), recordDays.contains(day))));
		}
		return days;
	}

	@Test
	void 확정된_미달은_저장한_인증일과_기록글_수와_근거를_그대로_쓴다() {
		MemberWeek week = MemberWeekCalculator.confirmed(judged(false, 1, 0), storedDays(Set.of(TUE), Set.of()));

		assertThat(week.status()).isEqualTo(TeamBoardStatus.FAIL);
		assertThat(week.verifiedDays()).isEqualTo(1);
		assertThat(week.recordCount()).isZero();
		assertThat(week.grassFetchedAt()).isNull();
		assertThat(week.days()).hasSize(7);
		assertThat(week.days().get(1).hasGrass()).isTrue();
		assertThat(week.days().get(0).hasGrass()).isFalse();
	}

	@Test
	void 확정된_통과도_저장한_값을_쓰고_근거는_날짜순으로_정렬한다() {
		List<JudgmentDay> shuffled = storedDays(Set.of(MON, TUE, WED), Set.of(THU));
		Collections.reverse(shuffled);

		MemberWeek week = MemberWeekCalculator.confirmed(judged(true, 4, 1), shuffled);

		assertThat(week.status()).isEqualTo(TeamBoardStatus.PASS);
		assertThat(week.days().get(0).date()).isEqualTo(MON);
		assertThat(week.days().get(6).date()).isEqualTo(SUN);
		assertThat(week.days().get(3).hasRecord()).isTrue();
	}

	@Test
	void 근거가_7일이_아니면_확정_결과로_쓰지_않는다() {
		List<JudgmentDay> sixDays = storedDays(Set.of(MON), Set.of()).subList(0, 6);

		assertThat(MemberWeekCalculator.confirmed(judged(true, 3, 1), sixDays)).isNull();
		assertThat(MemberWeekCalculator.confirmed(judged(true, 3, 1), List.of())).isNull();
	}

	@Test
	void 면제_제외_보류는_통과_미달이_아니라서_확정_근거로_쓰지_않는다() {
		LocalDateTime now = SUN.plusDays(1).atTime(7, 0);
		WeeklyJudgment exempt = WeeklyJudgment.of(1L, MON, JudgmentResult.skipped(SkipReason.PERSONAL_EXEMPTION, now));
		WeeklyJudgment excluded = WeeklyJudgment.of(1L, MON, JudgmentResult.skipped(SkipReason.FIRST_WEEK, now));
		WeeklyJudgment hold = WeeklyJudgment.of(1L, MON,
				JudgmentResult.hold(com.jandilog.judgment.domain.HoldReason.API_ERROR));

		assertThat(MemberWeekCalculator.confirmed(exempt, storedDays(Set.of(), Set.of()))).isNull();
		assertThat(MemberWeekCalculator.confirmed(excluded, storedDays(Set.of(), Set.of()))).isNull();
		assertThat(MemberWeekCalculator.confirmed(hold, storedDays(Set.of(), Set.of()))).isNull();
	}

	@Test
	void 판정_결과는_현황판_상태로_옮기고_면제와_제외는_모두_제외이며_보류는_표기할_결과가_없다() {
		assertThat(MemberWeekCalculator.statusOf(JudgmentStatus.PASS)).isEqualTo(TeamBoardStatus.PASS);
		assertThat(MemberWeekCalculator.statusOf(JudgmentStatus.FAIL)).isEqualTo(TeamBoardStatus.FAIL);
		assertThat(MemberWeekCalculator.statusOf(JudgmentStatus.EXEMPT)).isEqualTo(TeamBoardStatus.EXCLUDED);
		assertThat(MemberWeekCalculator.statusOf(JudgmentStatus.EXCLUDED)).isEqualTo(TeamBoardStatus.EXCLUDED);
		assertThat(MemberWeekCalculator.statusOf(JudgmentStatus.HOLD)).isNull();
	}

}
