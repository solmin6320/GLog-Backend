package com.jandilog.team.repository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.jandilog.post.domain.PostIndex;

// 팀 삭제 때 post_index의 팀 연결을 끊는다 (DB명세서 4-4). post 패키지의 저장소는 건드리지 않고 따로 둔다
public interface TeamPostIndexRepository extends Repository<PostIndex, Long> {

	@Modifying(flushAutomatically = true)
	@Query("update PostIndex p set p.teamId = null where p.teamId = :teamId")
	int clearTeam(@Param("teamId") long teamId);

}
