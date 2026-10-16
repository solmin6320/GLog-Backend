package com.jandilog.team.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.jandilog.team.domain.TeamMember;

public interface TeamMemberRepository extends JpaRepository<TeamMember, Long> {

	// 지금 소속(나간 시각이 없는 행)
	Optional<TeamMember> findByTeamIdAndMemberIdAndLeftAtIsNull(long teamId, long memberId);

	boolean existsByTeamIdAndMemberIdAndLeftAtIsNull(long teamId, long memberId);

	long countByTeamIdAndLeftAtIsNull(long teamId);

	// 팀원 목록: 참가한 순서
	List<TeamMember> findByTeamIdAndLeftAtIsNullOrderByJoinedAtAscIdAsc(long teamId);

	// 팀원 목록 첫 페이지: 팀장이 맨 앞, 나머지는 참가한 순서
	@Query("""
			select tm from TeamMember tm
			where tm.teamId = :teamId and tm.leftAt is null
			order by case when tm.memberId = :leaderId then 0 else 1 end asc, tm.joinedAt asc, tm.id asc
			""")
	List<TeamMember> findActivePage(@Param("teamId") long teamId, @Param("leaderId") long leaderId, Pageable pageable);

	// 팀원 목록 다음 페이지: 팀장은 첫 페이지에 나왔으므로 빼고, 커서(참가 시각, id) 뒤부터
	@Query("""
			select tm from TeamMember tm
			where tm.teamId = :teamId and tm.leftAt is null and tm.memberId <> :leaderId
			  and (tm.joinedAt > :joinedAt or (tm.joinedAt = :joinedAt and tm.id > :id))
			order by tm.joinedAt asc, tm.id asc
			""")
	List<TeamMember> findActivePageAfter(@Param("teamId") long teamId, @Param("leaderId") long leaderId,
			@Param("joinedAt") LocalDateTime joinedAt, @Param("id") long id, Pageable pageable);

	// 내 팀 목록: 참가한 순서(오래된 순)
	List<TeamMember> findByMemberIdAndLeftAtIsNullOrderByJoinedAtAscIdAsc(long memberId);

	// 같은 팀에서 가장 최근에 시작한 소속. (team_id, member_id, joined_at) UNIQUE를 피하는 데 쓴다
	Optional<TeamMember> findFirstByTeamIdAndMemberIdOrderByJoinedAtDesc(long teamId, long memberId);

	@Query("""
			select new com.jandilog.team.repository.TeamMemberCount(tm.teamId, count(tm))
			from TeamMember tm
			where tm.teamId in :teamIds and tm.leftAt is null
			group by tm.teamId
			""")
	List<TeamMemberCount> countActiveByTeamIds(@Param("teamIds") Collection<Long> teamIds);

	// 기준 시각의 소속 팀: 참가는 그 시각 이전이고 나간 시각이 없거나 그 시각 이후다
	@Query("""
			select distinct tm.teamId from TeamMember tm
			where tm.memberId = :memberId and tm.joinedAt <= :at and (tm.leftAt is null or tm.leftAt > :at)
			order by tm.teamId
			""")
	List<Long> findTeamIdsAt(@Param("memberId") long memberId, @Param("at") LocalDateTime at);

	@Query("""
			select tm from TeamMember tm
			where tm.memberId in :memberIds and tm.joinedAt <= :at and (tm.leftAt is null or tm.leftAt > :at)
			""")
	List<TeamMember> findMembershipsAt(@Param("memberIds") Collection<Long> memberIds, @Param("at") LocalDateTime at);

}
