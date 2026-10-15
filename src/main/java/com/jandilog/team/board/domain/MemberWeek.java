package com.jandilog.team.board.domain;

import java.time.LocalDateTime;
import java.util.List;

// 한 회원의 한 주 현황. verifiedDays는 잔디를 알 수 없으면 null (E-32).
// grassFetchedAt은 이 값에 쓴 잔디 캐시의 갱신 시각이고, 캐시를 쓰지 않았으면 null
public record MemberWeek(List<BoardDay> days, Integer verifiedDays, int recordCount, TeamBoardStatus status,
		LocalDateTime grassFetchedAt) {

	public MemberWeek {
		days = List.copyOf(days);
	}

	public MemberWeek withStatus(TeamBoardStatus newStatus) {
		return new MemberWeek(days, verifiedDays, recordCount, newStatus, grassFetchedAt);
	}

}
