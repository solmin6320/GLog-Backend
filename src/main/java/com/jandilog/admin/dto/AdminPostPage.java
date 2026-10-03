package com.jandilog.admin.dto;

import java.util.List;

// 게시물 관리 목록 한 페이지(20개). 최신순이고 nextCursor가 없으면 마지막 (Q-06)
public record AdminPostPage(List<AdminPostItem> items, String nextCursor) {
}
