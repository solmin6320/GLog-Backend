package com.jandilog.warning.domain;

import static com.jandilog.testsupport.warning.RecalcAssert.assertSameNumbers;
import static com.jandilog.testsupport.warning.WeekScript.endOf;
import static com.jandilog.testsupport.warning.WeekScript.parse;
import static com.jandilog.testsupport.warning.WeekScript.week;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import com.jandilog.testsupport.warning.WeekScript;
import com.jandilog.warning.domain.WarningRecalcResult.Calculated;
import com.jandilog.warning.domain.WarningRecalcResult.OnHold;

// 무작위 이력으로 재계산의 성질을 본다. 시드가 고정돼 있어 실패하면 같은 입력으로 다시 재현된다.
// 표기: P 통과 F 미달 D 미달(경고 삭제 표시) E 면제 X 제외 H 보류 . 판정 행 없음
class WarningRecalculatorPropertyTest {

	private static final int CASES = 4000;
	private static final long SEED = 20261001L;
	private static final String POOL = "PPPPPPFFFFFDEX";
	private static final String POOL_WITH_HOLD = "PPPPPPFFFFFDEXH";

	private record Case(String script, LocalDateTime baseline) {

		@Override
		public String toString() {
			return "이력=" + script + " 기준선=" + baseline;
		}

	}

	private static void forEachCase(boolean withHold, Consumer<Case> check) {
		for (int i = 0; i < CASES; i++) {
			Random random = new Random(SEED + i);
			check.accept(randomCase(random, withHold, true));
		}
	}

	private static Case randomCase(Random random, boolean withHold, boolean anyBaseline) {
		int length = random.nextInt(17);
		String pool = withHold ? POOL_WITH_HOLD : POOL;
		StringBuilder script = new StringBuilder();
		for (int i = 0; i < length; i++) {
			script.append(random.nextInt(20) == 0 ? '.' : pool.charAt(random.nextInt(pool.length())));
		}
		return new Case(script.toString(), randomBaseline(random, length, anyBaseline));
	}

	// 주 경계, 경계 +-1초, 주 도중 어디든 나올 수 있다
	private static LocalDateTime randomBaseline(Random random, int length, boolean anyBaseline) {
		int kind = random.nextInt(6);
		if (kind <= 1 || length == 0) {
			return null;
		}
		int last = anyBaseline ? length : length - 1;
		int k = random.nextInt(last + 2) - 1;
		return switch (kind) {
			case 2 -> endOf(k);
			case 3 -> endOf(k).plusSeconds(1);
			case 4 -> endOf(k).minusSeconds(1);
			default -> week(Math.max(k, 0)).atTime(random.nextInt(24), random.nextInt(60));
		};
	}

	private static boolean beforeBaseline(int index, LocalDateTime baseline) {
		return baseline != null && endOf(index).compareTo(baseline) <= 0;
	}

	private static WarningRecalcResult run(Case c) {
		return WarningRecalculator.recalculate(parse(c.script()), c.baseline());
	}

	// 규칙을 한 번 더 따로 옮긴 참조 구현. 연속 통과를 0/1 두 상태로만 들고 간다.
	// 삭제 표시된 미달(D)이 연속을 끊는다는 해석은 구현을 따른 것이며 결정 대기다
	private record Reference(List<Integer> hold, List<Integer> active, List<Integer> deducted, int streak) {
	}

	private static Reference reference(Case c) {
		List<Integer> hold = new ArrayList<>();
		TreeSet<Integer> alive = new TreeSet<>();
		List<Integer> deducted = new ArrayList<>();
		boolean onePass = false;
		String script = c.script();
		for (int i = 0; i < script.length(); i++) {
			char symbol = script.charAt(i);
			if (symbol == '.' || beforeBaseline(i, c.baseline())) {
				continue;
			}
			switch (symbol) {
				case 'E', 'X' -> {
				}
				case 'H' -> hold.add(i);
				case 'F' -> {
					alive.add(i);
					onePass = false;
				}
				case 'D' -> onePass = false;
				case 'P' -> {
					if (alive.size() >= 3) {
						onePass = false;
					}
					else if (onePass) {
						if (!alive.isEmpty()) {
							deducted.add(alive.pollFirst());
						}
						onePass = false;
					}
					else {
						onePass = true;
					}
				}
				default -> throw new IllegalStateException(String.valueOf(symbol));
			}
		}
		return new Reference(hold, new ArrayList<>(alive), deducted, onePass ? 1 : 0);
	}

	private static List<LocalDate> dates(List<Integer> indexes) {
		return indexes.stream().map(WeekScript::week).toList();
	}

	@Test
	void 무작위_이력에서_참조_구현과_같은_결과를_낸다() {
		forEachCase(true, c -> {
			Reference expected = reference(c);
			WarningRecalcResult actual = run(c);
			if (!expected.hold().isEmpty()) {
				assertThat(actual).as("%s", c).isEqualTo(new OnHold(dates(expected.hold())));
				return;
			}
			Calculated calculated = (Calculated) actual;
			assertThat(calculated.activeWarningWeeks()).as("%s 남은 경고", c).isEqualTo(dates(expected.active()));
			assertThat(calculated.deductedWarningWeeks()).as("%s 차감", c).isEqualTo(dates(expected.deducted()));
			assertThat(calculated.streak()).as("%s 연속", c).isEqualTo(expected.streak());
			assertThat(calculated.warningCount()).as("%s 경고 수", c).isEqualTo(expected.active().size());
			assertThat(calculated.penaltyTarget()).as("%s 벌칙 대상", c).isEqualTo(expected.active().size() >= 3);
		});
	}

	@Test
	void 어떤_이력에서도_결과가_서로_모순되지_않는다() {
		forEachCase(false, c -> {
			Calculated r = (Calculated) run(c);
			assertThat(r.warningCount()).as("%s", c).isEqualTo(r.activeWarningWeeks().size());
			assertThat(r.penaltyTarget()).as("%s", c).isEqualTo(r.warningCount() >= 3);
			// 연속은 2가 되면 바로 0이 되므로 0 또는 1이다
			assertThat(r.streak()).as("%s", c).isIn(0, 1);
			// 벌칙 대상에서는 연속을 쌓지 않는다
			if (r.penaltyTarget()) {
				assertThat(r.streak()).as("%s", c).isZero();
			}
			// 이행 기준선 이후의 살아 있는 미달은 남았거나 차감됐거나 둘 중 하나다
			TreeSet<LocalDate> aliveAfter = new TreeSet<>();
			TreeSet<LocalDate> aliveBefore = new TreeSet<>();
			for (int i = 0; i < c.script().length(); i++) {
				if (c.script().charAt(i) == 'F') {
					(beforeBaseline(i, c.baseline()) ? aliveBefore : aliveAfter).add(week(i));
				}
			}
			TreeSet<LocalDate> accounted = new TreeSet<>(r.activeWarningWeeks());
			accounted.addAll(r.deductedWarningWeeks());
			assertThat(accounted).as("%s 남은+차감", c).isEqualTo(aliveAfter);
			assertThat(Collections.disjoint(r.activeWarningWeeks(), r.deductedWarningWeeks()))
					.as("%s 남은과 차감은 겹치지 않음", c).isTrue();
			assertThat(r.beforeBaselineWarningWeeks()).as("%s 기준선 이전", c).isEqualTo(new ArrayList<>(aliveBefore));
			// 가장 오래된 것부터 차감하므로 차감된 주는 모두 남은 경고보다 앞이다
			if (!r.deductedWarningWeeks().isEmpty() && !r.activeWarningWeeks().isEmpty()) {
				assertThat(r.deductedWarningWeeks().get(r.deductedWarningWeeks().size() - 1))
						.as("%s", c).isBefore(r.activeWarningWeeks().get(0));
			}
			assertThat(r.activeWarningWeeks()).as("%s", c).isSorted();
			assertThat(r.deductedWarningWeeks()).as("%s", c).isSorted();
		});
	}

	@Test
	void 주차_입력_순서가_어떻게_뒤섞여도_결과가_같다() {
		forEachCase(true, c -> {
			WarningRecalcResult expected = run(c);
			List<WeekEntry> entries = new ArrayList<>(parse(c.script()));
			Random shuffler = new Random(c.hashCode());
			for (int i = 0; i < 3; i++) {
				Collections.shuffle(entries, shuffler);
				assertThat(WarningRecalculator.recalculate(entries, c.baseline())).as("%s", c).isEqualTo(expected);
			}
		});
	}

	@Test
	void 같은_입력을_몇_번을_불러도_다른_입력_사이에_끼어도_같은_결과다() {
		Random random = new Random(SEED);
		for (int i = 0; i < 500; i++) {
			Case c = randomCase(new Random(SEED + i), true, true);
			Case other = randomCase(random, true, true);
			WarningRecalcResult first = run(c);
			run(other);
			assertThat(run(c)).as("%s", c).isEqualTo(first);
			assertThat(run(c)).as("%s", c).isEqualTo(first);
		}
	}

	@Test
	void 빈_주에_면제나_제외를_끼워도_뒤에_붙여도_결과가_같다() {
		forEachCase(true, c -> {
			WarningRecalcResult expected = run(c);
			Random random = new Random(c.hashCode());
			StringBuilder changed = new StringBuilder(c.script());
			for (int i = 0; i < changed.length(); i++) {
				if (changed.charAt(i) == '.') {
					changed.setCharAt(i, random.nextBoolean() ? 'E' : 'X');
				}
			}
			int extra = random.nextInt(4);
			for (int i = 0; i < extra; i++) {
				changed.append(random.nextBoolean() ? 'E' : 'X');
			}
			assertThat(WarningRecalculator.recalculate(parse(changed.toString()), c.baseline()))
					.as("%s -> %s", c, changed).isEqualTo(expected);
		});
	}

	@Test
	void 이행_기준선_이전_주는_무엇으로_바꿔도_숫자_결과가_그대로다() {
		forEachCase(true, c -> {
			if (c.baseline() == null) {
				return;
			}
			WarningRecalcResult expected = run(c);
			Random random = new Random(c.hashCode());
			StringBuilder changed = new StringBuilder(c.script());
			for (int i = 0; i < changed.length(); i++) {
				if (beforeBaseline(i, c.baseline())) {
					changed.setCharAt(i, POOL_WITH_HOLD.charAt(random.nextInt(POOL_WITH_HOLD.length())));
				}
			}
			WarningRecalcResult actual = WarningRecalculator.recalculate(parse(changed.toString()), c.baseline());
			if (expected instanceof OnHold hold) {
				assertThat(actual).as("%s -> %s", c, changed).isEqualTo(hold);
			}
			else {
				assertSameNumbers(actual, expected);
			}
		});
	}

	@Test
	void 이행_기준선_이후에_보류가_있으면_보류이고_없으면_계산된다() {
		forEachCase(true, c -> {
			List<Integer> holdAfter = new ArrayList<>();
			for (int i = 0; i < c.script().length(); i++) {
				if (c.script().charAt(i) == 'H' && !beforeBaseline(i, c.baseline())) {
					holdAfter.add(i);
				}
			}
			WarningRecalcResult actual = run(c);
			if (holdAfter.isEmpty()) {
				assertThat(actual).as("%s", c).isInstanceOf(Calculated.class);
			}
			else {
				assertThat(actual).as("%s", c).isEqualTo(new OnHold(dates(holdAfter)));
			}
		});
	}

	@Test
	void 맨_끝에_미달을_붙이면_경고는_정확히_하나_늘고_연속은_0이다() {
		for (int i = 0; i < CASES; i++) {
			Case c = randomCase(new Random(SEED + i), false, false);
			Calculated before = (Calculated) run(c);
			Calculated after = (Calculated) WarningRecalculator.recalculate(parse(c.script() + "F"), c.baseline());
			assertThat(after.warningCount()).as("%s", c).isEqualTo(before.warningCount() + 1);
			assertThat(after.streak()).as("%s", c).isZero();
		}
	}

	@Test
	void 맨_끝에_통과를_붙여도_경고는_늘지_않고_최대_하나만_줄어든다() {
		for (int i = 0; i < CASES; i++) {
			Case c = randomCase(new Random(SEED + i), false, false);
			Calculated before = (Calculated) run(c);
			Calculated after = (Calculated) WarningRecalculator.recalculate(parse(c.script() + "P"), c.baseline());
			assertThat(after.warningCount()).as("%s", c)
					.isBetween(before.warningCount() - 1, before.warningCount());
		}
	}

	@Test
	void 맨_끝에_면제를_붙여도_결과가_그대로다() {
		for (int i = 0; i < CASES; i++) {
			Case c = randomCase(new Random(SEED + i), true, false);
			assertThat(WarningRecalculator.recalculate(parse(c.script() + "E"), c.baseline())).as("%s", c)
					.isEqualTo(run(c));
		}
	}

}
