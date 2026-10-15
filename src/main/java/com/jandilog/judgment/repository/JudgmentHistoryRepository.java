package com.jandilog.judgment.repository;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import com.jandilog.judgment.domain.WeeklyJudgment;

// 기동 시 미판정 주차를 가릴 때 쓰는 판정 이력 조회. WeeklyJudgmentRepository는 건드리지 않고 따로 둔다
public interface JudgmentHistoryRepository extends Repository<WeeklyJudgment, Long> {

	// 판정 이력(확정·보류 모두)이 있는 가장 최근 주차 월요일. 이력이 하나도 없으면 비어 있다
	@Query("select max(j.weekStart) from WeeklyJudgment j")
	Optional<LocalDate> findLatestWeekStart();

}
