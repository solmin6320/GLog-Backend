package com.jandilog.team.dto;

// 보낸 항목만 바꾼다. 소개를 비우려면 빈 문자열을 보낸다
public record UpdateTeamInput(String name, String description, Boolean isPublic) {
}
