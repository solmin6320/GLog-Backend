package com.jandilog.warning.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jandilog.warning.domain.WarningRecalcResult;
import com.jandilog.warning.domain.WarningRecalculator;
import com.jandilog.warning.repository.WarningRepository;

// 지금 벌칙 대상인 회원 (경고 3개 이상, 기능명세서 6장). 대시보드 건수와 벌칙 관리 목록이 같은 값을 쓴다.
// 살아 있는 경고가 3개 이상인 회원만 후보로 좁히고, 대상 여부는 처음부터 다시 계산해 정한다(보류 중인 회원은 판단할 수 없어 뺀다)
@Service
public class PenaltyTargetService {

	// reachedWeek: 경고가 3개에 도달한 주차. warningCount는 3을 넘을 수 있다
	public record Target(long memberId, int warningCount, LocalDate reachedWeek) {
	}

	private final WarningRepository warningRepository;
	private final WarningRecalculationService recalculationService;

	public PenaltyTargetService(WarningRepository warningRepository, WarningRecalculationService recalculationService) {
		this.warningRepository = warningRepository;
		this.recalculationService = recalculationService;
	}

	@Transactional(readOnly = true)
	public List<Target> findTargets() {
		List<Target> targets = new ArrayList<>();
		for (Long memberId : warningRepository
				.findMemberIdsWithAliveWarningsAtLeast(WarningRecalculator.PENALTY_THRESHOLD)) {
			if (recalculationService.calculate(memberId) instanceof WarningRecalcResult.Calculated state
					&& state.penaltyTarget()) {
				targets.add(new Target(memberId, state.warningCount(), state.penaltyReachedWeek()));
			}
		}
		targets.sort((a, b) -> a.reachedWeek().compareTo(b.reachedWeek()));
		return targets;
	}

}
