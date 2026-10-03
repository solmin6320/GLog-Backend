package com.jandilog.exemption.dto;

import java.util.List;

// 커서 기반 한 페이지(20개). nextCursor가 없으면 마지막 (Q-06)
public record PersonalExemptionPage(List<PersonalExemptionResponse> items, String nextCursor) {
}
