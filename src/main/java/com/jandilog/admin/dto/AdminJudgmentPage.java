package com.jandilog.admin.dto;

import java.util.List;

// 판정 결과 한 주의 집계와 회원별 목록 (AD-01 ⑦⑧). 집계는 검색·필터와 무관한 그 주 전체 값이다.
// holdCount는 사람이 처리해야 하는 보류만 센다. nextCursor가 없으면 마지막 (Q-06)
public record AdminJudgmentPage(
		String weekStart,
		String weekEnd,
		String executedAt,
		int passCount,
		int failCount,
		int exemptCount,
		int firstWeekCount,
		int holdCount,
		List<AdminJudgmentRow> items,
		String nextCursor) {
}
