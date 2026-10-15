package com.jandilog.exemption.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.jandilog.exemption.domain.PersonalExemption;
import com.jandilog.exemption.domain.PersonalExemptionStatus;

public interface PersonalExemptionRepository extends JpaRepository<PersonalExemption, Long> {

	// 중복 요청(대기·승인) 검사 (E-47)
	boolean existsByMemberIdAndWeekStartAndStatusIn(long memberId, LocalDate weekStart,
			Collection<PersonalExemptionStatus> statuses);

	// 판정이 그 회원·주를 개인 면제로 볼지 정하는 조회는 APPROVED로 부른다 (기능명세서 5·7장)
	boolean existsByMemberIdAndWeekStartAndStatus(long memberId, LocalDate weekStart, PersonalExemptionStatus status);

	// 회원 락을 잡기 전에 대상 회원만 먼저 알아낼 때 쓴다(엔티티를 올리지 않는다)
	@Query("select p.memberId from PersonalExemption p where p.id = :id")
	Optional<Long> findMemberIdById(@Param("id") long id);

	long countByStatus(PersonalExemptionStatus status);

	// 관리자 목록: 상태별 최신순(id 내림차순) 커서
	List<PersonalExemption> findByStatusAndIdLessThanOrderByIdDesc(PersonalExemptionStatus status, long before,
			Pageable pageable);

	// 팀장 본인이 보낸 요청 목록: 최신순 커서
	List<PersonalExemption> findByRequestedByAndIdLessThanOrderByIdDesc(long requestedBy, long before,
			Pageable pageable);

}
