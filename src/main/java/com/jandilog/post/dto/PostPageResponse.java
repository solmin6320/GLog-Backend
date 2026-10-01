package com.jandilog.post.dto;

import java.util.List;

import com.jandilog.post.repository.PostSearchCondition;

// 커서 기반 한 페이지(20개). nextCursor가 없으면 마지막 (E-57).
// condition은 GraphQL에 노출하지 않고, totalCount를 요청했을 때만 건수를 세는 데 쓴다
public record PostPageResponse(List<PostResponse> items, String nextCursor, PostSearchCondition condition) {
}
