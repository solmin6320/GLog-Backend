package com.jandilog.admin.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.bson.types.ObjectId;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.jandilog.admin.domain.AdminActionLog;
import com.jandilog.admin.domain.AdminActionType;
import com.jandilog.admin.dto.AdminPostFilter;
import com.jandilog.admin.dto.AdminPostItem;
import com.jandilog.admin.dto.AdminPostKind;
import com.jandilog.admin.dto.AdminPostPage;
import com.jandilog.admin.repository.AdminActionLogRepository;
import com.jandilog.admin.repository.AdminMemberLookupRepository;
import com.jandilog.admin.repository.AdminPostQueryRepository;
import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.common.pagination.CursorCodec;
import com.jandilog.common.time.KstFormats;
import com.jandilog.common.validation.InputRules;
import com.jandilog.member.domain.Member;
import com.jandilog.member.dto.MemberBrief;
import com.jandilog.member.repository.MemberRepository;
import com.jandilog.post.domain.Comment;
import com.jandilog.post.domain.Post;
import com.jandilog.post.repository.CommentRepository;
import com.jandilog.post.repository.PostRepository;
import com.jandilog.post.service.PostIndexService;
import com.jandilog.post.service.PostService;

// 게시물 관리 탭 (기능명세서 3·9장, AD-01 게시물 관리): 글 · 댓글 목록과 관리자 삭제.
// 삭제는 소프트 삭제(deletedAt)이고 되돌릴 수 없는 행동이라 admin_action_log에 남긴다.
// 글 삭제는 PostService와 같은 순서(MongoDB 먼저, post_index 나중)이고 색인 · 로그 쓰기가 실패하면 삭제 표시를 되돌린다 (DB명세서 4-1)
@Service
public class AdminPostService {

	private static final int AUTHOR_MATCH_LIMIT = 500;
	private static final int EXCERPT_MAX_LENGTH = 100;
	private static final String TARGET_TYPE_POST = "POST";
	private static final String TARGET_TYPE_COMMENT = "COMMENT";

	private final AdminPostQueryRepository queryRepository;
	private final AdminMemberLookupRepository memberLookupRepository;
	private final MemberRepository memberRepository;
	private final PostRepository postRepository;
	private final CommentRepository commentRepository;
	private final PostService postService;
	private final PostIndexService postIndexService;
	private final AdminActionLogRepository actionLogRepository;
	private final TransactionTemplate transaction;
	private final Clock clock;

	public AdminPostService(AdminPostQueryRepository queryRepository,
			AdminMemberLookupRepository memberLookupRepository, MemberRepository memberRepository,
			PostRepository postRepository, CommentRepository commentRepository, PostService postService,
			PostIndexService postIndexService, AdminActionLogRepository actionLogRepository,
			PlatformTransactionManager transactionManager, Clock clock) {
		this.queryRepository = queryRepository;
		this.memberLookupRepository = memberLookupRepository;
		this.memberRepository = memberRepository;
		this.postRepository = postRepository;
		this.commentRepository = commentRepository;
		this.postService = postService;
		this.postIndexService = postIndexService;
		this.actionLogRepository = actionLogRepository;
		this.transaction = new TransactionTemplate(transactionManager);
		this.clock = clock;
	}

	// 최신순 커서 20개. 글과 댓글은 _id(시각순)로 한 줄로 합쳐 정렬한다. 검색어는 제목 · 내용 · 작성자
	public AdminPostPage list(AdminPostFilter filter, String keyword, String cursor) {
		AdminPostFilter effective = filter == null ? AdminPostFilter.ALL : filter;
		String term = InputRules.keyword(keyword);
		ObjectId after = decodeCursor(cursor);
		List<Long> authorIds = term == null ? List.of()
				: memberLookupRepository.findIdsByKeyword(InputRules.likePattern(term),
						PageRequest.of(0, AUTHOR_MATCH_LIMIT));

		// 한 건 더 읽어서 다음 페이지가 있는지 판단한다
		int limit = CursorCodec.PAGE_SIZE + 1;
		List<Post> posts = effective.includesPosts() ? queryRepository.findPosts(after, term, authorIds, limit)
				: List.of();
		List<Comment> comments = effective.includesComments()
				? queryRepository.findComments(after, term, authorIds, limit) : List.of();

		List<Row> rows = new ArrayList<>();
		posts.forEach(post -> rows.add(new Row(post.getId(), post, null)));
		comments.forEach(comment -> rows.add(new Row(comment.getId(), null, comment)));
		rows.sort(Comparator.comparing((Row row) -> row.id()).reversed());

		boolean hasNext = rows.size() > CursorCodec.PAGE_SIZE;
		List<Row> page = hasNext ? rows.subList(0, CursorCodec.PAGE_SIZE) : rows;
		String nextCursor = hasNext ? encodeCursor(page.get(page.size() - 1).id()) : null;

		Set<Long> authorIdsOnPage = new HashSet<>();
		Set<ObjectId> parentPostIds = new HashSet<>();
		for (Row row : page) {
			authorIdsOnPage.add(row.post() != null ? row.post().getAuthorId() : row.comment().getAuthorId());
			if (row.comment() != null) {
				parentPostIds.add(row.comment().getPostId());
			}
		}
		Map<Long, Member> members = memberRepository.findAllById(authorIdsOnPage).stream()
				.collect(Collectors.toMap(Member::getId, Function.identity()));
		Map<ObjectId, String> titles = queryRepository.findPostTitles(parentPostIds);

		List<AdminPostItem> items = page.stream().map(row -> toItem(row, members, titles)).toList();
		return new AdminPostPage(items, nextCursor);
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

	private AdminPostItem toItem(Row row, Map<Long, Member> members, Map<ObjectId, String> titles) {
		if (row.post() != null) {
			Post post = row.post();
			return new AdminPostItem(AdminPostKind.POST, post.getId().toHexString(), post.getId().toHexString(),
					post.getTitle(), null, author(members, post.getAuthorId()), KstFormats.of(post.getCreatedAt()));
		}
		Comment comment = row.comment();
		return new AdminPostItem(AdminPostKind.COMMENT, comment.getId().toHexString(),
				comment.getPostId().toHexString(), titles.getOrDefault(comment.getPostId(), ""),
				excerpt(comment.getContent()), author(members, comment.getAuthorId()),
				KstFormats.of(comment.getCreatedAt()));
	}

	private static MemberBrief author(Map<Long, Member> members, long authorId) {
		Member member = members.get(authorId);
		return member != null ? MemberBrief.from(member) : new MemberBrief(authorId, "", "");
	}

	private static String excerpt(String content) {
		if (content == null || content.codePointCount(0, content.length()) <= EXCERPT_MAX_LENGTH) {
			return content;
		}
		return content.substring(0, content.offsetByCodePoints(0, EXCERPT_MAX_LENGTH));
	}

	private static ObjectId parseId(String id) {
		if (id == null || !ObjectId.isValid(id.strip())) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return new ObjectId(id.strip());
	}

	// 커서는 마지막 행의 ObjectId를 Base64url로 감싼 불투명 문자열
	private static String encodeCursor(ObjectId lastId) {
		return Base64.getUrlEncoder().withoutPadding()
				.encodeToString(lastId.toHexString().getBytes(StandardCharsets.UTF_8));
	}

	private static ObjectId decodeCursor(String cursor) {
		String hex = CursorCodec.decode(cursor);
		if (hex == null) {
			return null;
		}
		if (!ObjectId.isValid(hex)) {
			throw new ApiException(ErrorCode.INVALID_INPUT);
		}
		return new ObjectId(hex);
	}

	private record Row(ObjectId id, Post post, Comment comment) {
	}

}
