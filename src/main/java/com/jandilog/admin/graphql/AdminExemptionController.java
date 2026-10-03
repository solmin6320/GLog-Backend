package com.jandilog.admin.graphql;

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;

import com.jandilog.common.security.AuthenticatedMember;
import com.jandilog.exemption.domain.PersonalExemptionStatus;
import com.jandilog.exemption.dto.ExemptionPeriodInput;
import com.jandilog.exemption.dto.ExemptionPeriodPage;
import com.jandilog.exemption.dto.ExemptionPeriodResponse;
import com.jandilog.exemption.dto.PersonalExemptionPage;
import com.jandilog.exemption.dto.PersonalExemptionResponse;
import com.jandilog.exemption.dto.RetroExemptionPreview;
import com.jandilog.exemption.service.ExemptionPeriodService;
import com.jandilog.exemption.service.PersonalExemptionService;

// 관리자 면제 관리 탭 (AD-01 exemptions): 면제 기간 등록·수정·삭제, 개인 면제 요청 승인·거절 (기능명세서 7·9장)
@Controller
public class AdminExemptionController {

	private final ExemptionPeriodService periodService;
	private final PersonalExemptionService personalService;

	public AdminExemptionController(ExemptionPeriodService periodService, PersonalExemptionService personalService) {
		this.periodService = periodService;
		this.personalService = personalService;
	}

	@QueryMapping
	@PreAuthorize("hasRole('ADMIN')")
	public ExemptionPeriodPage exemptionPeriods(@Argument String after) {
		return periodService.list(after);
	}

	@QueryMapping
	@PreAuthorize("hasRole('ADMIN')")
	public RetroExemptionPreview previewExemptionPeriod(@Argument String weekStart) {
		return periodService.preview(weekStart);
	}

	@QueryMapping
	@PreAuthorize("hasRole('ADMIN')")
	public PersonalExemptionPage personalExemptionRequests(@Argument PersonalExemptionStatus status,
			@Argument String after) {
		return personalService.listForAdmin(status, after);
	}

	@QueryMapping
	@PreAuthorize("hasRole('ADMIN')")
	public RetroExemptionPreview previewPersonalExemptionApproval(@Argument String id) {
		return personalService.previewApproval(id);
	}

	@MutationMapping
	@PreAuthorize("hasRole('ADMIN')")
	public ExemptionPeriodResponse createExemptionPeriod(@AuthenticationPrincipal AuthenticatedMember admin,
			@Argument ExemptionPeriodInput input) {
		return periodService.create(admin.id(), input);
	}

	@MutationMapping
	@PreAuthorize("hasRole('ADMIN')")
	public ExemptionPeriodResponse updateExemptionPeriod(@Argument String id, @Argument ExemptionPeriodInput input) {
		return periodService.update(id, input);
	}

	@MutationMapping
	@PreAuthorize("hasRole('ADMIN')")
	public boolean deleteExemptionPeriod(@Argument String id) {
		periodService.delete(id);
		return true;
	}

	@MutationMapping
	@PreAuthorize("hasRole('ADMIN')")
	public PersonalExemptionResponse approvePersonalExemption(@Argument String id) {
		return personalService.approve(id);
	}

	@MutationMapping
	@PreAuthorize("hasRole('ADMIN')")
	public PersonalExemptionResponse rejectPersonalExemption(@Argument String id, @Argument String reason) {
		return personalService.reject(id, reason);
	}

}
