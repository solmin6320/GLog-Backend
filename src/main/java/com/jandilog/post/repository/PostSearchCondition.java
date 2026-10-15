package com.jandilog.post.repository;

import java.util.List;

import com.jandilog.post.domain.PostType;

// 게시판 목록 조건. tag는 정규화된 태그 하나(없으면 null), terms는 검색어를 공백으로 나눈 토큰(없으면 빈 목록)
public record PostSearchCondition(PostType type, String tag, List<String> terms) {
}
