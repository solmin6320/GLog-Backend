package com.jandilog.judgment.dto;

import java.util.List;

// 내 주간 활동 (CM-01 이번 주 진행, AC-01). days는 월~일 7행이다.
// verifiedDays·passExpected: 잔디를 아직 못 불러왔으면 알 수 없어 null. 판정이 끝난 주는 판정 시점 값이다
// grassFetchedAt: 이번 주를 보여준 잔디 캐시의 갱신 기준 시각(KST ISO-8601). 저장된 판정을 보여줄 때는 null
public record WeeklyActivityResponse(
		String weekStart,
		String weekEnd,
		boolean currentWeek,
		boolean inTeam,
		List<ActivityDayResponse> days,
		Integer verifiedDays,
		int recordCount,
		int requiredVerifiedDays,
		int requiredRecordCount,
		Boolean passExpected,
		boolean grassAvailable,
		String grassFetchedAt,
		ActivityBanner banner,
		WeekJudgmentResponse judgment) {
}
