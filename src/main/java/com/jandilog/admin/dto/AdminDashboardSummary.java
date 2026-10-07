package com.jandilog.admin.dto;

// 대시보드 처리 대기 요약 (AD-01 ⑤). 메뉴 배지도 같은 값을 받아 쓴다.
// judgedWeekStart: "이번 주 미달"이 가리키는 판정 주(직전 주 월요일, 이번 월요일 판정의 대상)
public record AdminDashboardSummary(
		String judgedWeekStart,
		int pendingMembers,
		int failedMembers,
		int penaltyTargets,
		int pendingExemptions) {
}
