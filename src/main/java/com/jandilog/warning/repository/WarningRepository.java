package com.jandilog.warning.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.jandilog.warning.domain.Warning;

public interface WarningRepository extends JpaRepository<Warning, Long> {

	Optional<Warning> findByMemberIdAndWeekStart(long memberId, LocalDate weekStart);

	// 삭제 표시된 것까지 전부, 오래된 주부터
	List<Warning> findByMemberIdOrderByWeekStartAsc(long memberId);

	// 여러 회원의 경고를 삭제 표시된 것까지 전부. 경고 재계산이 회원마다 따로 읽지 않게 한 번에 읽는다 (uk_warning)
	List<Warning> findByMemberIdIn(Collection<Long> memberIds);

	// 지금 살아 있는 경고만 (deleted_at IS NULL). 프로필·판정에는 이것만 보인다 (idx_warning_alive)
	List<Warning> findByMemberIdAndDeletedAtIsNullOrderByWeekStartAsc(long memberId);

	// 여러 회원의 지금 살아 있는 경고 (idx_warning_alive)
	List<Warning> findByMemberIdInAndDeletedAtIsNull(Collection<Long> memberIds);

	// 살아 있는 경고가 min개 이상인 회원 id. 벌칙 대상 후보를 좁히는 데 쓰고, 실제 대상 여부는 재계산으로 정한다
	@Query("select w.memberId from Warning w where w.deletedAt is null group by w.memberId having count(w) >= :min")
	List<Long> findMemberIdsWithAliveWarningsAtLeast(@Param("min") long min);

}
