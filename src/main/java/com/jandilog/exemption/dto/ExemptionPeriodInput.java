package com.jandilog.exemption.dto;

// 면제 기간 등록·수정 입력. weekStart는 월요일 yyyy-MM-dd, reason은 1~200자
public record ExemptionPeriodInput(String weekStart, String reason) {
}
