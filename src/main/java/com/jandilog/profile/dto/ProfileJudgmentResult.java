package com.jandilog.profile.dto;

import com.jandilog.judgment.domain.JudgmentStatus;

// 프로필 판정 이력의 결과 (PR-01 ⑩): 통과 / 미달(경고) / 면제 / 제외 / 진행 중
public enum ProfileJudgmentResult {
	PASS,
	FAIL,
	EXEMPT,
	EXCLUDED,
	IN_PROGRESS;

	// 보류(HOLD)는 확정 전이라 진행 중으로 본다. 사유(API 오류·아이디 불일치)는 다른 회원에게 내보내지 않는다
	public static ProfileJudgmentResult of(JudgmentStatus status) {
		return switch (status) {
			case PASS -> PASS;
			case FAIL -> FAIL;
			case EXEMPT -> EXEMPT;
			case EXCLUDED -> EXCLUDED;
			case HOLD -> IN_PROGRESS;
		};
	}

}
