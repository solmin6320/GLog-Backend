package com.jandilog.exemption.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.jandilog.exemption.domain.ExemptionPeriod;

public interface ExemptionPeriodRepository extends JpaRepository<ExemptionPeriod, Long> {

	Optional<ExemptionPeriod> findByWeekStart(LocalDate weekStart);

	// 판정이 그 주를 면제 기간으로 볼지 정하는 조회 (기능명세서 5·7장)
	boolean existsByWeekStart(LocalDate weekStart);

	List<ExemptionPeriod> findByWeekStartIn(Collection<LocalDate> weekStarts);

	// 주차 최신순 첫 페이지
	List<ExemptionPeriod> findAllByOrderByWeekStartDesc(Pageable pageable);

	// 커서(마지막 주차)보다 오래된 주차
	List<ExemptionPeriod> findByWeekStartLessThanOrderByWeekStartDesc(LocalDate before, Pageable pageable);

}
