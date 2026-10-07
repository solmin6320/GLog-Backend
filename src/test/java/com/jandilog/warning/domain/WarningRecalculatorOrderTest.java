package com.jandilog.warning.domain;

import static com.jandilog.testsupport.warning.RecalcAssert.assertActive;
import static com.jandilog.testsupport.warning.RecalcAssert.assertDeducted;
import static com.jandilog.testsupport.warning.RecalcAssert.assertState;
import static com.jandilog.testsupport.warning.WeekScript.endOf;
import static com.jandilog.testsupport.warning.WeekScript.parse;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import com.jandilog.judgment.domain.JudgmentStatus;
import com.jandilog.testsupport.warning.WeekScript;

// 정정·소급 면제·복구가 어떤 순서로 들어와도 최종 결과가 같다 (기능명세서 6장 "처음부터 다시 훑는다").
// 편집을 하나 적용할 때마다 다시 계산해 보고, 마지막 결과를 최종 이력에서 곧바로 계산한 값과 비교한다
class WarningRecalculatorOrderTest {

	// 이력 한 주를 바꾸는 편집. 상태를 바꾸는 편집과 경고 삭제 표시를 바꾸는 편집은 서로 다른 필드를 만진다
	private sealed interface Edit {

		void applyTo(Map<Integer, WeekEntry> history);

	}

	// 판정 정정 (통과·미달·면제)
	private record Correct(int week, JudgmentStatus status) implements Edit {

		@Override
		public void applyTo(Map<Integer, WeekEntry> history) {
			WeekEntry old = history.get(week);
			history.put(week, new WeekEntry(old.weekStart(), status, old.warningDeleted()));
		}

	}

	// 소급 면제
	private record RetroExempt(int week) implements Edit {

		@Override
		public void applyTo(Map<Integer, WeekEntry> history) {
			new Correct(week, JudgmentStatus.EXEMPT).applyTo(history);
		}

	}

	// 관리자 경고 복구
	private record Restore(int week) implements Edit {

		@Override
		public void applyTo(Map<Integer, WeekEntry> history) {
			WeekEntry old = history.get(week);
			history.put(week, new WeekEntry(old.weekStart(), old.status(), false));
		}

	}

	// 추방·팀 삭제로 경고 삭제 표시
	private record Remove(int week) implements Edit {

		@Override
		public void applyTo(Map<Integer, WeekEntry> history) {
			WeekEntry old = history.get(week);
			history.put(week, new WeekEntry(old.weekStart(), old.status(), true));
		}

	}

	private static Map<Integer, WeekEntry> history(String script) {
		Map<Integer, WeekEntry> history = new TreeMap<>();
		for (WeekEntry entry : parse(script)) {
			history.put(WeekScript.indexOf(entry.weekStart()), entry);
		}
		return history;
	}

	private static WarningRecalcResult recalc(Map<Integer, WeekEntry> history, LocalDateTime baseline) {
		return WarningRecalculator.recalculate(history.values(), baseline);
	}

	// 편집을 순서대로 적용하며 매번 다시 계산하고 마지막 결과를 돌려준다
	private static WarningRecalcResult replay(String base, List<Edit> order, LocalDateTime baseline) {
		Map<Integer, WeekEntry> history = history(base);
		WarningRecalcResult last = recalc(history, baseline);
		for (Edit edit : order) {
			edit.applyTo(history);
			last = recalc(history, baseline);
		}
		return last;
	}

	private static void permutations(List<Edit> rest, List<Edit> chosen, List<List<Edit>> out) {
		if (rest.isEmpty()) {
			out.add(new ArrayList<>(chosen));
			return;
		}
		for (int i = 0; i < rest.size(); i++) {
			List<Edit> remaining = new ArrayList<>(rest);
			Edit next = remaining.remove(i);
			chosen.add(next);
			permutations(remaining, chosen, out);
			chosen.remove(chosen.size() - 1);
		}
	}

	private static List<List<Edit>> allOrders(List<Edit> edits) {
		List<List<Edit>> orders = new ArrayList<>();
		permutations(edits, new ArrayList<>(), orders);
		return orders;
	}

	@Test
	void 정정_소급_면제_복구를_어떤_순서로_해도_결과가_같다() {
		// 최종: F E F P P F P P -> 경고 1개(5주), 2주 연속 통과로 0주·2주 경고를 차감
		String base = "FFDFFFPP";
		List<Edit> edits = List.of(new RetroExempt(1), new Restore(2), new Correct(3, JudgmentStatus.PASS),
				new Correct(4, JudgmentStatus.PASS));

		List<List<Edit>> orders = allOrders(edits);
		assertThat(orders).hasSize(24);
		for (List<Edit> order : orders) {
			var result = assertState(replay(base, order, null), 1, 0);
			assertActive(result, 5);
			assertDeducted(result, 0, 2);
		}
	}

	@Test
	void 이행_기준선이_있어도_편집_순서와_무관하다() {
		// 0~2주는 이행 전. 이행 전 주를 건드리는 편집이 섞여도 이행 뒤 결과는 그대로다
		String base = "FFFFPP";
		List<Edit> edits = List.of(new Correct(0, JudgmentStatus.PASS), new RetroExempt(1),
				new Remove(2), new Correct(3, JudgmentStatus.PASS));
		LocalDateTime baseline = endOf(2);

		for (List<Edit> order : allOrders(edits)) {
			// 이행 뒤는 3~5주가 모두 통과: 2주째에 연속 0(경고 없음), 3주째 연속 1
			var result = assertState(replay(base, order, baseline), 0, 1);
			assertThat(result.deductedWarningWeeks()).isEmpty();
		}
	}

	@Test
	void 같은_주에_상태_편집과_경고_표시_편집이_겹쳐도_순서와_무관하다() {
		// 삭제 표시된 미달 주를 복구하고 면제로 정정: 최종은 면제라 복구 여부와 무관
		String base = "FDPP";
		List<Edit> edits = List.of(new Restore(1), new RetroExempt(1));
		var results = allOrders(edits).stream().map(order -> replay(base, order, null)).toList();
		assertThat(results).hasSize(2);
		assertThat(results.get(0)).isEqualTo(results.get(1));

		// 삭제 표시된 미달 주를 복구하고 통과로 정정
		var results2 = allOrders(List.<Edit>of(new Restore(1), new Correct(1, JudgmentStatus.PASS))).stream()
				.map(order -> replay(base, order, null)).toList();
		assertThat(results2.get(0)).isEqualTo(results2.get(1));
	}

	@Test
	void 무작위_이력과_편집_묶음도_모든_순서에서_결과가_같다() {
		Random random = new Random(20261001L);
		for (int round = 0; round < 300; round++) {
			int length = 3 + random.nextInt(8);
			StringBuilder base = new StringBuilder();
			for (int i = 0; i < length; i++) {
				base.append("PPFFFDEX".charAt(random.nextInt(8)));
			}
			LocalDateTime baseline = random.nextInt(3) == 0 ? endOf(random.nextInt(length)) : null;

			// 주마다 편집은 많아야 상태 하나, 경고 표시 하나
			List<Edit> edits = new ArrayList<>();
			for (int week = 0; week < length && edits.size() < 5; week++) {
				if (random.nextInt(4) == 0) {
					JudgmentStatus[] choices = {JudgmentStatus.PASS, JudgmentStatus.FAIL, JudgmentStatus.EXEMPT};
					edits.add(random.nextBoolean() ? new RetroExempt(week)
							: new Correct(week, choices[random.nextInt(choices.length)]));
				}
				if (edits.size() < 5 && random.nextInt(4) == 0) {
					edits.add(random.nextBoolean() ? new Restore(week) : new Remove(week));
				}
			}

			Map<Integer, WeekEntry> finalState = history(base.toString());
			edits.forEach(edit -> edit.applyTo(finalState));
			WarningRecalcResult expected = recalc(finalState, baseline);

			for (List<Edit> order : allOrders(edits)) {
				assertThat(replay(base.toString(), order, baseline))
						.as("이력=%s 기준선=%s 순서=%s", base, baseline, order).isEqualTo(expected);
			}
		}
	}

}
