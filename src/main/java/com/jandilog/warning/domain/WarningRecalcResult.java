package com.jandilog.warning.domain;

import java.time.LocalDate;
import java.util.List;

// 경고 재계산 결과. 보류(HOLD) 주가 남아 있으면 숫자를 내지 않고 OnHold로 돌려준다 (Q-03 결정)
public sealed interface WarningRecalcResult {

	// 계산 완료. 모든 목록은 week_start 오름차순이고 값은 처음부터 다시 훑어 얻은 것이다(저장 카운트를 더하고 빼지 않는다)
	// warningCount: 살아 있고 차감되지 않은 경고 수. activeWarningWeeks.size()와 같다
	// streak: 지금 연속 통과 수. 차감하면 0이 되고, 벌칙 대상이면 쌓지 않아 0이다 (Q-10 ⓑ)
	// deductedWarningWeeks: 2주 연속 통과로 차감된 경고(가장 오래된 것부터)의 주차
	// beforeBaselineWarningWeeks: 벌칙 이행 기준선 이전이라 세지 않는 경고의 주차 (E-58)
	record Calculated(int warningCount, int streak, boolean penaltyTarget, List<LocalDate> activeWarningWeeks,
			List<LocalDate> deductedWarningWeeks, List<LocalDate> beforeBaselineWarningWeeks)
			implements WarningRecalcResult {

		public Calculated {
			activeWarningWeeks = List.copyOf(activeWarningWeeks);
			deductedWarningWeeks = List.copyOf(deductedWarningWeeks);
			beforeBaselineWarningWeeks = List.copyOf(beforeBaselineWarningWeeks);
		}

		// 경고가 3개에 도달한 주(세 번째 살아 있는 경고의 주차). 벌칙 대상일 때만 있다.
		// 3개에 닿은 뒤에는 차감이 멈춰 앞의 세 개가 바뀌지 않으므로 목록의 세 번째가 곧 도달 주차다 (penalty_fulfillment.reached_week)
		public LocalDate penaltyReachedWeek() {
			return penaltyTarget ? activeWarningWeeks.get(WarningRecalculator.PENALTY_THRESHOLD - 1) : null;
		}

	}

	// 판정 보류가 풀릴 때까지 재계산하지 않는다. holdWeeks: 보류 중인 주차
	record OnHold(List<LocalDate> holdWeeks) implements WarningRecalcResult {

		public OnHold {
			holdWeeks = List.copyOf(holdWeeks);
		}

	}

}
