package com.jandilog.warning.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.jandilog.warning.domain.WarningRecalcResult;
import com.jandilog.warning.repository.WarningRepository;
import com.jandilog.warning.service.PenaltyTargetService.Target;

// 벌칙 대상 목록 (기능명세서 6장): 후보를 좁힌 뒤 재계산으로 대상 여부를 정하고, 도달 주가 오래된 순으로 낸다
class PenaltyTargetServiceTest {

	private static final LocalDate W1 = LocalDate.of(2026, 8, 3);
	private static final LocalDate W2 = LocalDate.of(2026, 8, 10);
	private static final LocalDate W3 = LocalDate.of(2026, 8, 17);
	private static final LocalDate W4 = LocalDate.of(2026, 8, 24);

	@Test
	@DisplayName("재계산에서 벌칙 대상인 회원만 도달 주 순으로 내고 보류 · 차감된 회원은 뺀다")
	void keepsOnlyRecalculatedTargetsInReachedOrder() {
		WarningRepository warnings = mock(WarningRepository.class);
		WarningRecalculationService recalculation = mock(WarningRecalculationService.class);
		when(warnings.findMemberIdsWithAliveWarningsAtLeast(3)).thenReturn(List.of(1L, 2L, 3L, 4L, 5L));
		// 1: 늦게 도달, 2: 먼저 도달(경고 4개), 3: 보류 중, 4: 살아 있는 경고는 3개지만 차감돼 대상이 아님, 5: 1과 같은 주에 도달
		when(recalculation.calculate(1L)).thenReturn(calculated(3, true, W2, W3, W4));
		when(recalculation.calculate(2L)).thenReturn(calculated(4, true, W1, W2, W3, W4));
		when(recalculation.calculate(3L)).thenReturn(new WarningRecalcResult.OnHold(List.of(W4)));
		when(recalculation.calculate(4L)).thenReturn(calculated(2, false, W3, W4));
		when(recalculation.calculate(5L)).thenReturn(calculated(3, true, W2, W3, W4));

		List<Target> targets = new PenaltyTargetService(warnings, recalculation).findTargets();

		// 같은 주에 도달하면 회원 id 순
		assertThat(targets).containsExactly(new Target(2L, 4, W3), new Target(1L, 3, W4), new Target(5L, 3, W4));
	}

	private static WarningRecalcResult.Calculated calculated(int count, boolean penalty, LocalDate... weeks) {
		return new WarningRecalcResult.Calculated(count, 0, penalty, List.of(weeks), List.of(), List.of());
	}

}
