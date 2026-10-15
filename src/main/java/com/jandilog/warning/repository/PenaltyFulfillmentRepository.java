package com.jandilog.warning.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.jandilog.warning.domain.PenaltyFulfillment;

public interface PenaltyFulfillmentRepository extends JpaRepository<PenaltyFulfillment, Long> {

	// 가장 최근 이행 체크. 경고 재계산의 기준선이다
	Optional<PenaltyFulfillment> findFirstByMemberIdOrderByFulfilledAtDesc(long memberId);

	// 여러 회원의 가장 최근 이행 시각. 이행한 적 없는 회원은 빠진다 (idx_pf_member)
	@Query("""
			select new com.jandilog.warning.repository.LatestFulfillment(f.memberId, max(f.fulfilledAt))
			from PenaltyFulfillment f
			where f.memberId in :memberIds
			group by f.memberId
			""")
	List<LatestFulfillment> findLatestByMemberIds(@Param("memberIds") Collection<Long> memberIds);

}
