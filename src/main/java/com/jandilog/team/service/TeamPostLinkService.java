package com.jandilog.team.service;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.jandilog.post.domain.Post;
import com.jandilog.team.repository.TeamPostIndexRepository;

// 팀 삭제 때 글의 팀 연결을 두 곳 모두 끊는다. 글은 남고 팀 연결만 해제된다 (기능명세서 2장, DB명세서 4-4).
// Mongo posts와 MariaDB post_index를 함께 바꿔야 글 목록과 판정 색인이 서로 다른 팀을 가리키지 않는다.
// MariaDB 트랜잭션 안의 마지막 단계로 부른다: Mongo가 실패하면 예외가 올라가 앞선 MariaDB 변경도 롤백된다
@Service
public class TeamPostLinkService {

	private final MongoTemplate mongo;
	private final TeamPostIndexRepository postIndexRepository;

	public TeamPostLinkService(MongoTemplate mongo, TeamPostIndexRepository postIndexRepository) {
		this.mongo = mongo;
		this.postIndexRepository = postIndexRepository;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void unlinkTeam(long teamId) {
		postIndexRepository.clearTeam(teamId);
		// 삭제된 글까지 포함해 모든 글에서 teamId를 뺀다 (필드가 없는 것과 null은 같은 값으로 조회된다)
		mongo.updateMulti(Query.query(Criteria.where("teamId").is(teamId)), new Update().unset("teamId"), Post.class);
	}

}
