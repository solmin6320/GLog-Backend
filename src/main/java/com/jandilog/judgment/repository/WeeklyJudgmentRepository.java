package com.jandilog.judgment.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.jandilog.judgment.domain.JudgmentStatus;
import com.jandilog.judgment.domain.WeeklyJudgment;

public interface WeeklyJudgmentRepository extends JpaRepository<WeeklyJudgment, Long> {

	Optional<WeeklyJudgment> findByMemberIdAndWeekStart(long memberId, LocalDate weekStart);

	// 회원의 전체 판정 이력, 오래된 주부터. 경고 재계산이 처음부터 훑는 입력이다
	List<WeeklyJudgment> findByMemberIdOrderByWeekStartAsc(long memberId);

	// 보류 목록 (idx_judgment_status)
	List<WeeklyJudgment> findByStatusOrderByWeekStartAscMemberIdAsc(JudgmentStatus status);

}
