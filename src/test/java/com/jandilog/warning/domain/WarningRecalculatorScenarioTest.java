package com.jandilog.warning.domain;

import static com.jandilog.testsupport.warning.RecalcAssert.assertActive;
import static com.jandilog.testsupport.warning.RecalcAssert.assertBeforeBaseline;
import static com.jandilog.testsupport.warning.RecalcAssert.assertDeducted;
import static com.jandilog.testsupport.warning.RecalcAssert.assertState;
import static com.jandilog.testsupport.warning.RecalcAssert.holdWeeks;
import static com.jandilog.testsupport.warning.WeekScript.endOf;
import static com.jandilog.testsupport.warning.WeekScript.parse;
import static com.jandilog.testsupport.warning.WeekScript.week;
import static com.jandilog.testsupport.warning.WeekScript.weeks;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

// 여러 주에 걸친 실제 흐름. 한 글자가 한 주: P 통과 F 미달 D 미달(경고 삭제 표시) E 면제 X 제외 H 보류
class WarningRecalculatorScenarioTest {

	private static WarningRecalcResult run(String script) {
		return WarningRecalculator.recalculate(parse(script), null);
	}

	private static WarningRecalcResult run(String script, LocalDateTime fulfilledAt) {
		return WarningRecalculator.recalculate(parse(script), fulfilledAt);
	}

	@Test
	void 벌칙_대상이_미달_주를_면제로_정정받아_2개로_내려가면_다음_통과부터_차감이_다시_시작된다() {
		assertState(run("FFFPP"), 3, 0);

		// 첫 미달을 면제로 정정: 경고 2개라 벌칙 대상이 아니다
		var result = assertState(run("EFFPP"), 1, 0);
		assertActive(result, 2);
		assertDeducted(result, 1);
	}

	@Test
	void 벌칙_대상이_통과로_정정받으면_그_주부터_연속이_이어져_차감된다() {
		var result = assertState(run("FFPPP"), 1, 1);
		assertActive(result, 1);
		assertDeducted(result, 0);
	}

	@Test
	void 벌칙_대상에서_통과가_길어도_이행_전에는_경고가_줄지_않는다() {
		assertState(run("FFFPPPPPPPP"), 3, 0);
	}

	@Test
	void 벌칙_이행_뒤_다시_시작해_통과_2주는_연속만_되돌리고_미달은_새_경고가_된다() {
		// 3주 미달 -> 이행(화요일) -> 통과 2주 -> 미달
		LocalDateTime fulfilledAt = endOf(2).plusDays(1).plusHours(14);
		var result = assertState(run("FFFPPF", fulfilledAt), 1, 0);
		assertActive(result, 5);
		assertBeforeBaseline(result, 0, 1, 2);
		assertThat(result.deductedWarningWeeks()).isEmpty();
	}

	@Test
	void 이행_뒤_3개가_다시_쌓이면_다시_벌칙_대상이고_통과해도_차감하지_않는다() {
		LocalDateTime fulfilledAt = endOf(2).plusHours(10);
		assertState(run("FFFFFFPP", fulfilledAt), 3, 0);
	}

	@Test
	void 면제_기간이_4주_이어져도_앞뒤_통과가_이어져_차감한다() {
		var result = assertState(run("FPEEEEP"), 0, 0);
		assertDeducted(result, 0);
	}

	@Test
	void 첫_참가_주_제외_뒤_통과_2주는_경고가_없어_연속만_0으로_돌아간다() {
		assertState(run("XPP"), 0, 0);
		assertState(run("XPPF"), 1, 0);
	}

	@Test
	void 통과가_계속되면_2주마다_연속이_0으로_돌아간다() {
		assertState(run("P"), 0, 1);
		assertState(run("PP"), 0, 0);
		assertState(run("PPP"), 0, 1);
		assertState(run("PPPP"), 0, 0);
		assertState(run("PPPPF"), 1, 0);
	}

	@Test
	void 경고_0개에서_면제를_끼워도_통과_2주면_연속이_0으로_돌아간다() {
		assertState(run("PEP"), 0, 0);
		assertState(run("PEEP"), 0, 0);
		assertState(run("PXP"), 0, 0);
	}

	@Test
	void 보류가_풀리기_전에는_계산하지_않고_풀리면_확정된_이력으로_계산한다() {
		assertThat(holdWeeks(run("FFPH"))).isEqualTo(weeks(3));
		// 보류 주가 통과로 확정: 2주 연속 통과로 차감
		var passed = assertState(run("FFPP"), 1, 0);
		assertDeducted(passed, 0);
		// 보류 주가 미달로 확정: 연속이 끊기고 경고 3개
		assertState(run("FFPF"), 3, 0);
		// 보류 주가 면제로 확정: 건너뛰므로 연속 1
		assertState(run("FFPE"), 2, 1);
	}

	@Test
	void 보류가_여러_주에_걸쳐_있으면_모두_풀려야_계산한다() {
		assertThat(holdWeeks(run("FHPH"))).isEqualTo(weeks(1, 3));
		assertThat(holdWeeks(run("FPPH"))).isEqualTo(weeks(3));
		assertState(run("FPPP"), 0, 1);
	}

	@Test
	void 가장_최근_이행_이후로_다시_세므로_이행_기준선을_늦추면_결과가_바뀐다() {
		// 같은 이력도 기준선이 뒤로 가면 이후 주가 줄어든다 (이행을 두 번 한 회원은 마지막 이행이 기준선)
		var first = assertState(run("FFFFFF", endOf(2)), 3, 0);
		assertActive(first, 3, 4, 5);
		var second = assertState(run("FFFFFF", endOf(5)), 0, 0);
		assertBeforeBaseline(second, 0, 1, 2, 3, 4, 5);
	}

	@Test
	void 이행_직전에_삭제_표시된_경고를_이행_뒤에_복구해도_이전_주차라_기록만_되살아난다() {
		LocalDateTime fulfilledAt = endOf(2);
		var deleted = run("FFDFP", fulfilledAt);
		assertBeforeBaseline(assertState(deleted, 1, 1), 0, 1);

		var restored = run("FFFFP", fulfilledAt);
		assertBeforeBaseline(assertState(restored, 1, 1), 0, 1, 2);
	}

	@Test
	void 판정_행이_없는_빈_주는_세지_않고_건너뛴다() {
		var result = assertState(run("F.F.PP"), 1, 0);
		assertActive(result, 2);
		assertDeducted(result, 0);
		assertState(run("..F.F"), 2, 0);
	}

	@Test
	void 첫_주가_FIRST와_달라도_주차_순서만_따른다() {
		var entries = parse("FFPP");
		var shifted = entries.stream()
				.map(e -> new WeekEntry(e.weekStart().plusWeeks(40), e.status(), e.warningDeleted())).toList();
		var result = WarningRecalculator.recalculate(shifted, null);
		assertThat(result).isInstanceOf(WarningRecalcResult.Calculated.class);
		assertThat(((WarningRecalcResult.Calculated) result).activeWarningWeeks()).containsExactly(week(41));
		assertThat(((WarningRecalcResult.Calculated) result).deductedWarningWeeks()).containsExactly(week(40));
	}

}
