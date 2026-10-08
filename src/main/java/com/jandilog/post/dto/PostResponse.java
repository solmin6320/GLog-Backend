package com.jandilog.post.dto;

import java.util.List;

import com.jandilog.post.domain.Post;
import com.jandilog.post.domain.PostSections;
import com.jandilog.post.domain.PostType;

// GraphQL Post 타입. 작성자 정보·댓글 수는 @BatchMapping, 댓글 목록은 글마다 한 페이지씩 따로 채운다. 시각은 KST ISO-8601
public record PostResponse(
		String id,
		long authorId,
		Long teamId,
		PostType type,
		String title,
		PostSections sections,
		List<String> tags,
		List<String> commitUrls,
		boolean isRecord,
		String writtenDate,
		String createdAt,
		String updatedAt) {

	public static PostResponse from(Post post) {
		return new PostResponse(post.getId().toHexString(), post.getAuthorId(), post.getTeamId(), post.getType(),
				post.getTitle(), post.getSections(), post.getTags(), post.getCommitUrls(), post.isRecord(),
				post.writtenLocalDate().toString(), KstFormat.of(post.getCreatedAt()), KstFormat.of(post.getUpdatedAt()));
	}

}
