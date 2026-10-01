package com.jandilog.judgment.domain;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

// 주간 판정 계산 (기능명세서 5장, DB명세서 1-6, FC-02). Spring·DB 의존 없이 입력과 Clock만 쓴다.
// 순서: 소속 0개(NO_TEAM) → 첫 참가 주(FIRST_WEEK) → 면제 기간 → 개인 면제 → 잔디 확보(실패면 HOLD) → 인증일·기록글 판정
public final class JudgmentCalculator {

	public static final int REQUIRED_VERIFIED_DAYS = 3;
	public static final int REQUIRED_RECORD_COUNT = 1;
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private final Clock clock;

	public JudgmentCalculator(Clock clock) {
		this.clock = clock;
	}

	public JudgmentResult judge(JudgmentInput input) {
		LocalDateTime now = LocalDateTime.now(clock.withZone(KST));
		LocalDate weekStart = input.weekStart();
		// 끝나지 않은 주를 판정하면 아직 채울 수 있는 주에 경고가 붙는다
		if (now.isBefore(JudgmentWeek.endExclusive(weekStart))) {
			throw new IllegalStateException("아직 끝나지 않은 주차는 판정할 수 없어요: " + weekStart);
		}

		SkipReason skip = skipReason(input);
		if (skip != null) {
			return JudgmentResult.skipped(skip, now);
		}

		GrassLookup grass = input.grass();
		if (grass == null) {
			throw new IllegalArgumentException("판정 대상 주인데 잔디 조회 결과가 없어요");
		}
		if (grass.isFailed()) {
			return JudgmentResult.hold(grass.failure());
		}

		List<DayResult> days = new ArrayList<>(7);
		int verifiedDays = 0;
		int recordCount = 0;
		for (int i = 0; i < 7; i++) {
			LocalDate day = weekStart.plusDays(i);
			Boolean hasGrass = grass.hasGrassByDay().get(day);
			// 없는 날을 잔디 0칸으로 보면 조회가 덜 된 주가 미달 처리된다
			if (hasGrass == null) {
				throw new IllegalArgumentException("잔디 조회 결과에 " + day + "이 없어요");
			}
			int posts = Math.max(0, input.recordPostCounts().getOrDefault(day, 0));
			DayResult result = new DayResult(day, hasGrass, posts > 0);
			days.add(result);
			recordCount += posts;
			if (result.verified()) {
				verifiedDays++;
			}
		}
		boolean pass = verifiedDays >= REQUIRED_VERIFIED_DAYS && recordCount >= REQUIRED_RECORD_COUNT;
		return JudgmentResult.judged(pass, verifiedDays, recordCount, days, now);
	}

	// 판정 제외·면제 사유. 해당 없으면 null.
	// 면제 기간과 개인 면제가 겹치면 둘 다 보존되고 판정은 제외된다(E-44). 기록할 사유는 명세에 우선순위가 없어 면제 기간을 앞에 둔다
	private static SkipReason skipReason(JudgmentInput input) {
		if (input.teamIdsAtWeekEnd().isEmpty()) {
			return SkipReason.NO_TEAM;
		}
		if (isFirstTeamWeek(input.firstTeamJoinedAt(), input.weekStart())) {
			return SkipReason.FIRST_WEEK;
		}
		if (input.exemptionPeriod()) {
			return SkipReason.EXEMPTION_PERIOD;
		}
		if (input.personalExemption()) {
			return SkipReason.PERSONAL_EXEMPTION;
		}
		return null;
	}

	// 계정 생애 첫 팀 참가가 속한 주 (E-36)
	private static boolean isFirstTeamWeek(LocalDateTime firstTeamJoinedAt, LocalDate weekStart) {
		return firstTeamJoinedAt != null && JudgmentWeek.mondayOf(firstTeamJoinedAt.toLocalDate()).equals(weekStart);
	}

}
