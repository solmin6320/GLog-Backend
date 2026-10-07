package com.jandilog.warning.domain;

import static com.jandilog.testsupport.warning.RecalcAssert.assertActive;
import static com.jandilog.testsupport.warning.RecalcAssert.assertBeforeBaseline;
import static com.jandilog.testsupport.warning.RecalcAssert.assertDeducted;
import static com.jandilog.testsupport.warning.RecalcAssert.assertState;
import static com.jandilog.testsupport.warning.RecalcAssert.calculated;
import static com.jandilog.testsupport.warning.RecalcAssert.holdWeeks;
import static com.jandilog.testsupport.warning.RecalcAssert.onHold;
import static com.jandilog.testsupport.warning.WeekScript.endOf;
import static com.jandilog.testsupport.warning.WeekScript.parse;
import static com.jandilog.testsupport.warning.WeekScript.week;
import static com.jandilog.testsupport.warning.WeekScript.with;
import static com.jandilog.testsupport.warning.WeekScript.weeks;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.jandilog.judgment.domain.JudgmentStatus;

// 경고 재계산 규칙 (기능명세서 6·7장, DB명세서 4-3, Q-03·Q-10). 한 글자가 한 주: P 통과 F 미달 D 미달(경고 삭제 표시) E 면제 X 제외 H 보류
class WarningRecalculatorTest {

	private static WarningRecalcResult run(String script) {
		return WarningRecalculator.recalculate(parse(script), null);
	}

	private static WarningRecalcResult run(String script, LocalDateTime fulfilledAt) {
		return WarningRecalculator.recalculate(parse(script), fulfilledAt);
	}

	@Nested
	class 경고_부여 {

		@Test
		void 판정_이력이_없으면_경고_0_연속_0이다() {
			assertState(run(""), 0, 0);
		}

		@Test
		void 미달_1개는_경고_1개다() {
			assertActive(assertState(run("F"), 1, 0), 0);
		}

		@Test
		void 미달이_쌓이면_경고도_같은_수로_쌓인다() {
			assertState(run("FF"), 2, 0);
			assertState(run("FFF"), 3, 0);
		}

		@Test
		void 미달이_3개_도달하면_벌칙_대상이다() {
			assertThat(calculated(run("FF")).penaltyTarget()).isFalse();
			assertThat(calculated(run("FFF")).penaltyTarget()).isTrue();
		}

		@Test
		void 통과만_있으면_경고가_생기지_않는다() {
			assertState(run("PPPPP"), 0, 1);
		}

		@Test
		void 미달_뒤_통과_1주는_연속_1이고_차감하지_않는다() {
			assertState(run("FFP"), 2, 1);
		}

		@Test
		void 판정_주가_사이에_끼어도_미달만_경고로_센다() {
			assertActive(assertState(run("PFPF"), 2, 0), 1, 3);
		}

	}

	@Nested
	class 통과_2주_연속_차감 {

		@Test
		void 연속_2주_통과하면_가장_오래된_경고_1개를_차감하고_연속은_0이다() {
			var result = assertState(run("FFPP"), 1, 0);
			assertActive(result, 1);
			assertDeducted(result, 0);
		}

		@Test
		void 가장_오래된_살아_있는_경고부터_차감한다() {
			var result = assertState(run("FFPPFPP"), 1, 0);
			assertActive(result, 4);
			assertDeducted(result, 0, 1);
		}

		@Test
		void 미달이_끼면_연속이_끊겨_차감되지_않는다() {
			assertState(run("FPFP"), 2, 1);
		}

		@Test
		void 연속이_끊긴_뒤_다시_2주_통과하면_그때_차감한다() {
			var result = assertState(run("FPFPP"), 1, 0);
			assertActive(result, 2);
			assertDeducted(result, 0);
		}

		@Test
		void 차감_뒤에는_연속이_0이라_다시_2주가_필요하다() {
			assertState(run("FFPPP"), 1, 1);
			var result = assertState(run("FFPPPP"), 0, 0);
			assertDeducted(result, 0, 1);
		}

		@Test
		void 삭제_표시된_경고는_차감_대상이_아니다() {
			var result = assertState(run("DFPP"), 0, 0);
			assertDeducted(result, 1);
		}

	}

	// Q-10 경계 4건: a 경고 0개 2주 연속 / b 벌칙 대상 차감 정지 / c 3개 초과 / d 이행 뒤 0
	@Nested
	class 경고_경계_Q10 {

		@Test
		void Q10_a_경고_0개에서도_2주_연속_통과하면_연속이_0으로_돌아간다() {
			assertState(run("PP"), 0, 0);
		}

		@Test
		void Q10_a_연속_0으로_돌아간_뒤에는_다시_센다() {
			assertState(run("PPP"), 0, 1);
			assertState(run("PPPP"), 0, 0);
			assertState(run("FPPP"), 0, 1);
		}

		@Test
		void Q10_b_벌칙_대상에서는_연속_통과해도_차감하지_않는다() {
			var result = assertState(run("FFFPP"), 3, 0);
			assertThat(result.deductedWarningWeeks()).isEmpty();
			assertState(run("FFFPPPP"), 3, 0);
		}

		@Test
		void Q10_b_벌칙_대상에서는_연속_카운트도_쌓지_않는다() {
			assertState(run("FFFP"), 3, 0);
			assertState(run("FFFPPP"), 3, 0);
		}

		@Test
		void Q10_b_2개에서_통과_1주_뒤_미달로_3개가_되면_연속은_0이다() {
			assertState(run("FFPF"), 3, 0);
		}

		@Test
		void Q10_c_벌칙_대상에서_또_미달하면_경고가_3을_넘는다() {
			assertState(run("FFFF"), 4, 0);
			assertState(run("FFFFF"), 5, 0);
		}

		@Test
		void Q10_c_3을_넘은_뒤에는_통과해도_차감하지_않는다() {
			var result = assertState(run("FFFFPP"), 4, 0);
			assertThat(result.deductedWarningWeeks()).isEmpty();
			assertState(run("FFFPPF"), 4, 0);
		}

		@Test
		void Q10_d_벌칙_이행_체크_뒤에는_경고_0_연속_0이다() {
			var result = assertState(run("FFF", endOf(2)), 0, 0);
			assertBeforeBaseline(result, 0, 1, 2);
		}

		@Test
		void Q10_d_이행_체크_직전에_쌓인_연속도_0이_된다() {
			assertState(run("FP", endOf(1)), 0, 0);
		}

		@Test
		void Q10_d_이행_뒤_다시_미달하면_이전_경고와_합치지_않고_처음부터_센다() {
			assertState(run("FFFF", endOf(2)), 1, 0);
			assertState(run("FFFFF", endOf(2)), 2, 0);
		}

		@Test
		void Q10_d_이행_뒤_통과_2주는_차감할_경고가_없어_연속만_0이다() {
			var result = assertState(run("FFFPP", endOf(2)), 0, 0);
			assertThat(result.deductedWarningWeeks()).isEmpty();
		}

		@Test
		void Q10_d_이행_뒤_다시_3개가_쌓이면_다시_벌칙_대상이다() {
			assertState(run("FFFFFF", endOf(2)), 3, 0);
		}

	}

	@Nested
	class 면제와_제외는_건너뛴다 {

		@Test
		void 통과_면제_통과는_연속_2로_차감한다() {
			var result = assertState(run("FPEP"), 0, 0);
			assertDeducted(result, 0);
		}

		@Test
		void 통과_제외_통과도_연속_2로_차감한다() {
			var result = assertState(run("FPXP"), 0, 0);
			assertDeducted(result, 0);
		}

		@Test
		void 면제는_연속을_끊지_않는다() {
			assertState(run("FPEEEP"), 0, 0);
			assertState(run("FPEXP"), 0, 0);
		}

		@Test
		void 면제는_연속을_늘리지_않는다() {
			assertState(run("FPE"), 1, 1);
			assertState(run("FEP"), 1, 1);
			assertState(run("FEE"), 1, 0);
			assertState(run("FPEE"), 1, 1);
		}

		@Test
		void 제외도_연속을_늘리지_않는다() {
			assertState(run("FPX"), 1, 1);
			assertState(run("FXXP"), 1, 1);
		}

		@Test
		void 면제만_이어진_이력은_경고도_연속도_없다() {
			assertState(run("EEE"), 0, 0);
			assertState(run("XXX"), 0, 0);
			assertState(run("EXEX"), 0, 0);
		}

		@Test
		void 면제는_경고를_만들지도_줄이지도_않는다() {
			assertState(run("FFEE"), 2, 0);
			assertState(run("FEF"), 2, 0);
		}

		@Test
		void 벌칙_대상에서_면제_주는_상태를_바꾸지_않는다() {
			assertState(run("FFFEPP"), 3, 0);
			assertState(run("FFFE"), 3, 0);
		}

		@Test
		void 통과_면제_통과_면제_통과는_통과_3주와_같다() {
			assertThat(run("PEPEP")).isEqualTo(run("PPP"));
			assertState(run("PEPEP"), 0, 1);
		}

		@Test
		void 소급_면제로_미달_주를_면제로_바꾸면_연속이_이어져_경고가_차감된다() {
			assertState(run("FPFP"), 2, 1);
			var result = assertState(run("FPEP"), 0, 0);
			assertDeducted(result, 0);
		}

	}

	@Nested
	class 보류_Q03 {

		@Test
		void 보류_주가_남아_있으면_숫자_없이_보류로_끝낸다() {
			assertThat(holdWeeks(run("FPH"))).isEqualTo(weeks(2));
		}

		@Test
		void 보류_주_앞뒤에_확정_주가_있어도_보류다() {
			assertThat(holdWeeks(run("HFPP"))).isEqualTo(weeks(0));
			assertThat(holdWeeks(run("FFHPP"))).isEqualTo(weeks(2));
		}

		@Test
		void 보류_주가_여러_개면_모두_오름차순으로_돌려준다() {
			assertThat(holdWeeks(run("HFHH"))).isEqualTo(weeks(0, 2, 3));
		}

		@Test
		void 보류가_면제와_함께_있어도_보류다() {
			assertThat(holdWeeks(run("EH"))).isEqualTo(weeks(1));
		}

		@Test
		void 보류가_풀려_확정되면_처음부터_다시_계산한다() {
			onHold(run("FPH"));
			assertState(run("FPP"), 0, 0);
			assertState(run("FPF"), 2, 0);
			assertState(run("FPE"), 1, 1);
		}

		@Test
		void 이행_기준선_이전의_보류는_영향이_없다() {
			assertState(run("HFF", endOf(0)), 2, 0);
		}

		@Test
		void 이행_기준선_이후의_보류는_보류다() {
			assertThat(holdWeeks(run("FFFH", endOf(2)))).isEqualTo(weeks(3));
		}

		@Test
		void 이행_기준선_이전_보류와_이후_보류가_섞이면_이후_것만_돌려준다() {
			assertThat(holdWeeks(run("HFFH", endOf(1)))).isEqualTo(weeks(3));
		}

	}

	@Nested
	class 삭제된_경고_E60 {

		@Test
		void 삭제_표시된_경고는_세지_않는다() {
			assertActive(assertState(run("FDF"), 2, 0), 0, 2);
		}

		@Test
		void 삭제_표시된_경고만_있으면_경고_0이다() {
			assertState(run("DDD"), 0, 0);
		}

		@Test
		void 삭제된_경고가_있으면_미달_3주여도_벌칙_대상이_아니다() {
			assertThat(calculated(run("FDF")).penaltyTarget()).isFalse();
			assertThat(calculated(run("FDFD")).warningCount()).isEqualTo(2);
		}

		@Test
		void 삭제된_경고를_복구하면_다시_센다() {
			assertState(run("FDF"), 2, 0);
			assertState(run("FFF"), 3, 0);
		}

		@Test
		void 삭제된_경고가_있는_회원의_다른_주차를_미달_또는_면제로_정정해도_삭제된_경고는_되살아나지_않는다() {
			String base = "FDFFE";
			for (int i = 0; i < base.length(); i++) {
				if (i == 1) {
					continue;
				}
				for (char corrected : new char[] {'F', 'E'}) {
					String changed = with(base, i, corrected);
					var result = calculated(run(changed));
					long aliveFails = changed.chars().filter(c -> c == 'F').count();
					assertThat(result.warningCount()).as("%s -> %s", base, changed).isEqualTo((int) aliveFails);
					assertThat(result.activeWarningWeeks()).as("%s", changed).doesNotContain(week(1));
				}
			}
		}

		@ParameterizedTest(name = "{0} → 경고 {1}, 연속 {2}")
		@CsvSource({
				"PDF, 1, 0",
				"EDF, 1, 0",
				"FDP, 1, 1",
				"FDE, 1, 0"
		})
		void 삭제된_경고가_있는_회원의_다른_주차를_통과로_정정한_결과(String script, int warningCount, int streak) {
			var result = assertState(run(script), warningCount, streak);
			assertThat(result.activeWarningWeeks()).doesNotContain(week(1));
		}

		@Test
		void 결정_대기_삭제_표시된_경고가_붙은_미달_주는_경고에_안_넣지만_연속은_끊는다() {
			// 연속을 끊지 않으면 F P D P 에서 통과 2주로 보고 F를 차감해 경고 0이 된다
			var result = assertState(run("FPDP"), 1, 1);
			assertActive(result, 0);
			assertThat(result.deductedWarningWeeks()).isEmpty();
		}

		@Test
		void 결정_대기_삭제_표시된_미달_주_뒤_통과_2주는_차감할_경고가_없어_연속만_0이다() {
			assertState(run("PDPP"), 0, 0);
		}

	}

	@Nested
	class 입력_처리 {

		@Test
		void 같은_주차가_두_번_들어오면_거부한다() {
			List<WeekEntry> weeks = new ArrayList<>(parse("FP"));
			weeks.add(new WeekEntry(week(1), JudgmentStatus.PASS, false));
			assertThatThrownBy(() -> WarningRecalculator.recalculate(weeks, null))
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		void 월요일이_아닌_주차는_거부한다() {
			List<WeekEntry> weeks = List.of(new WeekEntry(week(0).plusDays(1), JudgmentStatus.FAIL, false));
			assertThatThrownBy(() -> WarningRecalculator.recalculate(weeks, null))
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		void 주차나_상태가_없는_항목은_만들_수_없다() {
			assertThatThrownBy(() -> new WeekEntry(null, JudgmentStatus.PASS, false))
					.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> new WeekEntry(week(0), null, false)).isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		void 주차_순서가_뒤섞인_입력도_같은_결과다() {
			String script = "FPFPPFFPPFPE";
			var expected = run(script);
			List<WeekEntry> reversed = new ArrayList<>(parse(script));
			Collections.reverse(reversed);
			assertThat(WarningRecalculator.recalculate(reversed, null)).isEqualTo(expected);

			Random random = new Random(7);
			for (int i = 0; i < 50; i++) {
				List<WeekEntry> shuffled = new ArrayList<>(parse(script));
				Collections.shuffle(shuffled, random);
				assertThat(WarningRecalculator.recalculate(shuffled, null)).isEqualTo(expected);
			}
		}

		@Test
		void 같은_입력을_두_번_불러도_같은_결과다() {
			List<WeekEntry> weeks = parse("FFPFPPF");
			assertThat(WarningRecalculator.recalculate(weeks, null)).isEqualTo(WarningRecalculator.recalculate(weeks, null));
			assertThat(run("FFPFPPF", endOf(1))).isEqualTo(run("FFPFPPF", endOf(1)));
		}

		@Test
		void 입력_컬렉션을_바꾸지_않는다() {
			List<WeekEntry> weeks = new ArrayList<>(parse("FPFPP"));
			Collections.reverse(weeks);
			List<WeekEntry> before = List.copyOf(weeks);
			WarningRecalculator.recalculate(weeks, null);
			assertThat(weeks).isEqualTo(before);
			// 변경할 수 없는 컬렉션도 받는다
			assertThat(WarningRecalculator.recalculate(before, null)).isEqualTo(run("FPFPP"));
		}

		@Test
		void 결과_목록은_바꿀_수_없다() {
			var result = calculated(run("FFPP"));
			assertThatThrownBy(() -> result.activeWarningWeeks().add(week(9)))
					.isInstanceOf(UnsupportedOperationException.class);
			assertThatThrownBy(() -> result.deductedWarningWeeks().add(week(9)))
					.isInstanceOf(UnsupportedOperationException.class);
			assertThatThrownBy(() -> result.beforeBaselineWarningWeeks().add(week(9)))
					.isInstanceOf(UnsupportedOperationException.class);
			assertThatThrownBy(() -> onHold(run("H")).holdWeeks().add(week(9)))
					.isInstanceOf(UnsupportedOperationException.class);
		}

		@Test
		void 결과_목록은_주차_오름차순이다() {
			var result = calculated(run("FFFFPPPFFP"));
			assertThat(result.activeWarningWeeks()).isSorted();
			assertThat(result.deductedWarningWeeks()).isSorted();
		}

	}

}
