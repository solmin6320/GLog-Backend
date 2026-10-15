package com.jandilog.team.board.repository;

import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import com.jandilog.post.domain.Post;

// 팀 현황판의 "이 팀의 기록글" 조회 (DB명세서 3-1 {teamId, createdAt} 인덱스). post 패키지의 저장소는 건드리지 않고 따로 둔다.
// 화면설계서 TM-05 ⑦ 본문대로 이 팀에 연결된 글 전체이고, 필수 항목이 빈 글도 포함한다
@Repository
public class TeamBoardPostRepository {

	private final MongoTemplate mongo;

	public TeamBoardPostRepository(MongoTemplate mongo) {
		this.mongo = mongo;
	}

	// 이 팀에 연결된 지워지지 않은 글 최신순 limit개. 같은 시각이면 _id가 큰(나중에 쓴) 글이 먼저
	public List<Post> findLatestLinked(long teamId, int limit) {
		Query query = Query.query(Criteria.where("teamId").is(teamId).and("deletedAt").isNull())
				.with(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("_id")))
				.limit(limit);
		return mongo.find(query, Post.class);
	}

}
