package com.jandilog.admin.graphql;

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;

import com.jandilog.admin.dto.AdminDeletedWarningPage;
import com.jandilog.admin.dto.AdminPenaltyFulfillment;
import com.jandilog.admin.dto.AdminPenaltyTargetPage;
import com.jandilog.admin.dto.AdminRestoredWarning;
import com.jandilog.admin.dto.AdminWarningRestorePreview;
import com.jandilog.admin.service.AdminPenaltyService;
import com.jandilog.admin.service.AdminWarningRestoreService;
import com.jandilog.common.security.AuthenticatedMember;

// 관리자 경고 복구 · 벌칙 탭 (AD-01 `/admin/warnings`): 벌칙 대상과 이행 체크, 삭제된 경고 목록과 복구 (기능명세서 6·9장)
@Controller
public class AdminWarningController {

	private final AdminPenaltyService penaltyService;
	private final AdminWarningRestoreService restoreService;

	public AdminWarningController(AdminPenaltyService penaltyService, AdminWarningRestoreService restoreService) {
		this.penaltyService = penaltyService;
		this.restoreService = restoreService;
	}

	@QueryMapping
	@PreAuthorize("hasRole('ADMIN')")
	public AdminPenaltyTargetPage adminPenaltyTargets(@Argument String after) {
		return penaltyService.list(after);
	}

	@QueryMapping
	@PreAuthorize("hasRole('ADMIN')")
	public AdminDeletedWarningPage adminDeletedWarnings(@Argument String after) {
		return restoreService.list(after);
	}

	@QueryMapping
	@PreAuthorize("hasRole('ADMIN')")
	public AdminWarningRestorePreview previewWarningRestore(@Argument String warningId) {
		return restoreService.preview(warningId);
	}

	@MutationMapping
	@PreAuthorize("hasRole('ADMIN')")
	public AdminPenaltyFulfillment fulfillPenalty(@AuthenticationPrincipal AuthenticatedMember admin,
			@Argument String memberId) {
		return penaltyService.fulfill(admin.id(), memberId);
	}

	@MutationMapping
	@PreAuthorize("hasRole('ADMIN')")
	public AdminRestoredWarning restoreWarning(@AuthenticationPrincipal AuthenticatedMember admin,
			@Argument String warningId) {
		return restoreService.restore(admin.id(), warningId);
	}

}
