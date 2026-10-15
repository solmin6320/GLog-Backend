package com.jandilog.team.repository;

// 팀별 현재 팀원 수 묶음 조회 결과
public record TeamMemberCount(long teamId, long count) {
}
