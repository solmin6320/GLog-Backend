package com.jandilog.admin.graphql;

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;

import com.jandilog.admin.dto.AdminPostFilter;
import com.jandilog.admin.dto.AdminPostPage;
import com.jandilog.admin.service.AdminPostService;
import com.jandilog.common.security.AuthenticatedMember;

// 관리자 게시물 관리 탭 (AD-01 `/admin/posts`): 글 · 댓글 목록과 삭제 (기능명세서 3·9장)
@Controller
public class AdminPostController {

	private final AdminPostService postService;

	public AdminPostController(AdminPostService postService) {
		this.postService = postService;
	}

	@QueryMapping
	@PreAuthorize("hasRole('ADMIN')")
	public AdminPostPage adminPosts(@Argument AdminPostFilter filter, @Argument String keyword,
			@Argument String after) {
		return postService.list(filter, keyword, after);
	}

	@MutationMapping
	@PreAuthorize("hasRole('ADMIN')")
	public boolean adminDeletePost(@AuthenticationPrincipal AuthenticatedMember admin, @Argument String postId) {
		postService.deletePost(admin.id(), postId);
		return true;
	}

	@MutationMapping
	@PreAuthorize("hasRole('ADMIN')")
	public boolean adminDeleteComment(@AuthenticationPrincipal AuthenticatedMember admin,
			@Argument String commentId) {
		postService.deleteComment(admin.id(), commentId);
		return true;
	}

}
