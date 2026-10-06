package com.jandilog.post.graphql;

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;

import com.jandilog.common.security.AuthenticatedMember;
import com.jandilog.post.dto.CommentResponse;
import com.jandilog.post.dto.CursorPage;
import com.jandilog.post.dto.PostResponse;
import com.jandilog.post.service.TeamPostManageService;
import com.jandilog.team.service.TeamAccessService;

// 팀 글 관리 목록 (TM-06 ⑧). 팀장 전용 검사는 서비스가 팀을 읽어서 하고, 여기서는 ACTIVE 회원만 통과시킨다
@Controller
public class TeamPostManageController {

	private final TeamPostManageService manageService;

	public TeamPostManageController(TeamPostManageService manageService) {
		this.manageService = manageService;
	}

	@QueryMapping
	@PreAuthorize("hasRole('MEMBER')")
	public CursorPage<PostResponse> teamPosts(@AuthenticationPrincipal AuthenticatedMember member,
			@Argument String teamId, @Argument String after) {
		return manageService.posts(member.id(), TeamAccessService.parseTeamId(teamId), after);
	}

	@QueryMapping
	@PreAuthorize("hasRole('MEMBER')")
	public CursorPage<CommentResponse> teamComments(@AuthenticationPrincipal AuthenticatedMember member,
			@Argument String teamId, @Argument String after) {
		return manageService.comments(member.id(), TeamAccessService.parseTeamId(teamId), after);
	}

}
