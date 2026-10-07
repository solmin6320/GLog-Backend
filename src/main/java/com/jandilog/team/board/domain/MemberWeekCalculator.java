package com.jandilog.team.board.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.jandilog.judgment.domain.JudgmentCalculator;
import com.jandilog.judgment.domain.JudgmentDay;
import com.jandilog.judgment.domain.JudgmentStatus;
import com.jandilog.judgment.domain.WeeklyJudgment;
import com.jandilog.judgment.dto.GrassDay;
import com.jandilog.judgment.dto.GrassSnapshot;

// 팀 현황판의 회원 한 주 계산 (기능명세서 2장·5장, 화면설계서 TM-05 ⑥). Spring·DB 의존 없는 순수 계산이다.
// 인증일 규칙은 판정과 같다: 잔디 1칸 이상 또는 기록글이 있는 날, 같은 날 둘 다여도 1일 (통과 3일·기록글 1개)
public final class MemberWeekCalculator {

	private MemberWeekCalculator() {
	}

	// 진행 중인 주. 잔디 캐시(없으면 null)와 일자별 기록글 수로 계산하고, 미달은 확정 뒤에만 쓰므로 NOT_YET까지만 낸다.
	// 캐시가 없으면 잔디를 모르는 날은 기록글만 센다: 인증일 수는 알 수 없어 null이고, 기록글만으로 이미 채웠을 때만 PASS다
	public static MemberWeek live(LocalDate weekStart, GrassSnapshot snapshot, Map<LocalDate, Integer> recordsByDay) {
		// 캐시가 이 주를 덮지 못하면(지난 주 월요일 이후 구간만 갖는다) 없는 날을 0칸으로 오해하지 않게 없는 것으로 본다
		GrassSnapshot usable = snapshot != null && !snapshot.from().isAfter(weekStart) ? snapshot : null;
		Map<LocalDate, GrassDay> grassByDay = new HashMap<>();
		if (usable != null) {
			usable.days().forEach(day -> grassByDay.put(day.date(), day));
		}

		List<BoardDay> days = new ArrayList<>(7);
		int recordCount = 0;
		int verified = 0;
		for (int i = 0; i < 7; i++) {
			LocalDate date = weekStart.plusDays(i);
			GrassDay grass = grassByDay.get(date);
			int posted = Math.max(0, recordsByDay.getOrDefault(date, 0));
			recordCount += posted;
			BoardDay day = new BoardDay(date, grass == null ? null : grass.hasGrass(), posted > 0);
			days.add(day);
			if (Boolean.TRUE.equals(day.verified())) {
				verified++;
			}
		}
		boolean pass = verified >= JudgmentCalculator.REQUIRED_VERIFIED_DAYS
				&& recordCount >= JudgmentCalculator.REQUIRED_RECORD_COUNT;
		return new MemberWeek(days, usable == null ? null : verified, recordCount,
				pass ? TeamBoardStatus.PASS : TeamBoardStatus.NOT_YET, usable == null ? null : usable.fetchedAt());
	}

	// 확정된 통과·미달 주. 판정 시점에 저장한 7일치 근거와 인증일·기록글 수를 그대로 쓴다.
	// 근거가 7일이 아니거나 통과·미달이 아니면 null
	public static MemberWeek confirmed(WeeklyJudgment judgment, List<JudgmentDay> storedDays) {
		if ((judgment.getStatus() != JudgmentStatus.PASS && judgment.getStatus() != JudgmentStatus.FAIL)
				|| storedDays.size() != 7) {
			return null;
		}
		List<BoardDay> days = storedDays.stream()
				.sorted((a, b) -> a.getDay().compareTo(b.getDay()))
				.map(d -> new BoardDay(d.getDay(), d.isHasGrass(), d.isHasRecord()))
				.toList();
		int recordCount = judgment.getRecordCount() == null ? 0 : judgment.getRecordCount();
		return new MemberWeek(days, judgment.getVerifiedDays(), recordCount, statusOf(judgment.getStatus()), null);
	}

	// 확정된 판정 결과의 현황판 표기. 면제·제외는 모두 "제외"이고, 확정이 아닌 보류는 표기할 결과가 없어 null
	public static TeamBoardStatus statusOf(JudgmentStatus judged) {
		return switch (judged) {
			case PASS -> TeamBoardStatus.PASS;
			case FAIL -> TeamBoardStatus.FAIL;
			case EXEMPT, EXCLUDED -> TeamBoardStatus.EXCLUDED;
			case HOLD -> null;
		};
	}

}
