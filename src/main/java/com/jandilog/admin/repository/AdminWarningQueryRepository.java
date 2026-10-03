package com.jandilog.admin.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.jandilog.judgment.domain.JudgmentStatus;
import com.jandilog.warning.domain.Warning;

// 관리자 경고 복구 탭이 경고를 읽는 조회 (AD-01 ⑦⑧). WarningRepository는 건드리지 않으려고 따로 둔다
public interface AdminWarningQueryRepository extends Repository<Warning, Long> {

	// 회원 락을 잡기 전에 대상 회원만 먼저 알아낸다
	@Query("select w.memberId from Warning w where w.id = :id")
	Optional<Long> findMemberIdById(@Param("id") long id);

	// 삭제 표시된 경고 중 그 주 판정이 아직 미달인 것만, 삭제가 최근인 순(id 내림차순 키셋).
	// 정정 · 소급 면제로 미달이 아니게 된 주의 경고는 복구가 아니라 그 정정을 되돌려야 살아나므로 뺀다
	@Query("""
			select w from Warning w, WeeklyJudgment j
			where w.deletedAt is not null and w.id < :before
			  and j.memberId = w.memberId and j.weekStart = w.weekStart and j.status = :fail
			order by w.id desc
			""")
	List<Warning> findRestorablePage(@Param("fail") JudgmentStatus fail, @Param("before") long before,
			Pageable pageable);

	// 복구할 수 있는 경고인가: 삭제 표시가 있고 그 주 판정이 미달
	@Query("""
			select count(w) from Warning w, WeeklyJudgment j
			where w.id = :id and w.deletedAt is not null
			  and j.memberId = w.memberId and j.weekStart = w.weekStart and j.status = :fail
			""")
	long countRestorable(@Param("id") long id, @Param("fail") JudgmentStatus fail);

}
