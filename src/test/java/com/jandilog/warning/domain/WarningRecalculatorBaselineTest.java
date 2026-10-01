package com.jandilog.warning.domain;

import static com.jandilog.testsupport.warning.RecalcAssert.assertActive;
import static com.jandilog.testsupport.warning.RecalcAssert.assertBeforeBaseline;
import static com.jandilog.testsupport.warning.RecalcAssert.assertSameNumbers;
import static com.jandilog.testsupport.warning.RecalcAssert.assertState;
import static com.jandilog.testsupport.warning.RecalcAssert.calculated;
import static com.jandilog.testsupport.warning.RecalcAssert.holdWeeks;
import static com.jandilog.testsupport.warning.WeekScript.endOf;
import static com.jandilog.testsupport.warning.WeekScript.parse;
import static com.jandilog.testsupport.warning.WeekScript.week;
import static com.jandilog.testsupport.warning.WeekScript.weeks;
import static com.jandilog.testsupport.warning.WeekScript.with;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

// 벌칙 이행 기준선(fulfilled_at, DB명세서 1-11). 기준선 이전 주는 나중에 고치거나 복구해도 현재 카운트에 반영하지 않는다 (E-58·E-59)
class WarningRecalculatorBaselineTest {

	private static WarningRecalcResult run(String script, LocalDateTime fulfilledAt) {
		return WarningRecalculator.recalculate(parse(script), fulfilledAt);
	}

	@Test
	void 이행_기록이_없으면_모든_주를_센다() {
		assertState(run("FFF", null), 3, 0);
	}

	@Test
	void 이행_체크_이전_경고는_세지_않고_이전_목록에_담는다() {
		var result = assertState(run("FFFFF", endOf(2)), 2, 0);
		assertActive(result, 3, 4);
		assertBeforeBaseline(result, 0, 1, 2);
	}

	@Test
	void 이행_체크_이전의_삭제_표시_경고와_면제는_이전_목록에_담지_않는다() {
		var result = assertState(run("FDEF", endOf(3)), 0, 0);
		assertBeforeBaseline(result, 0, 3);
	}

	@Test
	void 이행_이후_주가_하나도_없으면_경고_0_연속_0이다() {
		assertState(run("FFF", endOf(5)), 0, 0);
	}

	@Test
	void 이행_체크_이전에_쌓인_연속은_이행_뒤_통과에_이어지지_않는다() {
		// 이전 주를 세면 F P P 로 보고 차감해 (0,0)이 된다. 이행 뒤는 통과 1주뿐이라 (0,1)이다
		assertState(run("FPP", endOf(1)), 0, 1);
	}

	@Test
	void 이행_체크_이전의_통과는_이행_뒤_차감에_쓰이지_않는다() {
		var result = assertState(run("FFFPP", endOf(2)), 0, 0);
		assertThat(result.deductedWarningWeeks()).isEmpty();
	}

	// 아래 경계는 구현이 정한 규칙을 그대로 고정한다: 주가 끝나는 시각(다음 월요일 00:00) <= 이행 시각이면 이전 주 (결정 대기)
	static Stream<Arguments> 경계_사례() {
		return Stream.of(
				Arguments.of("주 끝 시각과 이행 시각이 같으면 그 주는 이전", endOf(2), 1, new int[] {0, 1, 2}),
				Arguments.of("이행 시각이 주 끝보다 1초 빠르면 그 주는 이후", endOf(2).minusSeconds(1), 2, new int[] {0, 1}),
				Arguments.of("이행 시각이 주 끝보다 1초 늦으면 그 주는 이전", endOf(2).plusSeconds(1), 1, new int[] {0, 1, 2}),
				Arguments.of("마지막 주 끝과 같으면 전부 이전", endOf(3), 0, new int[] {0, 1, 2, 3}),
				Arguments.of("마지막 주 끝보다 1초 빠르면 마지막 주는 이후", endOf(3).minusSeconds(1), 1, new int[] {0, 1, 2}),
				Arguments.of("주 도중에 이행하면 그 주는 이후", week(3).atTime(12, 0), 1, new int[] {0, 1, 2}),
				Arguments.of("일요일 23:59에 이행하면 그 주는 이후", endOf(2).minusMinutes(1), 2, new int[] {0, 1}),
				Arguments.of("월요일 00:00 정각에 이행하면 지난 주까지 이전", week(2).atStartOfDay(), 2, new int[] {0, 1}),
				Arguments.of("첫 주 시작과 같은 시각이면 아무것도 이전이 아니다", week(0).atStartOfDay(), 4, new int[] {}),
				Arguments.of("첫 주보다 한참 앞이면 전부 이후", week(0).minusYears(1).atStartOfDay(), 4, new int[] {}));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("경계_사례")
	void 결정_대기_이행_기준선_경계는_주_끝_시각_이하면_이전_주다(String name, LocalDateTime fulfilledAt, int warningCount,
			int[] beforeWeeks) {
		var result = assertState(run("FFFF", fulfilledAt), warningCount, 0);
		assertBeforeBaseline(result, beforeWeeks);
	}

	@Test
	void E58_이행_이전_주차를_어떻게_정정해도_현재_카운트는_그대로다() {
		String base = "FFFPF";
		var expected = run(base, endOf(2));
		assertState(expected, 1, 0);
		for (int i = 0; i <= 2; i++) {
			for (char corrected : new char[] {'P', 'F', 'E', 'X', 'D'}) {
				var actual = run(with(base, i, corrected), endOf(2));
				assertSameNumbers(actual, expected);
			}
		}
	}

	@Test
	void E58_이행_이전_주차를_통과로_정정하면_기록은_바뀌어도_경고_수는_그대로다() {
		var before = run("FFFPF", endOf(2));
		var after = run("FPFPF", endOf(2));
		assertSameNumbers(after, before);
		// 기록 쪽 목록은 정정을 반영한다
		assertBeforeBaseline((WarningRecalcResult.Calculated) before, 0, 1, 2);
		assertBeforeBaseline((WarningRecalcResult.Calculated) after, 0, 2);
	}

	@Test
	void E58_이행_이전_통과를_미달로_정정해도_이행_뒤_연속과_차감은_그대로다() {
		var before = run("PPPFPP", endOf(2));
		var after = run("PFPFPP", endOf(2));
		assertSameNumbers(after, before);
		assertState(after, 0, 0);
	}

	@Test
	void E59_이행_이전_경고를_복구해도_경고_수는_그대로이고_기록은_되살아난다() {
		var deleted = run("FDFFP", endOf(2));
		var restored = run("FFFFP", endOf(2));
		assertSameNumbers(restored, deleted);
		assertState(restored, 1, 1);
		assertBeforeBaseline((WarningRecalcResult.Calculated) deleted, 0, 2);
		assertBeforeBaseline((WarningRecalcResult.Calculated) restored, 0, 1, 2);
	}

	@Test
	void 이행_이후_주차를_정정하면_반영된다() {
		String base = "FFFFP";
		assertState(run(base, endOf(2)), 1, 1);
		assertState(run(with(base, 4, 'F'), endOf(2)), 2, 0);
		assertState(run(with(base, 3, 'P'), endOf(2)), 0, 0);
		assertState(run(with(base, 3, 'E'), endOf(2)), 0, 1);
	}

	@Test
	void 이행_이후_삭제된_경고를_복구하면_센다() {
		assertState(run("FFFDP", endOf(2)), 0, 1);
		assertState(run("FFFFP", endOf(2)), 1, 1);
	}

	@Test
	void 이행_이후_소급_면제는_반영된다() {
		assertState(run("FFFFPF", endOf(2)), 2, 0);
		assertState(run("FFFEPF", endOf(2)), 1, 0);
	}

	@Test
	void 이행_뒤_새_경고는_이전_경고와_합쳐지지_않아_벌칙_대상이_다시_되려면_3개가_필요하다() {
		assertThat(calculated(run("FFFFF", endOf(2))).penaltyTarget()).isFalse();
		assertThat(calculated(run("FFFFFF", endOf(2))).penaltyTarget()).isTrue();
	}

	@Test
	void 이행_기준선_이전_보류는_이행_이후_보류와_구별된다() {
		assertState(run("FHFF", endOf(1)), 2, 0);
		assertThat(holdWeeks(run("FFHFF", endOf(1)))).isEqualTo(weeks(2));
		// 이행한 바로 그 주가 끝난 뒤에 있는 보류는 이행 이후 주다
		assertThat(holdWeeks(run("FFFH", endOf(2)))).isEqualTo(weeks(3));
	}

}
