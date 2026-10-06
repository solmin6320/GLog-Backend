package com.jandilog.profile.dto;

import java.util.List;

// 프로필 판정 이력 한 페이지. nextCursor가 null이면 마지막 (Q-06)
public record ProfileJudgmentPage(List<ProfileJudgmentWeek> items, String nextCursor) {
}
