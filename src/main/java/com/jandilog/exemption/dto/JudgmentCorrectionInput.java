package com.jandilog.exemption.dto;

import com.jandilog.judgment.domain.JudgmentStatus;

// 판정 정정 입력. status는 통과·미달·면제 셋 중 하나(Q-05), reason은 필수(1~500자).
// expectedStatus는 미리보기 때 본 현재 결과이며, 다르면 반영하지 않는다(미리보기와 반영 사이 재검증, 선택)
public record JudgmentCorrectionInput(String judgmentId, JudgmentStatus status, String reason,
		JudgmentStatus expectedStatus) {
}
