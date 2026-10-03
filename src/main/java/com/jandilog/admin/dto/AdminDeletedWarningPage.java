package com.jandilog.admin.dto;

import java.util.List;

// 삭제된 경고 한 페이지(20개). 삭제가 최근인 순이고 nextCursor가 없으면 마지막 (Q-06)
public record AdminDeletedWarningPage(List<AdminDeletedWarning> items, String nextCursor) {
}
