package com.jandilog.post.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import com.jandilog.post.domain.Comment;

// MongoDB comments 컬렉션 접근. 목록은 글 id 묶음으로 한 번에 읽어 N+1을 피한다
@Repository
public class CommentRepository {

	private final MongoTemplate mongo;

	public CommentRepository(MongoTemplate mongo) {
		this.mongo = mongo;
	}

	public void insert(Comment comment) {
		mongo.insert(comment);
	}

	public Optional<Comment> findById(ObjectId id) {
		return Optional.ofNullable(mongo.findById(id, Comment.class));
	}

	// 글 상세의 댓글 목록: 오래된 순
	public List<Comment> findAliveByPostIds(Collection<ObjectId> postIds) {
		Query query = Query.query(Criteria.where("postId").in(postIds).and("deletedAt").isNull())
				.with(Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("_id")));
		return mongo.find(query, Comment.class);
	}

	// 팀 댓글 관리용: 글 id 묶음에 달린 삭제되지 않은 댓글, 최신순. after보다 오래된 댓글부터 limit개 (DB명세서 4-6 2단계)
	public List<Comment> findAlivePageByPostIds(Collection<ObjectId> postIds, ObjectId after, int limit) {
		Criteria criteria = Criteria.where("postId").in(postIds).and("deletedAt").isNull();
		if (after != null) {
			criteria = criteria.and("_id").lt(after);
		}
		return mongo.find(Query.query(criteria).with(Sort.by(Sort.Direction.DESC, "_id")).limit(limit), Comment.class);
	}

	public Map<ObjectId, Integer> countAliveByPostIds(Collection<ObjectId> postIds) {
		Aggregation aggregation = Aggregation.newAggregation(
				Aggregation.match(Criteria.where("postId").in(postIds).and("deletedAt").isNull()),
				Aggregation.group("postId").count().as("count"));
		Map<ObjectId, Integer> counts = new HashMap<>();
		for (Document row : mongo.aggregate(aggregation, Comment.class, Document.class).getMappedResults()) {
			counts.put(row.get("_id", ObjectId.class), row.getInteger("count"));
		}
		return counts;
	}

	// 삭제되지 않은 댓글만 고친다. 바뀐 게 없으면 false
	public boolean updateContentIfAlive(ObjectId id, String content, Instant now) {
		Query query = Query.query(Criteria.where("_id").is(id).and("deletedAt").isNull());
		return mongo.updateFirst(query, new Update().set("content", content).set("updatedAt", now), Comment.class)
				.getMatchedCount() == 1;
	}

	public boolean softDelete(ObjectId id, Instant now) {
		Query query = Query.query(Criteria.where("_id").is(id).and("deletedAt").isNull());
		return mongo.updateFirst(query, new Update().set("deletedAt", now), Comment.class).getMatchedCount() == 1;
	}

}
