package com.jandilog.exemption.dto;

import java.util.List;

// 면제 기간 등록·수정, 개인 면제 승인의 재계산 미리보기. 계산만 하고 아무것도 바꾸지 않는다 (기능명세서 7장).
// retroactive: 그 주가 이미 판정돼 바뀌는 판정이 있다. false면 items는 비어 있고 판정 때 면제로 처리된다
public record RetroExemptionPreview(String weekStart, boolean retroactive, List<RetroExemptionImpact> items) {
}
