package com.jandilog.post.graphql;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bson.types.ObjectId;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;

import com.jandilog.common.security.AuthenticatedMember;
import com.jandilog.post.domain.PostType;
import com.jandilog.post.dto.BoardAuthor;
import com.jandilog.post.dto.CommentResponse;
import com.jandilog.post.dto.CreatePostInput;
import com.jandilog.post.dto.PostPageResponse;
import com.jandilog.post.dto.PostResponse;
import com.jandilog.post.dto.TagCount;
import com.jandilog.post.dto.UpdatePostInput;
import com.jandilog.post.service.BoardAuthorService;
import com.jandilog.post.service.CommentService;
import com.jandilog.post.service.PostService;

// 게시판 글. 작성자·댓글 수·댓글은 목록에서도 쿼리 한 번씩만 나가도록 @BatchMapping으로 채운다 (기능명세서 10장)
@Controller
public class PostController {

	private final PostService postService;
	private final CommentService commentService;
	private final BoardAuthorService authorService;

	public PostController(PostService postService, CommentService commentService, BoardAuthorService authorService) {
		this.postService = postService;
		this.commentService = commentService;
		this.authorService = authorService;
	}

	@QueryMapping
	@PreAuthorize("hasRole('MEMBER')")
	public PostPageResponse posts(@Argument PostType type, @Argument String tag, @Argument String q,
			@Argument String after) {
		return postService.list(type, tag, q, after);
	}

	@QueryMapping
	@PreAuthorize("hasRole('MEMBER')")
	public PostResponse post(@Argument String id) {
		return postService.get(id);
	}

	@QueryMapping
	@PreAuthorize("hasRole('MEMBER')")
	public List<TagCount> popularTags(@Argument PostType type) {
		return postService.popularTags(type);
	}

	@MutationMapping
	@PreAuthorize("hasRole('MEMBER')")
	public PostResponse createPost(@AuthenticationPrincipal AuthenticatedMember member,
			@Argument CreatePostInput input) {
		return postService.create(member.id(), input);
	}

	@MutationMapping
	@PreAuthorize("hasRole('MEMBER')")
	public PostResponse updatePost(@AuthenticationPrincipal AuthenticatedMember member, @Argument String id,
			@Argument UpdatePostInput input) {
		return postService.update(member.id(), id, input);
	}

	@MutationMapping
	@PreAuthorize("hasRole('MEMBER')")
	public boolean deletePost(@AuthenticationPrincipal AuthenticatedMember member, @Argument String id) {
		postService.delete(member.id(), id);
		return true;
	}

	// 건수는 요청했을 때만 센다
	@SchemaMapping(typeName = "PostPage", field = "totalCount")
	public int totalCount(PostPageResponse page) {
		return postService.count(page.condition());
	}

	@BatchMapping(typeName = "Post")
	public Map<PostResponse, BoardAuthor> author(List<PostResponse> posts) {
		Map<Long, BoardAuthor> authors = authorService
				.findByIds(posts.stream().map(PostResponse::authorId).distinct().toList());
		Map<PostResponse, BoardAuthor> result = new LinkedHashMap<>();
		for (PostResponse post : posts) {
			result.put(post, authors.get(post.authorId()));
		}
		return result;
	}

	@BatchMapping(typeName = "Post")
	public Map<PostResponse, Integer> commentCount(List<PostResponse> posts) {
		Map<ObjectId, Integer> counts = commentService.countByPostIds(objectIds(posts));
		Map<PostResponse, Integer> result = new HashMap<>();
		for (PostResponse post : posts) {
			result.put(post, counts.getOrDefault(new ObjectId(post.id()), 0));
		}
		return result;
	}

	@BatchMapping(typeName = "Post")
	public Map<PostResponse, List<CommentResponse>> comments(List<PostResponse> posts) {
		Map<ObjectId, List<CommentResponse>> byPost = commentService.findByPostIds(objectIds(posts));
		Map<PostResponse, List<CommentResponse>> result = new HashMap<>();
		for (PostResponse post : posts) {
			result.put(post, byPost.getOrDefault(new ObjectId(post.id()), List.of()));
		}
		return result;
	}

	private static List<ObjectId> objectIds(List<PostResponse> posts) {
		List<ObjectId> ids = new ArrayList<>();
		for (PostResponse post : posts) {
			ids.add(new ObjectId(post.id()));
		}
		return ids;
	}

}
