package com.jandilog.judgment.dto;

// AC-01 날짜별 인증 한 행. null은 "알 수 없음"(아직 안 불러왔거나 오늘 갱신 전이거나 저장하지 않은 값)이다.
// 판정이 끝난 주는 판정 시점에 저장한 값(has_grass, has_record)만 있어 grassCount·recordCount가 null이다
public record ActivityDayResponse(
		String date,
		Boolean hasGrass,
		Integer grassCount,
		boolean hasRecord,
		Integer recordCount) {
}
