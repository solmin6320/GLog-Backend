package com.jandilog.exemption;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.jandilog.testsupport.exemption.ExemptionIntegrationTest;
import com.jandilog.warning.domain.WarningRecalcResult;

// 정정·소급 면제·개인 면제 승인이 어떤 순서로 들어와도 같은 결과가 나온다 (기능명세서 6장, T-31 경로 순서 무관성).
// 경고 수와 연속 통과는 저장값을 더하고 빼지 않고 판정 이력을 처음부터 훑어 구하므로, 같은 최종 이력을 처음부터 가진 회원과도 같다.
// 면제 기간은 전체 회원에 걸리므로 2049년 주차만 쓰고 조합마다 지운다
class CorrectionOrderIntegrationTest extends ExemptionIntegrationTest {

	private static final LocalDate BASE = monday(2049, 3, 7);
	// 처음 이력: 0 F, 1 P, 2 F, 3 F, 4 P, 5 P, 6 F, 7 P, 8 P, 9 P
	private static final String START = "F P F F P P F P P P";
	// 네 가지 변경을 모두 적용한 최종 이력: 2주 미달->통과, 5주 통과->미달, 3주 면제 기간, 6주 개인 면제
	private static final String FINAL = "F P P E P F E P P P";
	private static final List<String> FINAL_STATUSES = List.of("FAIL", "PASS", "PASS", "EXEMPT", "PASS", "FAIL",
			"EXEMPT", "PASS", "PASS", "PASS");

	@BeforeEach
	void fixClock() {
		fixClockAt(BASE.plusWeeks(30).atTime(10, 0));
	}

	@Test
	@DisplayName("정정 둘·소급 면제·개인 면제 승인을 24가지 순서로 적용해도 결과가 같고 처음부터 그 이력을 가진 회원과도 같다")
	void allOrdersOfFourChangesGiveTheSameResult() {
		WarningRecalcResult.Calculated expected = runAllOrders(false, false);

		// 앵커: 최종 이력을 직접 훑은 값. F0 P1 P2(차감) E3 P4 F5 E6 P7 P8(차감) P9
		assertThat(expected.warningCount()).isZero();
		assertThat(expected.streak()).isEqualTo(1);
		assertThat(expected.deductedWarningWeeks()).containsExactly(BASE, BASE.plusWeeks(5));
		assertThat(expected.penaltyTarget()).isFalse();
	}

	@Test
	@DisplayName("벌칙 이행 기준선이 있어도 변경 순서와 상관없이 결과가 같고 이행 이전 주의 변경은 경고 수에 반영되지 않는다 (E-58)")
	void allOrdersWithFulfillmentBaselineGiveTheSameResult() {
		WarningRecalcResult.Calculated expected = runAllOrders(true, false);

		// 0~3주는 이행 이전이라 세지 않고, 이후 F5는 8주 차감으로 사라진다
		assertThat(expected.warningCount()).isZero();
		assertThat(expected.streak()).isEqualTo(1);
		assertThat(expected.beforeBaselineWarningWeeks()).containsExactly(BASE);
		assertThat(expected.deductedWarningWeeks()).containsExactly(BASE.plusWeeks(5));
	}

	@Test
	@DisplayName("삭제 표시된 경고가 있어도 변경 순서와 상관없이 결과가 같고 삭제된 경고는 되살아나지 않는다 (E-60)")
	void allOrdersWithDeletedWarningGiveTheSameResult() {
		WarningRecalcResult.Calculated expected = runAllOrders(false, true);

		// 삭제된 0주 경고는 세지 않으니 2주 연속 통과에서 차감할 경고가 없고, 5주 경고만 8주에 차감된다
		assertThat(expected.warningCount()).isZero();
		assertThat(expected.deductedWarningWeeks()).containsExactly(BASE.plusWeeks(5));
		assertThat(expected.activeWarningWeeks()).isEmpty();
	}

	// 모든 순서로 적용한 결과가 하나뿐이고 최종 이력을 직접 가진 회원과 같은지 확인하고 그 결과를 돌려준다
	private WarningRecalcResult.Calculated runAllOrders(boolean withBaseline, boolean deletedFirstWarning) {
		long leader = activeMember();
		long team = newTeam("순서팀", leader);
		Set<WarningRecalcResult.Calculated> results = new LinkedHashSet<>();
		List<List<Integer>> orders = permutations(List.of(0, 1, 2, 3));
		assertThat(orders).hasSize(24);

		for (List<Integer> order : orders) {
			long member = activeMember();
			join(team, member);
			History history = history(member, BASE, START, team);
			prepare(member, history, withBaseline, deletedFirstWarning);
			long request = requestPersonal(leader, team, member, history.week(6), "개인 면제").path("id").asLong();

			for (int op : order) {
				apply(op, history, request);
			}
			// 면제 기간은 조합마다 지워 다음 조합의 회원에 걸리지 않게 한다
			jdbc.update("delete from exemption_period where week_start = ?", history.week(3));

			assertThat(statuses(member)).as("순서 " + order).isEqualTo(FINAL_STATUSES);
			results.add(state(member));
		}
		assertThat(results).as("순서에 따라 결과가 달라지면 안 돼요").hasSize(1);

		long direct = activeMember();
		join(team, direct);
		History fresh = history(direct, BASE, FINAL, team);
		prepare(direct, fresh, withBaseline, deletedFirstWarning);
		WarningRecalcResult.Calculated expected = state(direct);
		assertThat(results.iterator().next()).isEqualTo(expected);
		return expected;
	}

	private void prepare(long member, History history, boolean withBaseline, boolean deletedFirstWarning) {
		if (withBaseline) {
			// 4주 월요일 새벽에 이행: 0~3주는 이행 이전
			data.fulfillment(member, adminId, history.week(4).atTime(1, 0), history.week(2));
		}
		if (deletedFirstWarning) {
			LocalDateTime deletedAt = LocalDateTime.of(2049, 1, 1, 0, 0);
			jdbc.update("update warning set deleted_at = ?, delete_reason = 'KICKED' where id = ?", deletedAt,
					history.warning(0));
			jdbc.update("update warning_team set removed_at = ? where warning_id = ?", deletedAt, history.warning(0));
		}
	}

	private void apply(int op, History history, long personalRequest) {
		switch (op) {
			case 0 -> correct(history.judgment(2), "PASS", "2주 정정");
			case 1 -> correct(history.judgment(5), "FAIL", "5주 정정");
			case 2 -> createPeriod(history.week(3), "3주 면제 기간");
			case 3 -> approve(personalRequest);
			default -> throw new IllegalArgumentException("알 수 없는 변경: " + op);
		}
	}

	private List<String> statuses(long memberId) {
		return jdbc.queryForList("select status from weekly_judgment where member_id = ? order by week_start",
				String.class, memberId);
	}

	private static List<List<Integer>> permutations(List<Integer> items) {
		List<List<Integer>> out = new ArrayList<>();
		permute(new ArrayList<>(items), 0, out);
		return out;
	}

	private static void permute(List<Integer> items, int index, List<List<Integer>> out) {
		if (index == items.size()) {
			out.add(List.copyOf(items));
			return;
		}
		for (int i = index; i < items.size(); i++) {
			java.util.Collections.swap(items, index, i);
			permute(items, index + 1, out);
			java.util.Collections.swap(items, index, i);
		}
	}

}
