package com.jandilog.post.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.post.domain.Comment;
import com.jandilog.post.domain.Post;
import com.jandilog.post.dto.CommentResponse;
import com.jandilog.post.repository.CommentRepository;

// 댓글 작성·수정·삭제와 글 상세의 댓글 목록 (기능명세서 3장). 1단계 댓글이고, 삭제는 작성자 본인만, 수정은 팀장도 가능
@Service
public class CommentService {

	private final CommentRepository commentRepository;
	private final PostService postService;
	private final Clock clock;

	public CommentService(CommentRepository commentRepository, PostService postService, Clock clock) {
		this.commentRepository = commentRepository;
		this.postService = postService;
		this.clock = clock;
	}

	// 삭제된 글에는 달 수 없다 (E-31)
	public CommentResponse create(long memberId, String postId, String content) {
		Post post = postService.loadAlive(postId);
		String text = PostInputRules.commentContent(content);
		Instant now = now();
		Comment comment = Comment.create(new ObjectId(Date.from(now)), post.getId(), memberId, text, now);
		commentRepository.insert(comment);
		return CommentResponse.from(comment);
	}

	// 작성자 본인 또는 그 글이 연결된 팀의 현재 팀장이 고칠 수 있다 (기능명세서 2장, BD-03 ⑪)
	public CommentResponse update(long memberId, String commentId, String content) {
		Comment comment = loadAlive(commentId);
		if (comment.getAuthorId() != memberId && !postService.isLinkedTeamLeader(comment.getPostId(), memberId)) {
			throw new ApiException(ErrorCode.FORBIDDEN);
		}
		postService.loadAlive(comment.getPostId().toHexString());
		String text = PostInputRules.commentContent(content);

		if (!commentRepository.updateContentIfAlive(comment.getId(), text, now())) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return CommentResponse.from(loadAlive(commentId));
	}

	// 소프트 삭제(deletedAt)
	public void delete(long memberId, String commentId) {
		Comment comment = loadAlive(commentId);
		requireAuthor(comment, memberId);
		postService.loadAlive(comment.getPostId().toHexString());

		if (!commentRepository.softDelete(comment.getId(), now())) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
	}

	// 글 id별 댓글 목록(오래된 순)
	public Map<ObjectId, List<CommentResponse>> findByPostIds(Collection<ObjectId> postIds) {
		Map<ObjectId, List<CommentResponse>> byPost = new HashMap<>();
		for (Comment comment : commentRepository.findAliveByPostIds(postIds)) {
			byPost.computeIfAbsent(comment.getPostId(), key -> new ArrayList<>())
					.add(CommentResponse.from(comment));
		}
		return byPost;
	}

	public Map<ObjectId, Integer> countByPostIds(Collection<ObjectId> postIds) {
		return commentRepository.countAliveByPostIds(postIds);
	}

	// Mongo는 밀리초까지만 저장하므로 응답에 싣는 시각도 밀리초로 맞춘다
	private Instant now() {
		return clock.instant().truncatedTo(ChronoUnit.MILLIS);
	}

	// 없거나 삭제된 댓글은 모두 없는 내용(E-53)으로 본다
	private Comment loadAlive(String commentId) {
		Comment comment = commentRepository.findById(PostService.parseId(commentId))
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (comment.getDeletedAt() != null) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return comment;
	}

	private static void requireAuthor(Comment comment, long memberId) {
		if (comment.getAuthorId() != memberId) {
			throw new ApiException(ErrorCode.FORBIDDEN);
		}
	}

}
