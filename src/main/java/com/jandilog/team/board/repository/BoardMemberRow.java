package com.jandilog.team.board.repository;

import java.time.LocalDateTime;

// 팀의 현재 팀원 한 명. 현황판 행과 판정 제외 계산에 필요한 값만 담는다
public record BoardMemberRow(long teamId, long memberId, String nickname, String githubLogin,
		LocalDateTime firstTeamJoinedAt, LocalDateTime joinedAt) {
}
