package com.jandilog.exemption.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.jandilog.judgment.domain.JudgmentStatus;
import com.jandilog.judgment.domain.WeeklyJudgment;

// 면제·정정이 판정 행을 찾는 조회. 회원 락을 잡기 전에 대상 회원만 먼저 알아내야 해서 엔티티가 아닌 id를 돌려준다.
// WeeklyJudgmentRepository는 건드리지 않으려고 따로 둔다
public interface JudgmentLookupRepository extends Repository<WeeklyJudgment, Long> {

	@Query("select j.memberId from WeeklyJudgment j where j.id = :id")
	Optional<Long> findMemberIdById(@Param("id") long id);

	// 그 주에 지정한 상태인 판정의 회원 id, 오름차순(여러 회원을 잠글 때 순서를 고정해 교착을 피한다)
	@Query("select j.memberId from WeeklyJudgment j where j.weekStart = :weekStart and j.status in :statuses order by j.memberId")
	List<Long> findMemberIdsByWeekAndStatusIn(@Param("weekStart") LocalDate weekStart,
			@Param("statuses") Collection<JudgmentStatus> statuses);

}
