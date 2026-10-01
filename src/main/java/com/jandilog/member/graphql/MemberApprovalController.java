package com.jandilog.member.graphql;

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;

import com.jandilog.common.security.AuthenticatedMember;
import com.jandilog.member.dto.AdminMemberPage;
import com.jandilog.member.dto.AdminMemberResponse;
import com.jandilog.member.dto.ApprovalStatusFilter;
import com.jandilog.member.service.MemberApprovalService;

// 가입 승인 탭(관리자 전용)
@Controller
public class MemberApprovalController {

	private final MemberApprovalService approvalService;

	public MemberApprovalController(MemberApprovalService approvalService) {
		this.approvalService = approvalService;
	}

	@QueryMapping
	@PreAuthorize("hasRole('ADMIN')")
	public AdminMemberPage adminMembers(@Argument ApprovalStatusFilter status, @Argument String keyword,
			@Argument String after) {
		return approvalService.list(status, keyword, after);
	}

	@MutationMapping
	@PreAuthorize("hasRole('ADMIN')")
	public AdminMemberResponse approveMember(@Argument String memberId) {
		return approvalService.approve(memberId);
	}

	@MutationMapping
	@PreAuthorize("hasRole('ADMIN')")
	public AdminMemberResponse rejectMember(@AuthenticationPrincipal AuthenticatedMember admin,
			@Argument String memberId) {
		return approvalService.reject(admin.id(), memberId);
	}

	@MutationMapping
	@PreAuthorize("hasRole('ADMIN')")
	public AdminMemberResponse revertMemberToPending(@Argument String memberId) {
		return approvalService.revertToPending(memberId);
	}

}
