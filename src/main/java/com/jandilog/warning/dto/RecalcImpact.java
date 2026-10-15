package com.jandilog.warning.dto;

import com.jandilog.warning.domain.WarningRecalcPreview;

// 정정 · 소급 면제 · 경고 복구의 재계산 미리보기 요약 (기능명세서 7장, E-42 · E-49 · E-50 · E-58 · E-59).
// onHold면 보류 중인 주가 있어 경고 수를 비교하지 않는다(Q-03). recordOnly면 벌칙 이행 기준선 이전 주라 기록만 바뀐다
public record RecalcImpact(
		boolean onHold,
		Integer warningCountBefore,
		Integer warningCountAfter,
		boolean entersPenalty,
		boolean leavesPenalty,
		boolean recordOnly,
		int affectedWeeks) {

	public static RecalcImpact from(WarningRecalcPreview preview, boolean recordOnly, int affectedWeeks) {
		if (!preview.comparable()) {
			return new RecalcImpact(true, null, null, false, false, recordOnly, affectedWeeks);
		}
		return new RecalcImpact(false, preview.warningCountBefore(), preview.warningCountAfter(),
				preview.entersPenalty(), preview.leavesPenalty(), recordOnly, affectedWeeks);
	}

}
