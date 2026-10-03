package com.jandilog.post.dto;

import java.util.List;

import com.jandilog.post.domain.PostType;

// 글 작성 입력 (BD-04)
public record CreatePostInput(
		PostType type,
		String title,
		PostSectionsInput sections,
		List<String> tags,
		List<String> commitUrls,
		String teamId) {
}
