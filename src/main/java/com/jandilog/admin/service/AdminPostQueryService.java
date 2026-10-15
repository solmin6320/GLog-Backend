package com.jandilog.admin.service;

import java.util.ArrayList;
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

import com.jandilog.admin.dto.AdminPostFilter;
import com.jandilog.admin.dto.AdminPostItem;
import com.jandilog.admin.dto.AdminPostKind;
import com.jandilog.admin.dto.AdminPostPage;
import com.jandilog.admin.repository.AdminMemberLookupRepository;
import com.jandilog.admin.repository.AdminPostQueryRepository;
import com.jandilog.common.pagination.CursorCodec;
import com.jandilog.common.pagination.PageSlice;
import com.jandilog.common.time.KstFormats;
import com.jandilog.common.validation.InputRules;
import com.jandilog.member.domain.Member;
import com.jandilog.member.dto.MemberBrief;
import com.jandilog.member.repository.MemberRepository;
import com.jandilog.post.domain.Comment;
import com.jandilog.post.domain.Post;
import com.jandilog.post.service.PostService;

// 게시물 관리 탭의 글 · 댓글 목록 (기능명세서 3·9장, AD-01 게시물 관리). 삭제는 AdminPostService가 맡는다
@Service
public class AdminPostQueryService {

	private static final int AUTHOR_MATCH_LIMIT = 500;
	private static final int EXCERPT_MAX_LENGTH = 100;

	private final AdminPostQueryRepository queryRepository;
	private final AdminMemberLookupRepository memberLookupRepository;
	private final MemberRepository memberRepository;

	public AdminPostQueryService(AdminPostQueryRepository queryRepository,
			AdminMemberLookupRepository memberLookupRepository, MemberRepository memberRepository) {
		this.queryRepository = queryRepository;
		this.memberLookupRepository = memberLookupRepository;
		this.memberRepository = memberRepository;
	}

	// 최신순 커서 20개. 글과 댓글은 _id(시각순)로 한 줄로 합쳐 정렬한다. 검색어는 제목 · 내용 · 작성자
	public AdminPostPage list(AdminPostFilter filter, String keyword, String cursor) {
		AdminPostFilter effective = filter == null ? AdminPostFilter.ALL : filter;
		String term = InputRules.keyword(keyword);
		ObjectId after = PostService.decodeCursor(cursor);
		List<Long> authorIds = matchingAuthorIds(term);

		List<Row> rows = newestFirst(effective, after, term, authorIds);
		PageSlice<Row> page = PageSlice.of(rows, last -> PostService.encodeCursor(last.id()));
		return new AdminPostPage(toItems(page.rows()), page.nextCursor());
	}

	// 검색어가 있으면 닉네임 · 아이디가 맞는 작성자 id를 먼저 찾는다
	private List<Long> matchingAuthorIds(String term) {
		return term == null ? List.of()
				: memberLookupRepository.findIdsByKeyword(InputRules.likePattern(term),
						PageRequest.of(0, AUTHOR_MATCH_LIMIT));
	}

	// 필터에 맞는 글 · 댓글을 한 건 더 읽어(다음 페이지가 있는지 판단) _id 내림차순 한 줄로 합친다
	private List<Row> newestFirst(AdminPostFilter filter, ObjectId after, String term, List<Long> authorIds) {
		int limit = CursorCodec.PAGE_SIZE + 1;
		List<Post> posts = filter.includesPosts() ? queryRepository.findPosts(after, term, authorIds, limit) : List.of();
		List<Comment> comments = filter.includesComments() ? queryRepository.findComments(after, term, authorIds, limit)
				: List.of();

		List<Row> rows = new ArrayList<>();
		posts.forEach(post -> rows.add(new Row(post.getId(), post, null)));
		comments.forEach(comment -> rows.add(new Row(comment.getId(), null, comment)));
		rows.sort(Comparator.comparing((Row row) -> row.id()).reversed());
		return rows;
	}

	// 한 페이지의 작성자와 댓글의 원글 제목을 한 번씩 읽어 목록 행으로 바꾼다
	private List<AdminPostItem> toItems(List<Row> rows) {
		Set<Long> authorIdsOnPage = new HashSet<>();
		Set<ObjectId> parentPostIds = new HashSet<>();
		for (Row row : rows) {
			authorIdsOnPage.add(row.post() != null ? row.post().getAuthorId() : row.comment().getAuthorId());
			if (row.comment() != null) {
				parentPostIds.add(row.comment().getPostId());
			}
		}
		Map<Long, Member> members = memberRepository.findAllById(authorIdsOnPage).stream()
				.collect(Collectors.toMap(Member::getId, Function.identity()));
		Map<ObjectId, String> titles = queryRepository.findPostTitles(parentPostIds);

		return rows.stream().map(row -> toItem(row, members, titles)).toList();
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

	private record Row(ObjectId id, Post post, Comment comment) {
	}

}
