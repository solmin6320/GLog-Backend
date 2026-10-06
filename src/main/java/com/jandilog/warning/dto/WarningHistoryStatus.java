package com.jandilog.warning.dto;

// 경고 이력의 상태 (AC-02 ③, Q-13): 유효 / 차감됨 / 이행 완료
public enum WarningHistoryStatus {
	// 벌칙 이행 이후 살아 있고 차감되지 않아 현재 경고 수에 들어간다
	ACTIVE,
	// 2주 연속 통과로 차감됐다
	DEDUCTED,
	// 벌칙 이행 체크 이전에 받아 현재 경고 수에는 넣지 않는다. 기록만 남는다
	FULFILLED
}
