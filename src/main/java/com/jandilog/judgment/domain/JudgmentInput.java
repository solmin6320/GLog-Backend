package com.jandilog.judgment.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

// 한 회원의 한 주를 판정하는 데 필요한 입력. 호출한 쪽이 DB·캐시에서 모아 넘긴다.
// teamIdsAtWeekEnd: 대상 주 종료 시각(일 23:59) 소속 스냅샷 (Q-04)
// firstTeamJoinedAt: member.first_team_joined_at (계정 생애 첫 팀 참가 시각, 없으면 null)
// grass: 잔디 조회 결과. 제외·면제로 건너뛸 주는 조회하지 않으므로 null이어도 된다 (FC-02)
// recordPostCounts: 일자별 기록글 수. is_record이고 삭제되지 않은 글만, 날짜는 written_date (Q-11)
public record JudgmentInput(
		LocalDate weekStart,
		Set<Long> teamIdsAtWeekEnd,
		LocalDateTime firstTeamJoinedAt,
		boolean exemptionPeriod,
		boolean personalExemption,
		GrassLookup grass,
		Map<LocalDate, Integer> recordPostCounts) {

	public JudgmentInput {
		if (weekStart == null || !JudgmentWeek.isMonday(weekStart)) {
			throw new IllegalArgumentException("weekStart는 월요일이어야 해요");
		}
		teamIdsAtWeekEnd = teamIdsAtWeekEnd == null ? Set.of() : Set.copyOf(teamIdsAtWeekEnd);
		recordPostCounts = recordPostCounts == null ? Map.of() : Map.copyOf(recordPostCounts);
	}

}
