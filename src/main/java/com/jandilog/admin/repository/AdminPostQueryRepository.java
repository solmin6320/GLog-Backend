package com.jandilog.admin.repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import com.jandilog.post.domain.Comment;
import com.jandilog.post.domain.Post;

// 관리자 게시물 관리 탭의 MongoDB 조회 (AD-01 게시물 관리). 삭제되지 않은 글 · 댓글을 최신순(_id 내림차순)으로 읽는다.
// 검색어는 제목 · 본문(글), 내용(댓글), 작성자 id에 걸고, 관리자 화면이라 텍스트 인덱스 대신 대소문자 무시 부분 일치(정규식)를 쓴다
@Repository
public class AdminPostQueryRepository {

	private static final String[] POST_TEXT_FIELDS = { "title", "sections.problem", "sections.cause",
			"sections.solution", "sections.did", "sections.learned" };

	private static final Pattern SPECIAL = Pattern.compile("[\\\\^$.|?*+()\\[\\]{}]");

	private final MongoTemplate mongo;

	public AdminPostQueryRepository(MongoTemplate mongo) {
		this.mongo = mongo;
	}

	// after보다 오래된 글 limit개. keyword는 null이면 전체, authorIds는 작성자 검색에 걸린 회원 id
	public List<Post> findPosts(ObjectId after, String keyword, Collection<Long> authorIds, int limit) {
		Criteria criteria = base(after);
		if (keyword != null) {
			List<Criteria> any = new ArrayList<>();
			for (String field : POST_TEXT_FIELDS) {
				any.add(Criteria.where(field).regex(containing(keyword)));
			}
			if (!authorIds.isEmpty()) {
				any.add(Criteria.where("authorId").in(authorIds));
			}
			criteria.orOperator(any);
		}
		return mongo.find(Query.query(criteria).with(Sort.by(Sort.Direction.DESC, "_id")).limit(limit), Post.class);
	}

	public List<Comment> findComments(ObjectId after, String keyword, Collection<Long> authorIds, int limit) {
		Criteria criteria = base(after);
		if (keyword != null) {
			List<Criteria> any = new ArrayList<>();
			any.add(Criteria.where("content").regex(containing(keyword)));
			if (!authorIds.isEmpty()) {
				any.add(Criteria.where("authorId").in(authorIds));
			}
			criteria.orOperator(any);
		}
		return mongo.find(Query.query(criteria).with(Sort.by(Sort.Direction.DESC, "_id")).limit(limit),
				Comment.class);
	}

	// 댓글 행에 붙일 글 제목. 삭제된 글도 제목을 읽는다
	public Map<ObjectId, String> findPostTitles(Collection<ObjectId> postIds) {
		Map<ObjectId, String> titles = new HashMap<>();
		if (postIds.isEmpty()) {
			return titles;
		}
		for (Post post : mongo.find(Query.query(Criteria.where("_id").in(postIds)), Post.class)) {
			titles.put(post.getId(), post.getTitle());
		}
		return titles;
	}

	// 보상용: 댓글 삭제 로그를 남기지 못했을 때 삭제 표시를 되돌린다
	public void clearCommentDeleted(ObjectId id) {
		mongo.updateFirst(Query.query(Criteria.where("_id").is(id)), new Update().unset("deletedAt"), Comment.class);
	}

	private static Criteria base(ObjectId after) {
		Criteria criteria = Criteria.where("deletedAt").isNull();
		if (after != null) {
			criteria = criteria.and("_id").lt(after);
		}
		return criteria;
	}

	// 검색어를 글자 그대로 부분 일치로 찾는 정규식 (대소문자 무시). 정규식 특수문자는 앞에 \를 붙여 글자로 만든다
	private static Pattern containing(String keyword) {
		return Pattern.compile(SPECIAL.matcher(keyword).replaceAll("\\\\$0"), Pattern.CASE_INSENSITIVE);
	}

}
