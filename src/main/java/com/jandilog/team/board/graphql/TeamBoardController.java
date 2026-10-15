package com.jandilog.team.board.graphql;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;

import com.jandilog.common.security.AuthenticatedMember;
import com.jandilog.team.board.dto.TeamBoardResponse;
import com.jandilog.team.board.dto.TeamWeekSummaryResponse;
import com.jandilog.team.board.service.TeamBoardService;
import com.jandilog.team.dto.TeamResponse;
import com.jandilog.team.service.TeamAccessService;

// 팀 현황판(TM-05)과 내 팀 목록의 이번 주 요약(TM-01 ⑧). 현황판 열람 권한 검사는 서비스가 팀을 읽어서 한다(관리자도 예외 없음)
@Controller
public class TeamBoardController {

	private final TeamBoardService boardService;

	public TeamBoardController(TeamBoardService boardService) {
		this.boardService = boardService;
	}

	@QueryMapping
	@PreAuthorize("hasRole('MEMBER')")
	public TeamBoardResponse teamBoard(@AuthenticationPrincipal AuthenticatedMember member,
			@Argument String teamId) {
		return boardService.teamBoard(member.id(), TeamAccessService.parseTeamId(teamId));
	}

	// Team 객체는 팀원 본인에게만 내려가는 쿼리·뮤테이션에서 나오므로, 목록 전체를 쿼리 몇 번으로 센다
	@BatchMapping(typeName = "Team", field = "weekSummary")
	public Map<TeamResponse, TeamWeekSummaryResponse> weekSummary(List<TeamResponse> teams) {
		Map<Long, TeamWeekSummaryResponse> summaries = boardService
				.weekSummaries(teams.stream().map(TeamResponse::id).distinct().toList());
		Map<TeamResponse, TeamWeekSummaryResponse> result = new HashMap<>();
		for (TeamResponse team : teams) {
			result.put(team, summaries.get(team.id()));
		}
		return result;
	}

}
