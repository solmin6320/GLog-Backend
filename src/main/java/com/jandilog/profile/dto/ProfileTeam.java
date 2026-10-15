package com.jandilog.profile.dto;

// 프로필의 소속 팀 뱃지 (PR-01 ⑫). 공개 팀만 나오고 비공개 팀은 목록에서 아예 빠진다 (기능명세서 2·8장)
public record ProfileTeam(Long id, String name) {
}
