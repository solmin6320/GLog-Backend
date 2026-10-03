package com.jandilog.admin.graphql;

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

import com.jandilog.admin.dto.AdminDashboardSummary;
import com.jandilog.admin.dto.AdminHoldPage;
import com.jandilog.admin.dto.AdminHoldRerunSummary;
import com.jandilog.admin.dto.AdminHoldRetryResult;
import com.jandilog.admin.dto.AdminJudgmentFilter;
import com.jandilog.admin.dto.AdminJudgmentPage;
import com.jandilog.admin.service.AdminDashboardService;
import com.jandilog.admin.service.AdminHoldService;

// 관리자 대시보드 (AD-01 `/admin`): 처리 대기 요약, 판정 결과, 보류 목록과 재시도. 정정 패널은 AdminCorrectionController (기능명세서 9장)
@Controller
public class AdminDashboardController {

	private final AdminDashboardService dashboardService;
	private final AdminHoldService holdService;

	public AdminDashboardController(AdminDashboardService dashboardService, AdminHoldService holdService) {
		this.dashboardService = dashboardService;
		this.holdService = holdService;
	}

	@QueryMapping
	@PreAuthorize("hasRole('ADMIN')")
	public AdminDashboardSummary adminDashboardSummary() {
		return dashboardService.summary();
	}

	@QueryMapping
	@PreAuthorize("hasRole('ADMIN')")
	public AdminJudgmentPage adminJudgments(@Argument String weekStart, @Argument String keyword,
			@Argument AdminJudgmentFilter filter, @Argument String after) {
		return dashboardService.judgments(weekStart, keyword, filter, after);
	}

	@QueryMapping
	@PreAuthorize("hasRole('ADMIN')")
	public AdminHoldPage adminJudgmentHolds(@Argument String after) {
		return dashboardService.holds(after);
	}

	@MutationMapping
	@PreAuthorize("hasRole('ADMIN')")
	public AdminHoldRetryResult retryJudgmentHold(@Argument String memberId, @Argument String weekStart) {
		return holdService.retryOne(memberId, weekStart);
	}

	@MutationMapping
	@PreAuthorize("hasRole('ADMIN')")
	public AdminHoldRerunSummary retryAllJudgmentHolds() {
		return holdService.retryAll();
	}

}
