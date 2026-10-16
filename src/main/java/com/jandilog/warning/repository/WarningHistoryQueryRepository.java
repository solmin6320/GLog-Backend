package com.jandilog.warning.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.jandilog.judgment.domain.JudgmentStatus;
import com.jandilog.warning.domain.Warning;
import com.jandilog.warning.dto.WarningHistoryRow;

// 내 경고 이력(AC-02 ③)이 경고를 읽는 조회. WarningRepository는 건드리지 않으려고 따로 둔다
public interface WarningHistoryQueryRepository extends Repository<Warning, Long> {

	// 살아 있고 그 주 판정이 아직 미달인 경고만, 최근 주부터 (before보다 이전 주).
	// 삭제 표시된 경고와 정정·소급 면제로 미달이 아니게 된 주의 경고는 이력에 나오지 않는다
	@Query("""
			select new com.jandilog.warning.dto.WarningHistoryRow(w.id, w.weekStart, j.verifiedDays, j.recordCount)
			from Warning w, WeeklyJudgment j
			where w.memberId = :memberId and w.deletedAt is null and w.weekStart < :before
			  and j.memberId = w.memberId and j.weekStart = w.weekStart and j.status = :fail
			order by w.weekStart desc
			""")
	List<WarningHistoryRow> findAlivePage(@Param("memberId") long memberId, @Param("fail") JudgmentStatus fail,
			@Param("before") LocalDate before, Pageable pageable);

}
