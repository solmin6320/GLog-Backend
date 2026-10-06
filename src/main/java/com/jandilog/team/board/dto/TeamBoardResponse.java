package com.jandilog.team.board.dto;

import java.util.List;

// GraphQL TeamBoard 타입 (TM-05). grassFetchedAt은 팀원 잔디 캐시 중 가장 오래된 갱신 시각이다
public record TeamBoardResponse(
		String weekStart,
		String weekEnd,
		String grassFetchedAt,
		boolean grassNotLoaded,
		List<TeamBoardMemberResponse> members,
		List<TeamBoardPostResponse> recentPosts) {
}
