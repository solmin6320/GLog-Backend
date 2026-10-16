package com.jandilog.judgment.domain;

// weekly_judgment.status ENUM과 1:1 (DB명세서 1-6)
public enum JudgmentStatus {
	PASS,
	FAIL,
	// 면제 기간·개인 면제
	EXEMPT,
	// 팀 없음·첫 참가 주
	EXCLUDED,
	// GitHub 조회 실패로 아직 확정하지 못함. 확정 상태가 아니다
	HOLD;

	public boolean isConfirmed() {
		return this != HOLD;
	}

	// 경고·연속 통과 계산에서 끊지도 늘리지도 않고 건너뛰는 주 (기능명세서 7장)
	public boolean isSkipped() {
		return this == EXEMPT || this == EXCLUDED;
	}

	// 인증일·기록글 수를 보여주는 결과. 제외·면제·보류 주는 "—"로 둔다 (화면설계서 AC-01 ⑤).
	// 정정·소급 면제로 면제가 된 주는 DB에 예전 수치가 남아 있어도 보여주지 않는다
	public boolean showsCounts() {
		return this == PASS || this == FAIL;
	}
}
