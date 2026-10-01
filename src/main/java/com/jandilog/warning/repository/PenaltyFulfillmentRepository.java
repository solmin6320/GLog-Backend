package com.jandilog.warning.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.jandilog.warning.domain.PenaltyFulfillment;

public interface PenaltyFulfillmentRepository extends JpaRepository<PenaltyFulfillment, Long> {

	// 가장 최근 이행 체크. 경고 재계산의 기준선이다
	Optional<PenaltyFulfillment> findFirstByMemberIdOrderByFulfilledAtDesc(long memberId);

}
