package com.jandilog.team.board.dto;

// GraphQL TeamWeekSummary 타입 (TM-01 ⑧). 판정 제외·면제인 팀원은 어느 쪽에도 세지 않는다
public record TeamWeekSummaryResponse(String weekStart, int passCount, int failCount, int notYetCount) {
}
