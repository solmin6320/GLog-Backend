package com.jandilog.judgment.graphql;

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;

import com.jandilog.common.security.AuthenticatedMember;
import com.jandilog.judgment.dto.JudgmentHistoryPage;
import com.jandilog.judgment.dto.WeeklyActivityResponse;
import com.jandilog.judgment.service.WeeklyActivityService;

// 내 주간 활동·지난 판정 (기능명세서 5장, 홈·AC-01). 본인 것만 조회한다
@Controller
public class WeeklyActivityController {

	private final WeeklyActivityService activityService;

	public WeeklyActivityController(WeeklyActivityService activityService) {
		this.activityService = activityService;
	}

	@QueryMapping
	@PreAuthorize("hasRole('MEMBER')")
	public WeeklyActivityResponse myWeeklyActivity(@AuthenticationPrincipal AuthenticatedMember member,
			@Argument String weekStart) {
		return activityService.weeklyActivity(member.id(), weekStart);
	}

	@QueryMapping
	@PreAuthorize("hasRole('MEMBER')")
	public JudgmentHistoryPage myJudgmentHistory(@AuthenticationPrincipal AuthenticatedMember member,
			@Argument String after) {
		return activityService.history(member.id(), after);
	}

}
