package com.jandilog.team.repository;

import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.jandilog.warning.domain.WarningTeam;

// 팀 변동(추방·팀 삭제·재초대 복귀)이 경고의 팀 카테고리를 읽고 바꾸는 조회. warning 패키지의 저장소는 건드리지 않고 따로 둔다
public interface TeamWarningRepository extends Repository<WarningTeam, WarningTeam.Key> {

	// 그 회원의 경고에 붙은 그 팀 카테고리 중 아직 빠지지 않은 것
	@Query("""
			select wt from WarningTeam wt
			where wt.teamId = :teamId and wt.removedAt is null
			  and wt.warningId in (select w.id from Warning w where w.memberId = :memberId)
			""")
	List<WarningTeam> findLiveCategories(@Param("teamId") long teamId, @Param("memberId") long memberId);

	// 그 팀 카테고리 중 이미 빠진 것(추방으로 빠진 카테고리를 되살릴 때)
	@Query("""
			select wt from WarningTeam wt
			where wt.teamId = :teamId and wt.removedAt is not null
			  and wt.warningId in (select w.id from Warning w where w.memberId = :memberId)
			""")
	List<WarningTeam> findRemovedCategories(@Param("teamId") long teamId, @Param("memberId") long memberId);

	// 팀 삭제: 팀원 누구의 경고든 그 팀 카테고리가 붙어 있으면 모두 뺀다 (자진 탈퇴해 경고만 남은 사람 포함)
	@Query("select wt from WarningTeam wt where wt.teamId = :teamId and wt.removedAt is null")
	List<WarningTeam> findLiveCategoriesOfTeam(@Param("teamId") long teamId);

	// 경고에 남은(빠지지 않은) 카테고리 수. 0이면 경고를 소프트 삭제한다
	@Query("select count(wt) from WarningTeam wt where wt.warningId = :warningId and wt.removedAt is null")
	long countLiveCategories(@Param("warningId") long warningId);

	// 그 팀 카테고리가 붙은 경고를 가진 회원 id. 팀 삭제 때 재계산할 회원을 모은다
	@Query("""
			select distinct w.memberId from Warning w, WarningTeam wt
			where wt.warningId = w.id and wt.teamId = :teamId and wt.removedAt is null
			""")
	List<Long> findMemberIdsWithLiveCategory(@Param("teamId") long teamId);

}
