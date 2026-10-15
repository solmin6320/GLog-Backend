package com.jandilog.warning.service;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jandilog.judgment.domain.JudgmentStatus;
import com.jandilog.judgment.repository.WeeklyJudgmentRepository;
import com.jandilog.warning.domain.PenaltyFulfillment;
import com.jandilog.warning.domain.WarningRecalcPreview;
import com.jandilog.warning.domain.WarningRecalculator;
import com.jandilog.warning.dto.RecalcImpact;
import com.jandilog.warning.repository.PenaltyFulfillmentRepository;

// 정정 · 소급 면제 · 경고 복구의 재계산 미리보기를 화면용 요약으로 만든다. 계산은 WarningRecalculationService가 하고
// 여기서는 DB를 바꾸지 않는다 (기능명세서 7장 "미리보기는 계산만 하고 아무것도 바꾸지 않는다")
@Service
public class RecalcImpactService {

	private final WarningRecalculationService recalculationService;
	private final WeeklyJudgmentRepository judgmentRepository;
	private final PenaltyFulfillmentRepository fulfillmentRepository;

	public RecalcImpactService(WarningRecalculationService recalculationService,
			WeeklyJudgmentRepository judgmentRepository, PenaltyFulfillmentRepository fulfillmentRepository) {
		this.recalculationService = recalculationService;
		this.judgmentRepository = judgmentRepository;
		this.fulfillmentRepository = fulfillmentRepository;
	}

	// 그 주 판정을 target으로 바꿨다고 가정한 영향 (정정 · 소급 면제)
	@Transactional(readOnly = true)
	public RecalcImpact ofStatusChange(long memberId, LocalDate weekStart, JudgmentStatus target) {
		WarningRecalcPreview preview = recalculationService.preview(memberId, Map.of(weekStart, target));
		return summarize(memberId, weekStart, preview);
	}

	// 그 주 경고의 삭제 표시를 풀었다고 가정한 영향 (경고 복구, E-42)
	@Transactional(readOnly = true)
	public RecalcImpact ofWarningRestore(long memberId, LocalDate warningWeek) {
		WarningRecalcPreview preview = recalculationService.preview(memberId, Map.of(), Set.of(warningWeek));
		return summarize(memberId, warningWeek, preview);
	}

	// 이행 체크 이전 주차면 기록만 바뀌고 경고 수는 그대로다 (E-58, E-59). 영향 주차는 그 주 이후 판정 수
	private RecalcImpact summarize(long memberId, LocalDate weekStart, WarningRecalcPreview preview) {
		boolean recordOnly = fulfillmentRepository.findFirstByMemberIdOrderByFulfilledAtDesc(memberId)
				.map(PenaltyFulfillment::getFulfilledAt)
				.map(fulfilledAt -> WarningRecalculator.isBeforeBaseline(weekStart, fulfilledAt))
				.orElse(false);
		int affectedWeeks = recordOnly ? 0
				: (int) judgmentRepository.findByMemberIdOrderByWeekStartAsc(memberId).stream()
						.filter(judgment -> judgment.getWeekStart().isAfter(weekStart)).count();
		return RecalcImpact.from(preview, recordOnly, affectedWeeks);
	}

}
