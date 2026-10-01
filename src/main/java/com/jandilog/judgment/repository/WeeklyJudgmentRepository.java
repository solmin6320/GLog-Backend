package com.jandilog.judgment.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.jandilog.judgment.domain.HoldReason;
import com.jandilog.judgment.domain.JudgmentStatus;
import com.jandilog.judgment.domain.WeeklyJudgment;

public interface WeeklyJudgmentRepository extends JpaRepository<WeeklyJudgment, Long> {

	Optional<WeeklyJudgment> findByMemberIdAndWeekStart(long memberId, LocalDate weekStart);

	// 회원의 전체 판정 이력, 오래된 주부터. 경고 재계산이 처음부터 훑는 입력이다
	List<WeeklyJudgment> findByMemberIdOrderByWeekStartAsc(long memberId);

	// 보류 목록 (idx_judgment_status)
	List<WeeklyJudgment> findByStatusOrderByWeekStartAscMemberIdAsc(JudgmentStatus status);

	// 자동 재시도 대상: 보류이고 사유가 API 오류이며 횟수가 남은 건
	List<WeeklyJudgment> findByStatusAndHoldReasonAndRetryCountLessThanOrderByWeekStartAscMemberIdAsc(
			JudgmentStatus status, HoldReason holdReason, int retryCount);

	// 사람이 봐야 하는 보류: 자동 재시도가 소진됐거나 아이디 불일치(재로그인 필요). 재시도가 남은 건은 올리지 않는다
	@Query("""
			select j from WeeklyJudgment j
			where j.status = :hold and (j.holdReason = :mismatch or j.retryCount >= :maxRetries)
			order by j.weekStart asc, j.memberId asc
			""")
	List<WeeklyJudgment> findManualHolds(@Param("hold") JudgmentStatus hold, @Param("mismatch") HoldReason mismatch,
			@Param("maxRetries") int maxRetries);

	// 내 판정 이력: 기준 주차보다 이전 주를 최근 주부터 (커서 기반 페이지)
	List<WeeklyJudgment> findByMemberIdAndWeekStartLessThanOrderByWeekStartDesc(long memberId, LocalDate before,
			Pageable pageable);

}
