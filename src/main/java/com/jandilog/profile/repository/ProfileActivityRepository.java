package com.jandilog.profile.repository;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import com.jandilog.post.domain.Comment;
import com.jandilog.post.domain.Post;

// 프로필의 게시글 수 · 댓글 수 (DB명세서 4-5). post_index가 아니라 MongoDB를 직접 센다:
// post_index는 판정용 색인이라 집계 대상이 달라질 수 있고, 프로필에 보이는 수는 본문이 있는 글의 수여야 한다
@Repository
public class ProfileActivityRepository {

	private final MongoTemplate mongo;

	public ProfileActivityRepository(MongoTemplate mongo) {
		this.mongo = mongo;
	}

	// 삭제되지 않은 글 수
	public long countPosts(long authorId) {
		return mongo.count(Query.query(Criteria.where("authorId").is(authorId).and("deletedAt").isNull()), Post.class);
	}

	// 삭제되지 않은 댓글 수
	public long countComments(long authorId) {
		return mongo.count(Query.query(Criteria.where("authorId").is(authorId).and("deletedAt").isNull()),
				Comment.class);
	}

}
