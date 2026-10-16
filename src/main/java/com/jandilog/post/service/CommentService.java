package com.jandilog.post.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.post.domain.Comment;
import com.jandilog.post.domain.Post;
import com.jandilog.post.dto.CommentResponse;
import com.jandilog.post.dto.CursorPage;
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

	// 글 상세의 댓글 한 페이지: 오래된 순(작성 시각, id) 커서 20개 (Q-06)
	public CursorPage<CommentResponse> findPage(ObjectId postId, String cursor) {
		Cursor after = Cursor.decode(cursor);
		// 한 건 더 읽어서 다음 페이지가 있는지 판단한다
		List<Comment> rows = commentRepository.findAlivePageByPostId(postId, after == null ? null : after.createdAt(),
				after == null ? null : after.id(), PostService.PAGE_SIZE + 1);
		boolean hasNext = rows.size() > PostService.PAGE_SIZE;
		List<Comment> page = hasNext ? rows.subList(0, PostService.PAGE_SIZE) : rows;
		String nextCursor = hasNext ? Cursor.of(page.get(page.size() - 1)).encode() : null;
		return new CursorPage<>(page.stream().map(CommentResponse::from).toList(), nextCursor);
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

	// 목록 위치. 작성 시각(epoch ms) + id 를 Base64url로 감싼다. 같은 시각 댓글은 id로 가른다
	record Cursor(Instant createdAt, ObjectId id) {

		static Cursor of(Comment comment) {
			return new Cursor(comment.getCreatedAt(), comment.getId());
		}

		String encode() {
			String raw = createdAt.toEpochMilli() + "|" + id.toHexString();
			return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
		}

		// 비었으면 첫 페이지(null), 형식이 틀리면 INVALID_INPUT
		static Cursor decode(String cursor) {
			if (cursor == null || cursor.isEmpty()) {
				return null;
			}
			try {
				String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
				String[] parts = raw.split("\\|", -1);
				if (parts.length == 2 && ObjectId.isValid(parts[1])) {
					return new Cursor(Instant.ofEpochMilli(Long.parseLong(parts[0])), new ObjectId(parts[1]));
				}
			} catch (IllegalArgumentException e) {
				// 아래에서 같은 오류로 처리
			}
			throw new ApiException(ErrorCode.INVALID_INPUT);
		}

	}

}
