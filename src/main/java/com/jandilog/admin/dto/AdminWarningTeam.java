package com.jandilog.admin.dto;

// 경고에 붙은 팀. 경고 복구 · 벌칙 화면에서만 비공개 팀도 실명으로 보여준다 (기능명세서 9장)
public record AdminWarningTeam(Long id, String name) {
}
