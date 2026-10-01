package com.jandilog.warning.graphql;

import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;

import com.jandilog.common.security.AuthenticatedMember;
import com.jandilog.warning.dto.WarningSummaryResponse;
import com.jandilog.warning.service.WarningRecalculationService;

// 내 경고 요약 (기능명세서 6장). 본인 것만 조회하고 저장된 카운트가 아니라 매번 처음부터 다시 계산한 값이다
@Controller
public class WarningSummaryController {

	private final WarningRecalculationService recalculationService;

	public WarningSummaryController(WarningRecalculationService recalculationService) {
		this.recalculationService = recalculationService;
	}

	@QueryMapping
	@PreAuthorize("hasRole('MEMBER')")
	public WarningSummaryResponse myWarningSummary(@AuthenticationPrincipal AuthenticatedMember member) {
		return WarningSummaryResponse.from(recalculationService.calculate(member.id()));
	}

}
