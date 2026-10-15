package com.jandilog.exemption.graphql;

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;

import com.jandilog.common.security.AuthenticatedMember;
import com.jandilog.exemption.dto.PersonalExemptionPage;
import com.jandilog.exemption.dto.PersonalExemptionResponse;
import com.jandilog.exemption.service.PersonalExemptionService;

// 팀장의 개인 면제 요청 (TM-08). 팀장 자격은 서비스가 그 팀을 읽어 확인한다 (기능명세서 7장)
@Controller
public class PersonalExemptionController {

	private final PersonalExemptionService exemptionService;

	public PersonalExemptionController(PersonalExemptionService exemptionService) {
		this.exemptionService = exemptionService;
	}

	@MutationMapping
	@PreAuthorize("hasRole('MEMBER')")
	public PersonalExemptionResponse requestPersonalExemption(@AuthenticationPrincipal AuthenticatedMember member,
			@Argument String teamId, @Argument String memberId, @Argument String weekStart, @Argument String reason) {
		return exemptionService.request(member.id(), teamId, memberId, weekStart, reason);
	}

	@QueryMapping
	@PreAuthorize("hasRole('MEMBER')")
	public PersonalExemptionPage myPersonalExemptionRequests(@AuthenticationPrincipal AuthenticatedMember member,
			@Argument String after) {
		return exemptionService.listMine(member.id(), after);
	}

}
