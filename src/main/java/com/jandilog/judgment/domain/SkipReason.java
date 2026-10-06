package com.jandilog.judgment.domain;

// weekly_judgment.skip_reason ENUM과 1:1. status와의 대응은 고정이다 (DB명세서 1-6)
public enum SkipReason {
	NO_TEAM(JudgmentStatus.EXCLUDED),
	FIRST_WEEK(JudgmentStatus.EXCLUDED),
	EXEMPTION_PERIOD(JudgmentStatus.EXEMPT),
	PERSONAL_EXEMPTION(JudgmentStatus.EXEMPT);

	private final JudgmentStatus status;

	SkipReason(JudgmentStatus status) {
		this.status = status;
	}

	// 이 사유가 붙는 판정 상태
	public JudgmentStatus status() {
		return status;
	}
}
