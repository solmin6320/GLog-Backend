package com.jandilog.team.board.repository;

import java.time.LocalDate;

// 어느 회원이 어느 주 미달로 받은 경고인지
public record WarningWeek(long memberId, LocalDate weekStart) {
}
