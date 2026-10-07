package com.jandilog.warning.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.jandilog.admin.domain.AdminActionLog;
import com.jandilog.admin.domain.AdminActionType;
import com.jandilog.admin.repository.AdminActionLogRepository;
import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.warning.domain.PenaltyFulfillment;
import com.jandilog.warning.domain.WarningRecalcResult;
import com.jandilog.warning.repository.PenaltyFulfillmentRepository;

// 벌칙 이행 체크 (기능명세서 6장, DB명세서 1-11). 이행 시각이 경고 재계산의 고정점이 되어 그 이전 주는 세지 않으므로
// 경고 0·연속 통과 0이 된다 (Q-10). 되돌릴 수 없어 admin_action_log에 남긴다. 관리자 권한 검사는 호출하는 쪽 몫이다
@Service
public class PenaltyFulfillmentService {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final String TARGET_TYPE_MEMBER = "MEMBER";

	private final WarningRecalculationService recalculationService;
	private final PenaltyFulfillmentRepository fulfillmentRepository;
	private final AdminActionLogRepository actionLogRepository;
	private final Clock clock;

	public PenaltyFulfillmentService(WarningRecalculationService recalculationService,
			PenaltyFulfillmentRepository fulfillmentRepository, AdminActionLogRepository actionLogRepository,
			Clock clock) {
		this.recalculationService = recalculationService;
		this.fulfillmentRepository = fulfillmentRepository;
		this.actionLogRepository = actionLogRepository;
		this.clock = clock;
	}

	// 회원 행을 잠그고 지금 벌칙 대상인지 다시 계산해 확인한 뒤 기록한다.
	// 대상이 아니거나(이미 이행했거나 정정으로 빠짐) 보류 중이라 판단할 수 없으면 NOT_PENALTY_TARGET.
	// 두 관리자가 동시에 눌러도 락을 기다린 쪽은 먼저 커밋된 이행을 보고 거부된다
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public PenaltyFulfillment fulfill(long adminId, long memberId) {
		WarningRecalcResult state = recalculationService.recalculate(memberId);
		if (!(state instanceof WarningRecalcResult.Calculated calculated) || !calculated.penaltyTarget()) {
			throw new ApiException(ErrorCode.NOT_PENALTY_TARGET);
		}
		LocalDateTime now = LocalDateTime.now(clock.withZone(KST));
		PenaltyFulfillment fulfillment = fulfillmentRepository
				.save(PenaltyFulfillment.of(memberId, now, adminId, calculated.penaltyReachedWeek()));
		actionLogRepository.save(AdminActionLog.of(adminId, AdminActionType.FULFILL_PENALTY, TARGET_TYPE_MEMBER,
				Long.toString(memberId), null, now));
		return fulfillment;
	}

}
