package com.jandilog.warning.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.jandilog.judgment.domain.JudgmentWeek;

// 경고 재계산 (기능명세서 6장, DB명세서 4-3, FC-04). Spring·DB 의존 없는 순수 계산이다.
// 저장된 카운트를 더하고 빼지 않고 매번 처음부터 다시 훑는다. 정정·소급 면제·복구가 어떤 순서로 들어와도 결과가 같다.
// 미리보기(무변경)와 반영이 같은 함수를 쓴다: 호출한 쪽이 가정한 주차 목록을 넘기면 미리보기가 된다.
//
// 훑는 규칙 (week_start 오름차순, 이행 기준선 이후 주만)
//  - EXEMPT·EXCLUDED: 연속을 끊지도 늘리지도 않고 건너뜀
//  - HOLD: 보류가 남아 있으면 계산하지 않고 OnHold를 돌려준다 (Q-03)
//  - FAIL: 연속 0. 경고 레코드가 살아 있으면 경고로 센다. 벌칙 대상(3개)에서도 계속 쌓여 3을 넘을 수 있다 (Q-10 ⓒ)
//  - PASS: 벌칙 대상이면 차감 정지·연속 0 유지 (Q-10 ⓑ). 아니면 연속 +1, 2가 되면 가장 오래된 경고 1개 차감 후
//    연속 0. 경고가 0개여도 2가 되면 연속을 0으로 되돌린다 (Q-10 ⓐ)
// 이행 기준선 이전 주는 경고도 통과도 세지 않으므로 이행 직후 연속은 0이다 (Q-10 ⓓ, E-58)
public final class WarningRecalculator {

	public static final int PENALTY_THRESHOLD = 3;
	public static final int STREAK_FOR_DEDUCTION = 2;

	private WarningRecalculator() {
	}

	// fulfilledAt: 가장 최근 penalty_fulfillment.fulfilled_at, 없으면 null
	public static WarningRecalcResult recalculate(Collection<WeekEntry> weeks, LocalDateTime fulfilledAt) {
		List<WeekEntry> ordered = new ArrayList<>(weeks);
		ordered.sort(Comparator.comparing(WeekEntry::weekStart));
		Set<LocalDate> seen = new HashSet<>();
		for (WeekEntry week : ordered) {
			if (!JudgmentWeek.isMonday(week.weekStart())) {
				throw new IllegalArgumentException("weekStart는 월요일이어야 해요: " + week.weekStart());
			}
			if (!seen.add(week.weekStart())) {
				throw new IllegalArgumentException("같은 주차가 두 번 들어왔어요: " + week.weekStart());
			}
		}

		List<LocalDate> beforeBaseline = new ArrayList<>();
		List<LocalDate> holdWeeks = new ArrayList<>();
		// 아직 차감되지 않은 살아 있는 경고. 앞이 가장 오래된 경고다
		List<LocalDate> active = new ArrayList<>();
		List<LocalDate> deducted = new ArrayList<>();
		int streak = 0;

		for (WeekEntry week : ordered) {
			if (isBeforeBaseline(week.weekStart(), fulfilledAt)) {
				if (week.countsAsWarning()) {
					beforeBaseline.add(week.weekStart());
				}
				continue;
			}
			switch (week.status()) {
				case EXEMPT, EXCLUDED -> {
					// 건너뜀
				}
				case HOLD -> holdWeeks.add(week.weekStart());
				case FAIL -> {
					streak = 0;
					// 삭제 표시된 경고는 세지 않는다. 그 주의 미달 자체는 연속을 끊는다
					if (week.countsAsWarning()) {
						active.add(week.weekStart());
					}
				}
				case PASS -> {
					if (active.size() >= PENALTY_THRESHOLD) {
						streak = 0;
					}
					else {
						streak++;
						if (streak == STREAK_FOR_DEDUCTION) {
							if (!active.isEmpty()) {
								deducted.add(active.remove(0));
							}
							streak = 0;
						}
					}
				}
			}
		}

		if (!holdWeeks.isEmpty()) {
			return new WarningRecalcResult.OnHold(holdWeeks);
		}
		return new WarningRecalcResult.Calculated(active.size(), streak, active.size() >= PENALTY_THRESHOLD, active,
				deducted, beforeBaseline);
	}

	// 주가 끝난 시각(다음 월요일 00:00)이 이행 시각 이하면 이행 전에 이미 지나간 주다.
	// 이행한 주는 아직 판정 전이므로 이후로 센다
	static boolean isBeforeBaseline(LocalDate weekStart, LocalDateTime fulfilledAt) {
		return fulfilledAt != null && !JudgmentWeek.endExclusive(weekStart).isAfter(fulfilledAt);
	}

}
