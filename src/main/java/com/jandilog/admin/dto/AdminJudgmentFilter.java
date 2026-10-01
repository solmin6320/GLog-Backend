package com.jandilog.admin.dto;

import java.util.EnumSet;
import java.util.Set;

import com.jandilog.judgment.domain.JudgmentStatus;

// 판정 결과 목록의 결과 필터 (AD-01 ⑥: 전체 / 미달만 / 보류만)
public enum AdminJudgmentFilter {
	ALL(EnumSet.allOf(JudgmentStatus.class)),
	FAIL_ONLY(EnumSet.of(JudgmentStatus.FAIL)),
	HOLD_ONLY(EnumSet.of(JudgmentStatus.HOLD));

	private final Set<JudgmentStatus> statuses;

	AdminJudgmentFilter(Set<JudgmentStatus> statuses) {
		this.statuses = statuses;
	}

	public Set<JudgmentStatus> statuses() {
		return statuses;
	}
}
