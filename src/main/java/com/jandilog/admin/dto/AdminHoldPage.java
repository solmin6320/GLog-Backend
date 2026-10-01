package com.jandilog.admin.dto;

import java.util.List;

// 보류 목록 한 페이지(20개). totalCount가 0이면 화면은 보류 표시를 아예 그리지 않는다.
// identityMismatchCount: 그중 본인 재로그인이 필요한 건 (안내 문구가 다르다)
public record AdminHoldPage(List<AdminHold> items, String nextCursor, int totalCount, int identityMismatchCount) {
}
