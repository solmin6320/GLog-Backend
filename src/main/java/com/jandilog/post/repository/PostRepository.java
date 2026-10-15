package com.jandilog.post.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.BasicQuery;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import com.jandilog.post.domain.Post;
import com.jandilog.post.domain.PostType;

// MongoDB posts 컬렉션 접근. 수정·삭제는 "아직 삭제되지 않은 글"만 대상으로 한 조건부 쓰기라 삭제와 겹쳐도 되살아나지 않는다
@Repository
public class PostRepository {

	private final MongoTemplate mongo;

	public PostRepository(MongoTemplate mongo) {
		this.mongo = mongo;
	}

	public void insert(Post post) {
		mongo.insert(post);
	}

	public Optional<Post> findById(ObjectId id) {
		return Optional.ofNullable(mongo.findById(id, Post.class));
	}

	// 삭제되지 않은 글이면 통째로 바꾸고 바꾸기 전 문서를 돌려준다. 이미 삭제됐거나 없으면 빈 값
	public Optional<Post> replaceIfAlive(Post replacement) {
		Query query = Query.query(Criteria.where("_id").is(replacement.getId()).and("deletedAt").isNull());
		return Optional.ofNullable(mongo.findAndReplace(query, replacement));
	}

	// 보상용: 색인 쓰기가 실패했을 때 바꾸기 전 문서로 되돌린다. 그 사이 삭제된 글은 되살리지 않는다
	public void restore(Post previous) {
		replaceIfAlive(previous);
	}

	// 보상용: 색인 쓰기가 실패한 새 글을 지운다
	public void remove(ObjectId id) {
		mongo.remove(Query.query(Criteria.where("_id").is(id)), Post.class);
	}

	public boolean softDelete(ObjectId id, Instant now) {
		Query query = Query.query(Criteria.where("_id").is(id).and("deletedAt").isNull());
		return mongo.updateFirst(query, new Update().set("deletedAt", now), Post.class).getMatchedCount() == 1;
	}

	// 보상용: 삭제 표시를 되돌린다
	public void clearDeleted(ObjectId id) {
		mongo.updateFirst(Query.query(Criteria.where("_id").is(id)), new Update().unset("deletedAt"), Post.class);
	}

	// 최신순(_id 내림차순). after보다 오래된 글부터 limit개
	public List<Post> findPage(PostSearchCondition condition, ObjectId after, int limit) {
		Document filter = filter(condition);
		if (after != null) {
			filter.append("_id", new Document("$lt", after));
		}
		Query query = new BasicQuery(filter).with(Sort.by(Sort.Direction.DESC, "_id")).limit(limit);
		return mongo.find(query, Post.class);
	}

	// 팀 글 관리용: 그 팀에 연결된 삭제되지 않은 글, 최신순. after보다 오래된 글부터 limit개 (TM-06 ⑧)
	public List<Post> findPageByTeam(long teamId, ObjectId after, int limit) {
		Criteria criteria = Criteria.where("teamId").is(teamId).and("deletedAt").isNull();
		if (after != null) {
			criteria = criteria.and("_id").lt(after);
		}
		return mongo.find(Query.query(criteria).with(Sort.by(Sort.Direction.DESC, "_id")).limit(limit), Post.class);
	}

	// 팀 댓글 관리용: 그 팀에 연결된 삭제되지 않은 글의 _id 목록 (DB명세서 4-6 1단계)
	public List<ObjectId> findAliveIdsByTeam(long teamId) {
		Query query = Query.query(Criteria.where("teamId").is(teamId).and("deletedAt").isNull());
		query.fields().include("_id");
		return mongo.find(query, Post.class).stream().map(Post::getId).toList();
	}

	public long count(PostSearchCondition condition) {
		return mongo.count(new BasicQuery(filter(condition)), Post.class);
	}

	// 태그를 많이 쓴 순(같으면 이름순) 상위 limit개. type이 null이면 전체 글
	public List<Document> popularTags(PostType type, int limit) {
		Criteria match = Criteria.where("deletedAt").isNull();
		if (type != null) {
			match = match.and("type").is(type.name());
		}
		Aggregation aggregation = Aggregation.newAggregation(
				Aggregation.match(match),
				Aggregation.unwind("tags"),
				Aggregation.group("tags").count().as("count"),
				Aggregation.sort(Sort.by(Sort.Order.desc("count"), Sort.Order.asc("_id"))),
				Aggregation.limit(limit));
		return mongo.aggregate(aggregation, Post.class, Document.class).getMappedResults();
	}

	// 삭제되지 않은 글 + 종류 + 태그(정확히 일치) + 검색어(제목·내용·태그 텍스트 인덱스, 모든 검색어를 포함)
	private static Document filter(PostSearchCondition condition) {
		Document filter = new Document("type", condition.type().name()).append("deletedAt", null);
		if (condition.tag() != null) {
			filter.append("tags", condition.tag());
		}
		if (!condition.terms().isEmpty()) {
			// 검색어마다 따옴표로 감싸면 연산자(-, 따옴표)가 글자로 취급되고 모두 포함한 글만 찾는다
			String search = condition.terms().stream().map(term -> "\"" + term + "\"").collect(Collectors.joining(" "));
			filter.append("$text", new Document("$search", search));
		}
		return filter;
	}

}
