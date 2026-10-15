package com.jandilog.team.dto;

// 팀 이름 1~20자, 소개 100자 이내(선택), 공개 여부는 생략하면 공개 (화면설계서 TM-02, 제안)
public record CreateTeamInput(String name, String description, Boolean isPublic) {
}
