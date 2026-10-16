package com.jandilog.profile.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.jandilog.judgment.domain.JudgmentStatus;
import com.jandilog.judgment.domain.SkipReason;
import com.jandilog.judgment.domain.WeeklyJudgment;

// 프로필 판정 이력(PR-01 ⑩)이 판정 행을 읽는 조회. WeeklyJudgmentRepository는 건드리지 않으려고 따로 둔다
public interface ProfileJudgmentQueryRepository extends Repository<WeeklyJudgment, Long> {

	// before보다 이전 주를 최근 주부터. 팀이 없던 주(제외 · NO_TEAM)는 쿼리에서 걸러 페이지 크기가 어긋나지 않게 한다.
	// 첫 참가 주(FIRST_WEEK)와 면제 주는 그대로 나온다
	@Query("""
			select j from WeeklyJudgment j
			where j.memberId = :memberId and j.weekStart < :before
			  and (j.status <> :excluded or j.skipReason is null or j.skipReason <> :noTeam)
			order by j.weekStart desc
			""")
	List<WeeklyJudgment> findVisiblePage(@Param("memberId") long memberId, @Param("before") LocalDate before,
			@Param("excluded") JudgmentStatus excluded, @Param("noTeam") SkipReason noTeam, Pageable pageable);

}
