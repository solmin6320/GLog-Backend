package com.jandilog.admin.service;

import java.time.Clock;
import java.time.LocalDateTime;

import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.jandilog.admin.domain.AdminActionLog;
import com.jandilog.admin.domain.AdminActionType;
import com.jandilog.admin.repository.AdminActionLogRepository;
import com.jandilog.admin.repository.AdminPostQueryRepository;
import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.post.domain.Comment;
import com.jandilog.post.domain.Post;
import com.jandilog.post.repository.CommentRepository;
import com.jandilog.post.repository.PostRepository;
import com.jandilog.post.service.PostIndexService;
import com.jandilog.post.service.PostService;

// 게시물 관리 탭의 관리자 삭제 (기능명세서 3·9장, AD-01 게시물 관리). 목록은 AdminPostQueryService가 맡는다.
// 삭제는 소프트 삭제(deletedAt)이고 되돌릴 수 없는 행동이라 admin_action_log에 남긴다.
// 글 삭제는 PostService와 같은 순서(MongoDB 먼저, post_index 나중)이고 색인 · 로그 쓰기가 실패하면 삭제 표시를 되돌린다 (DB명세서 4-1)
@Service
public class AdminPostService {

	private static final String TARGET_TYPE_POST = "POST";
	private static final String TARGET_TYPE_COMMENT = "COMMENT";

	private final AdminPostQueryRepository queryRepository;
	private final PostRepository postRepository;
	private final CommentRepository commentRepository;
	private final PostService postService;
	private final PostIndexService postIndexService;
	private final AdminActionLogRepository actionLogRepository;
	private final TransactionTemplate transaction;
	private final Clock clock;

	public AdminPostService(AdminPostQueryRepository queryRepository, PostRepository postRepository,
			CommentRepository commentRepository, PostService postService, PostIndexService postIndexService,
			AdminActionLogRepository actionLogRepository, PlatformTransactionManager transactionManager,
			Clock clock) {
		this.queryRepository = queryRepository;
		this.postRepository = postRepository;
		this.commentRepository = commentRepository;
		this.postService = postService;
		this.postIndexService = postIndexService;
		this.actionLogRepository = actionLogRepository;
		this.transaction = new TransactionTemplate(transactionManager);
		this.clock = clock;
	}

	// 관리자 글 삭제. 이미 삭제된 글은 POST_DELETED, 없는 글은 NOT_FOUND
	public void deletePost(long adminId, String postId) {
		Post current = postService.loadAlive(postId);
		if (!postRepository.softDelete(current.getId(), clock.instant())) {
			throw new ApiException(ErrorCode.POST_DELETED);
		}
		String hexId = current.getId().toHexString();
		try {
			transaction.executeWithoutResult(status -> {
				postIndexService.markDeleted(hexId);
				actionLogRepository.save(AdminActionLog.of(adminId, AdminActionType.DELETE_POST, TARGET_TYPE_POST,
						hexId, null, LocalDateTime.now(clock)));
			});
		}
		catch (RuntimeException e) {
			postRepository.clearDeleted(current.getId());
			throw e;
		}
	}

	// 관리자 댓글 삭제. 없거나 이미 삭제된 댓글은 NOT_FOUND
	public void deleteComment(long adminId, String commentId) {
		Comment comment = commentRepository.findById(parseId(commentId))
				.filter(found -> found.getDeletedAt() == null)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (!commentRepository.softDelete(comment.getId(), clock.instant())) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		try {
			actionLogRepository.save(AdminActionLog.of(adminId, AdminActionType.DELETE_COMMENT, TARGET_TYPE_COMMENT,
					comment.getId().toHexString(), null, LocalDateTime.now(clock)));
		}
		catch (RuntimeException e) {
			queryRepository.clearCommentDeleted(comment.getId());
			throw e;
		}
	}

	private static ObjectId parseId(String id) {
		if (id == null || !ObjectId.isValid(id.strip())) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return new ObjectId(id.strip());
	}

}
