package com.jandilog.judgment.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.jandilog.judgment.domain.JudgmentDay;

public interface JudgmentDayRepository extends JpaRepository<JudgmentDay, JudgmentDay.Key> {

	List<JudgmentDay> findByJudgmentIdOrderByDay(long judgmentId);

}
