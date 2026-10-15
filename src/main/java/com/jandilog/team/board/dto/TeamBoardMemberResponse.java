package com.jandilog.team.board.dto;

import java.util.List;

import com.jandilog.team.board.domain.TeamBoardStatus;

// GraphQL TeamBoardMember 타입. 팀원 한 명의 이번 주 행
public record TeamBoardMemberResponse(
		Long memberId,
		String nickname,
		String githubLogin,
		boolean isMe,
		List<TeamBoardDayResponse> days,
		Integer verifiedDays,
		int recordCount,
		Integer warningCount,
		TeamBoardStatus status) {
}
