package com.jandilog.warning.graphql;

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;

import com.jandilog.common.security.AuthenticatedMember;
import com.jandilog.warning.dto.WarningHistoryPage;
import com.jandilog.warning.service.WarningHistoryService;

// 내 경고 이력 (AC-02 ③). 본인 것만 조회한다
@Controller
public class WarningHistoryController {

	private final WarningHistoryService historyService;

	public WarningHistoryController(WarningHistoryService historyService) {
		this.historyService = historyService;
	}

	@QueryMapping
	@PreAuthorize("hasRole('MEMBER')")
	public WarningHistoryPage myWarningHistory(@AuthenticationPrincipal AuthenticatedMember member,
			@Argument String after) {
		return historyService.history(member.id(), after);
	}

}
