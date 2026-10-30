package com.jandilog.team.board.dto;

// GraphQL TeamWeekSummary 타입 (TM-01 ⑧). 판정 제외·면제인 팀원은 통과·미달·아직에 세지 않고 excludedCount에 센다
public record TeamWeekSummaryResponse(String weekStart, int passCount, int failCount, int notYetCount,
		int excludedCount) {
}
