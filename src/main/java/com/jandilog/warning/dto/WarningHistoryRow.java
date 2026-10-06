package com.jandilog.warning.dto;

import java.time.LocalDate;

// 경고 이력 조회의 한 행: 경고와 그 주 판정의 인증일·기록글 수. JPQL 생성자 식으로 채운다
public record WarningHistoryRow(Long warningId, LocalDate weekStart, Integer verifiedDays, Integer recordCount) {
}
