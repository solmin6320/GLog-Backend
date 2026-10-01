package com.jandilog.team.domain;

// team_member.leave_type ENUM과 1:1. 추방·팀 삭제는 그 팀 경고 카테고리를 빼고, 자진 탈퇴는 경고를 유지한다 (기능명세서 6장)
public enum TeamLeaveType {
	SELF,
	KICKED,
	TEAM_DELETED
}
