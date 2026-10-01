package com.jandilog.judgment.domain;

// weekly_judgment.hold_reason ENUM과 1:1 (DB명세서 1-6)
public enum HoldReason {
	// GitHub 오류·레이트리밋. 재시도로 풀린다
	API_ERROR,
	// GitHub 아이디 변경 등으로 사용자를 찾지 못함. 본인 재로그인이 필요해 재시도로는 안 풀린다
	IDENTITY_MISMATCH
}
