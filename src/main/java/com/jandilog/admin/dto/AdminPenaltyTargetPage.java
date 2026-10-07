package com.jandilog.admin.dto;

import java.util.List;

// 벌칙 대상 한 페이지(20명). 도달한 주가 오래된 순이고 nextCursor가 없으면 마지막 (Q-06)
public record AdminPenaltyTargetPage(List<AdminPenaltyTarget> items, String nextCursor, int totalCount) {
}
