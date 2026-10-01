package com.jandilog.judgment.dto;

import java.util.List;

// 커서 기반 20개, nextCursor가 null이면 마지막 (Q-06)
public record JudgmentHistoryPage(List<JudgmentHistoryItem> items, String nextCursor) {
}
