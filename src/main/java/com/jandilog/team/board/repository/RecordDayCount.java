package com.jandilog.team.board.repository;

import java.time.LocalDate;

// 회원의 하루치 기록글 수 (post_index)
public record RecordDayCount(long memberId, LocalDate day, long count) {
}
