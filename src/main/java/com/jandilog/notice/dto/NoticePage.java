package com.jandilog.notice.dto;

import java.util.List;

// 커서 기반 한 페이지(20개). nextCursor가 없으면 마지막 (E-57)
public record NoticePage(List<NoticeResponse> items, String nextCursor) {
}
