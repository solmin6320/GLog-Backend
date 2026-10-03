package com.jandilog.post.graphql;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;

import com.jandilog.common.security.AuthenticatedMember;
import com.jandilog.post.dto.BoardAuthor;
import com.jandilog.post.dto.CommentResponse;
import com.jandilog.post.service.BoardAuthorService;
import com.jandilog.post.service.CommentService;

// 댓글 작성·수정·삭제 (기능명세서 3장)
@Controller
public class CommentController {

	private final CommentService commentService;
	private final BoardAuthorService authorService;

	public CommentController(CommentService commentService, BoardAuthorService authorService) {
		this.commentService = commentService;
		this.authorService = authorService;
	}

	@MutationMapping
	@PreAuthorize("hasRole('MEMBER')")
	public CommentResponse createComment(@AuthenticationPrincipal AuthenticatedMember member,
			@Argument String postId, @Argument String content) {
		return commentService.create(member.id(), postId, content);
	}

	@MutationMapping
	@PreAuthorize("hasRole('MEMBER')")
	public CommentResponse updateComment(@AuthenticationPrincipal AuthenticatedMember member, @Argument String id,
			@Argument String content) {
		return commentService.update(member.id(), id, content);
	}

	@MutationMapping
	@PreAuthorize("hasRole('MEMBER')")
	public boolean deleteComment(@AuthenticationPrincipal AuthenticatedMember member, @Argument String id) {
		commentService.delete(member.id(), id);
		return true;
	}

	@BatchMapping(typeName = "Comment")
	public Map<CommentResponse, BoardAuthor> author(List<CommentResponse> comments) {
		Map<Long, BoardAuthor> authors = authorService
				.findByIds(comments.stream().map(CommentResponse::authorId).distinct().toList());
		Map<CommentResponse, BoardAuthor> result = new LinkedHashMap<>();
		for (CommentResponse comment : comments) {
			result.put(comment, authors.get(comment.authorId()));
		}
		return result;
	}

}
