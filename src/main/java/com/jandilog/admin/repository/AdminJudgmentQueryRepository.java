package com.jandilog.admin.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.jandilog.judgment.domain.HoldReason;
import com.jandilog.judgment.domain.JudgmentStatus;
import com.jandilog.judgment.domain.SkipReason;
import com.jandilog.judgment.domain.WeeklyJudgment;

// 관리자 대시보드가 판정 행을 읽는 조회 모음 (AD-01 ⑤~⑨). WeeklyJudgmentRepository는 건드리지 않으려고 따로 둔다.
// 사람이 봐야 하는 보류 = 아이디 불일치이거나 자동 재시도 횟수를 다 쓴 건 (기능명세서 5장)
public interface AdminJudgmentQueryRepository extends Repository<WeeklyJudgment, Long> {

	@Query("select count(j) from WeeklyJudgment j where j.weekStart = :week and j.status = :status")
	long countByWeekAndStatus(@Param("week") LocalDate week, @Param("status") JudgmentStatus status);

	@Query("""
			select count(j) from WeeklyJudgment j
			where j.weekStart = :week and j.status = :status and j.skipReason = :reason
			""")
	long countByWeekAndSkipReason(@Param("week") LocalDate week, @Param("status") JudgmentStatus status,
			@Param("reason") SkipReason reason);

	// 그 주 판정이 확정된 마지막 시각 ("9/15 07:00 실행"). 확정된 행이 없으면 null
	@Query("select max(j.judgedAt) from WeeklyJudgment j where j.weekStart = :week")
	LocalDateTime findLastJudgedAt(@Param("week") LocalDate week);

	// 판정 결과 목록: 그 주 · 결과 필터 · 회원 검색(닉네임·GitHub 아이디). id 오름차순 키셋
	@Query("""
			select j from WeeklyJudgment j, Member m
			where m.id = j.memberId and j.weekStart = :week and j.status in :statuses and j.id > :afterId
			  and (lower(m.nickname) like :pattern escape '!' or lower(m.githubLogin) like :pattern escape '!')
			order by j.id asc
			""")
	List<WeeklyJudgment> findResultPage(@Param("week") LocalDate week,
			@Param("statuses") Collection<JudgmentStatus> statuses, @Param("pattern") String pattern,
			@Param("afterId") long afterId, Pageable pageable);

	@Query("""
			select count(j) from WeeklyJudgment j
			where j.status = :hold and j.weekStart = :week
			  and (j.holdReason = :mismatch or j.retryCount >= :maxRetries)
			""")
	long countManualHoldsOfWeek(@Param("week") LocalDate week, @Param("hold") JudgmentStatus hold,
			@Param("mismatch") HoldReason mismatch, @Param("maxRetries") int maxRetries);

	// 보류 목록: 모든 주의 사람이 봐야 하는 보류, id 오름차순 키셋
	@Query("""
			select j from WeeklyJudgment j
			where j.status = :hold and (j.holdReason = :mismatch or j.retryCount >= :maxRetries) and j.id > :afterId
			order by j.id asc
			""")
	List<WeeklyJudgment> findManualHoldPage(@Param("hold") JudgmentStatus hold, @Param("mismatch") HoldReason mismatch,
			@Param("maxRetries") int maxRetries, @Param("afterId") long afterId, Pageable pageable);

	@Query("""
			select count(j) from WeeklyJudgment j
			where j.status = :hold and (j.holdReason = :mismatch or j.retryCount >= :maxRetries)
			""")
	long countManualHolds(@Param("hold") JudgmentStatus hold, @Param("mismatch") HoldReason mismatch,
			@Param("maxRetries") int maxRetries);

	@Query("select count(j) from WeeklyJudgment j where j.status = :hold and j.holdReason = :mismatch")
	long countIdentityMismatchHolds(@Param("hold") JudgmentStatus hold, @Param("mismatch") HoldReason mismatch);

}
