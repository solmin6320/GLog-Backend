package com.jandilog.testsupport.warning;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import com.jandilog.warning.domain.WarningRecalcResult;
import com.jandilog.warning.domain.WarningRecalcResult.Calculated;
import com.jandilog.warning.domain.WarningRecalcResult.OnHold;

// 재계산 결과 검증 도우미
public final class RecalcAssert {

	private RecalcAssert() {
	}

	public static Calculated calculated(WarningRecalcResult result) {
		assertThat(result).as("보류가 아니라 계산된 결과여야 해요").isInstanceOf(Calculated.class);
		return (Calculated) result;
	}

	public static OnHold onHold(WarningRecalcResult result) {
		assertThat(result).as("계산하지 않고 보류여야 해요").isInstanceOf(OnHold.class);
		return (OnHold) result;
	}

	// 경고 수·연속·벌칙 대상 여부를 한 번에 본다. 벌칙 대상은 경고 3개 이상과 항상 같아야 한다
	public static Calculated assertState(WarningRecalcResult result, int warningCount, int streak) {
		Calculated calculated = calculated(result);
		assertThat(calculated.warningCount()).as("경고 수").isEqualTo(warningCount);
		assertThat(calculated.streak()).as("연속 통과").isEqualTo(streak);
		assertThat(calculated.penaltyTarget()).as("벌칙 대상").isEqualTo(warningCount >= 3);
		assertThat(calculated.activeWarningWeeks()).as("살아 있는 경고 목록 크기").hasSize(warningCount);
		return calculated;
	}

	public static void assertActive(Calculated calculated, int... weekIndexes) {
		assertThat(calculated.activeWarningWeeks()).as("남은 경고 주차").isEqualTo(WeekScript.weeks(weekIndexes));
	}

	public static void assertDeducted(Calculated calculated, int... weekIndexes) {
		assertThat(calculated.deductedWarningWeeks()).as("차감된 경고 주차").isEqualTo(WeekScript.weeks(weekIndexes));
	}

	public static void assertBeforeBaseline(Calculated calculated, int... weekIndexes) {
		assertThat(calculated.beforeBaselineWarningWeeks()).as("이행 기준선 이전 경고 주차")
				.isEqualTo(WeekScript.weeks(weekIndexes));
	}

	// 숫자 결과(경고 수·연속·벌칙 대상·남은/차감된 경고)가 같은가. 기준선 이전 목록은 비교하지 않는다
	public static void assertSameNumbers(WarningRecalcResult actual, WarningRecalcResult expected) {
		Calculated a = calculated(actual);
		Calculated e = calculated(expected);
		assertThat(a.warningCount()).as("경고 수").isEqualTo(e.warningCount());
		assertThat(a.streak()).as("연속 통과").isEqualTo(e.streak());
		assertThat(a.penaltyTarget()).as("벌칙 대상").isEqualTo(e.penaltyTarget());
		assertThat(a.activeWarningWeeks()).as("남은 경고 주차").isEqualTo(e.activeWarningWeeks());
		assertThat(a.deductedWarningWeeks()).as("차감된 경고 주차").isEqualTo(e.deductedWarningWeeks());
	}

	public static List<LocalDate> holdWeeks(WarningRecalcResult result) {
		return onHold(result).holdWeeks();
	}

}
