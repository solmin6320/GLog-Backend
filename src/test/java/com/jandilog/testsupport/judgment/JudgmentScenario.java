package com.jandilog.testsupport.judgment;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import com.jandilog.judgment.domain.GrassLookup;
import com.jandilog.judgment.domain.HoldReason;
import com.jandilog.judgment.domain.JudgmentInput;

// 판정 입력을 짧게 만드는 도우미. 기본값은 팀 없음·면제 없음·잔디 0칸·기록글 없음이다
public final class JudgmentScenario {

	// 기본 판정 대상 주(월요일)와 그 주를 판정하는 월요일 오전 7시 (KST)
	public static final LocalDate WEEK = LocalDate.of(2026, 9, 28);
	public static final LocalDateTime JUDGE_TIME = LocalDateTime.of(2026, 10, 5, 7, 0);

	private final LocalDate weekStart;
	private Set<Long> teams = Set.of();
	private LocalDateTime firstTeamJoinedAt;
	private boolean exemptionPeriod;
	private boolean personalExemption;
	private GrassLookup grass;
	private final Map<LocalDate, Boolean> grassByDay = new HashMap<>();
	private final Map<LocalDate, Integer> records = new HashMap<>();

	private JudgmentScenario(LocalDate weekStart) {
		this.weekStart = weekStart;
		for (int i = 0; i < 7; i++) {
			grassByDay.put(weekStart.plusDays(i), false);
		}
	}

	public static JudgmentScenario of(LocalDate weekStart) {
		return new JudgmentScenario(weekStart);
	}

	public static JudgmentScenario ofDefaultWeek() {
		return new JudgmentScenario(WEEK);
	}

	public JudgmentScenario teams(Set<Long> teamIds) {
		this.teams = Set.copyOf(teamIds);
		return this;
	}

	public JudgmentScenario firstTeamJoinedAt(LocalDateTime time) {
		this.firstTeamJoinedAt = time;
		return this;
	}

	public JudgmentScenario exemptionPeriod() {
		this.exemptionPeriod = true;
		return this;
	}

	public JudgmentScenario personalExemption() {
		this.personalExemption = true;
		return this;
	}

	// 0=월 ... 6=일. 지정한 날에 잔디 1칸 이상
	public JudgmentScenario grassOn(int... dayIndexes) {
		for (int index : dayIndexes) {
			grassByDay.put(weekStart.plusDays(index), true);
		}
		return this;
	}

	public JudgmentScenario grassFailed(HoldReason reason) {
		this.grass = GrassLookup.failed(reason);
		return this;
	}

	// 지정한 날에 기록글 1개씩
	public JudgmentScenario recordsOn(int... dayIndexes) {
		for (int index : dayIndexes) {
			records.merge(weekStart.plusDays(index), 1, Integer::sum);
		}
		return this;
	}

	public JudgmentScenario postsOn(int dayIndex, int count) {
		records.put(weekStart.plusDays(dayIndex), count);
		return this;
	}

	public LocalDate weekStart() {
		return weekStart;
	}

	public Set<Long> teams() {
		return teams;
	}

	public JudgmentInput build() {
		GrassLookup lookup = grass != null ? grass : GrassLookup.available(grassByDay);
		return new JudgmentInput(weekStart, teams, firstTeamJoinedAt, exemptionPeriod, personalExemption, lookup,
				records);
	}

}
