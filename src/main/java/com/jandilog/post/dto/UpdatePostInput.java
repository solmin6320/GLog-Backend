package com.jandilog.post.dto;

import java.util.List;

// 글 수정 입력 (BD-04). 글 종류는 바꿀 수 없다. 모든 값을 통째로 바꾸므로 생략한 태그·커밋 링크·팀 연결은 비워진다
public record UpdatePostInput(
		String title,
		PostSectionsInput sections,
		List<String> tags,
		List<String> commitUrls,
		String teamId) {
}
