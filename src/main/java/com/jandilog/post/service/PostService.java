package com.jandilog.post.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.post.domain.Post;
import com.jandilog.post.domain.PostSections;
import com.jandilog.post.domain.PostType;
import com.jandilog.post.dto.CreatePostInput;
import com.jandilog.post.dto.PostPageResponse;
import com.jandilog.post.dto.PostResponse;
import com.jandilog.post.dto.TagCount;
import com.jandilog.post.dto.UpdatePostInput;
import com.jandilog.post.repository.PostRepository;
import com.jandilog.post.repository.PostSearchCondition;
import com.jandilog.team.service.TeamAccessService;

// 게시판 글 작성·수정·삭제·상세·목록 (기능명세서 3장).
// 쓰기는 MongoDB를 먼저, post_index를 나중에 쓴다. 분산 트랜잭션은 쓰지 않고, 색인 쓰기가 실패하면
// 방금 쓴 MongoDB 변경을 되돌려 두 곳이 어긋나지 않게 한다 (DB명세서 4-1)
@Service
public class PostService {

	static final int PAGE_SIZE = 20;
	private static final int POPULAR_TAG_LIMIT = 10;

	private static final Logger log = LoggerFactory.getLogger(PostService.class);

	private final PostRepository postRepository;
	private final PostIndexService postIndexService;
	private final TeamAccessService teamAccess;
	private final Clock clock;

	public PostService(PostRepository postRepository, PostIndexService postIndexService,
			TeamAccessService teamAccess, Clock clock) {
		this.postRepository = postRepository;
		this.postIndexService = postIndexService;
		this.teamAccess = teamAccess;
		this.clock = clock;
	}

	// 작성일(written_date)은 서버 저장 시각의 KST 날짜다. 클라이언트 시계는 쓰지 않는다 (E-27)
	public PostResponse create(long memberId, CreatePostInput input) {
		PostType type = input.type();
		String title = PostInputRules.title(input.title());
		PostSections sections = PostInputRules.sections(type, input.sections());
		List<String> tags = PostInputRules.tags(input.tags());
		List<String> commitUrls = PostInputRules.commitUrls(input.commitUrls());
		Long teamId = PostInputRules.teamId(input.teamId());
		// 지금 내가 소속인 삭제되지 않은 팀만 연결할 수 있다
		teamAccess.requireLinkable(teamId, memberId);

		Instant now = now();
		// ObjectId 시각을 서버 시계에 맞춰 _id 순서와 createdAt 순서가 같게 한다
		Post post = Post.create(new ObjectId(Date.from(now)), memberId, teamId, type, title, sections, tags, commitUrls,
				LocalDate.now(clock), now);

		postRepository.insert(post);
		try {
			postIndexService.register(post);
		} catch (RuntimeException e) {
			undoCreate(post);
			throw e;
		}
		return PostResponse.from(post);
	}

	// 수정해도 작성일·종류는 그대로이고, 기록글 여부는 새 본문 기준으로 다시 계산한다 (Q-11)
	public PostResponse update(long memberId, String postId, UpdatePostInput input) {
		Post current = loadAlive(postId);
		requireAuthor(current, memberId);

		String title = PostInputRules.title(input.title());
		PostSections sections = PostInputRules.sections(current.getType(), input.sections());
		List<String> tags = PostInputRules.tags(input.tags());
		List<String> commitUrls = PostInputRules.commitUrls(input.commitUrls());
		Long teamId = PostInputRules.teamId(input.teamId());
		// 이미 연결된 팀을 그대로 두는 수정은 다시 검사하지 않는다(팀에서 나간 뒤에도 내용은 고칠 수 있게). 새로 연결하거나 바꿀 때만 소속을 본다
		if (teamId != null && !teamId.equals(current.getTeamId())) {
			teamAccess.requireLinkable(teamId, memberId);
		}

		Post replacement = current.edited(teamId, title, sections, tags, commitUrls, now());
		// 그 사이 삭제됐으면 바뀌지 않는다 (EX-BD04-02)
		Optional<Post> previous = postRepository.replaceIfAlive(replacement);
		if (previous.isEmpty()) {
			throw new ApiException(ErrorCode.POST_DELETED);
		}
		try {
			postIndexService.sync(replacement);
		} catch (RuntimeException e) {
			undoUpdate(previous.get());
			throw e;
		}
		return PostResponse.from(replacement);
	}

	// 소프트 삭제: Mongo deletedAt 먼저, post_index.deleted_at 나중
	public void delete(long memberId, String postId) {
		Post current = loadAlive(postId);
		requireAuthor(current, memberId);

		if (!postRepository.softDelete(current.getId(), now())) {
			throw new ApiException(ErrorCode.POST_DELETED);
		}
		try {
			postIndexService.markDeleted(current.getId().toHexString());
		} catch (RuntimeException e) {
			undoDelete(current);
			throw e;
		}
	}

	// 삭제된 글은 404가 아니라 삭제 안내(E-31), 없는 글은 404(E-53)
	public PostResponse get(String postId) {
		return PostResponse.from(loadAlive(postId));
	}

	// 최신순 커서 기반 20개. 검색어는 2~50자(E-61, E-63), 태그는 정확히 일치하는 글만
	public PostPageResponse list(PostType type, String tag, String query, String cursor) {
		PostSearchCondition condition = new PostSearchCondition(type, PostInputRules.normalizeTag(tag),
				PostInputRules.searchTerms(query));
		ObjectId after = decodeCursor(cursor);

		// 한 건 더 읽어서 다음 페이지가 있는지 판단한다
		List<Post> rows = postRepository.findPage(condition, after, PAGE_SIZE + 1);
		boolean hasNext = rows.size() > PAGE_SIZE;
		List<Post> page = hasNext ? rows.subList(0, PAGE_SIZE) : rows;
		String nextCursor = hasNext ? encodeCursor(page.get(page.size() - 1).getId()) : null;
		return new PostPageResponse(page.stream().map(PostResponse::from).toList(), nextCursor, condition);
	}

	public int count(PostSearchCondition condition) {
		return (int) postRepository.count(condition);
	}

	// 태그 필터에 보여줄 많이 쓰인 태그 상위 10개 (BD-01 ④)
	public List<TagCount> popularTags(PostType type) {
		return postRepository.popularTags(type, POPULAR_TAG_LIMIT).stream()
				.map(row -> new TagCount(row.getString("_id"), row.getInteger("count")))
				.toList();
	}

	// 삭제되지 않은 글을 읽는다. 댓글 쓰기도 이 검사를 거친다
	public Post loadAlive(String postId) {
		Post post = postRepository.findById(parseId(postId)).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (post.isDeleted()) {
			throw new ApiException(ErrorCode.POST_DELETED);
		}
		return post;
	}

	// Mongo는 밀리초까지만 저장하므로 응답에 싣는 시각도 밀리초로 맞춘다
	private Instant now() {
		return clock.instant().truncatedTo(ChronoUnit.MILLIS);
	}

	static ObjectId parseId(String id) {
		if (id == null || !ObjectId.isValid(id)) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return new ObjectId(id);
	}

	// 글 수정·삭제는 작성자 본인만. 팀장·관리자 권한은 팀 모듈·관리자 모듈에서 따로 붙인다
	private static void requireAuthor(Post post, long memberId) {
		if (post.getAuthorId() != memberId) {
			throw new ApiException(ErrorCode.FORBIDDEN);
		}
	}

	private void undoCreate(Post post) {
		try {
			postRepository.remove(post.getId());
		} catch (RuntimeException e) {
			log.error("post_index 쓰기 실패 뒤 새 글을 지우지 못했어요. 색인이 빠진 글이 남았어요. (postId={})",
					post.getId().toHexString(), e);
		}
	}

	private void undoUpdate(Post previous) {
		try {
			postRepository.restore(previous);
		} catch (RuntimeException e) {
			log.error("post_index 쓰기 실패 뒤 글을 원래대로 되돌리지 못했어요. 두 DB가 어긋났어요. (postId={})",
					previous.getId().toHexString(), e);
		}
	}

	private void undoDelete(Post post) {
		try {
			postRepository.clearDeleted(post.getId());
		} catch (RuntimeException e) {
			log.error("post_index 쓰기 실패 뒤 삭제 표시를 되돌리지 못했어요. 두 DB가 어긋났어요. (postId={})",
					post.getId().toHexString(), e);
		}
	}

	// 커서는 마지막 글의 ObjectId를 Base64url로 감싼 불투명 문자열
	static String encodeCursor(ObjectId lastId) {
		return Base64.getUrlEncoder().withoutPadding()
				.encodeToString(lastId.toHexString().getBytes(StandardCharsets.UTF_8));
	}

	static ObjectId decodeCursor(String cursor) {
		if (cursor == null || cursor.isEmpty()) {
			return null;
		}
		try {
			String hex = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
			if (ObjectId.isValid(hex)) {
				return new ObjectId(hex);
			}
		} catch (IllegalArgumentException e) {
			// 아래에서 같은 오류로 처리
		}
		throw new ApiException(ErrorCode.INVALID_INPUT);
	}

}
