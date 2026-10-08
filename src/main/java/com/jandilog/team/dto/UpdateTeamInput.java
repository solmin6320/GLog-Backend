package com.jandilog.team.dto;

// 팀장이 바꿀 수 있는 것은 공개 설정뿐이다 (기능명세서 2장 공개 설정, 화면설계서 TM-06 ⑦)
public record UpdateTeamInput(boolean isPublic) {
}
