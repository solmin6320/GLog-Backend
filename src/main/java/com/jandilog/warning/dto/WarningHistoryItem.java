package com.jandilog.warning.dto;

import java.util.List;

// 경고 이력 표 한 행 (AC-02 ③). 사유는 인증일·기록글 수, 팀 카테고리는 표시 이름이다.
// 확정 판정에서 수치를 알 수 없으면(면제에서 미달로 정정한 주 등) 인증일·기록글이 null이다
public record WarningHistoryItem(
		String weekStart,
		String weekEnd,
		Integer verifiedDays,
		Integer recordCount,
		List<String> categories,
		WarningHistoryStatus status) {
}
