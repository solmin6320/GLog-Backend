package com.jandilog.judgment.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 회원 한 명의 잔디 조회 결과. from~to(KST 일자, 양끝 포함) 구간을 날짜 오름차순으로 빠짐없이 담는다.
// fetchedAt이 화면에 보여줄 "갱신 기준 시각"이다 (기능명세서 5장, 화면설계서 5-2-6)
public record GrassSnapshot(long memberId, LocalDate from, LocalDate to, LocalDateTime fetchedAt, List<GrassDay> days) {

	public GrassSnapshot {
		if (from == null || to == null || fetchedAt == null || days == null) {
			throw new IllegalArgumentException("잔디 조회 결과에 빈 값이 있어요");
		}
		if (to.isBefore(from)) {
			throw new IllegalArgumentException("to가 from보다 앞이에요");
		}
		days = List.copyOf(days);
		long expected = ChronoUnit.DAYS.between(from, to) + 1;
		if (days.size() != expected) {
			throw new IllegalArgumentException("잔디 일자가 구간을 빠짐없이 채우지 않았어요");
		}
		LocalDate cursor = from;
		for (GrassDay day : days) {
			if (!day.date().equals(cursor)) {
				throw new IllegalArgumentException("잔디 일자가 날짜 오름차순이 아니에요");
			}
			cursor = cursor.plusDays(1);
		}
	}

	// 구간 전체의 기여 수 합. 0인데 기록글만 있으면 비공개 기여 표시가 꺼진 회원일 수 있다 (E-39)
	public int totalContributions() {
		return days.stream().mapToInt(GrassDay::count).sum();
	}

	public boolean covers(LocalDate fromDay, LocalDate toDay) {
		return !fromDay.isBefore(from) && !toDay.isAfter(to);
	}

	// fromDay~toDay 구간의 일자별 잔디 유무. 구간을 덮지 못하면 없는 날을 0칸으로 오해하지 않게 거부한다
	public Map<LocalDate, Boolean> hasGrassBetween(LocalDate fromDay, LocalDate toDay) {
		if (!covers(fromDay, toDay)) {
			throw new IllegalArgumentException("잔디 조회 구간이 요청한 일자를 덮지 않아요");
		}
		Map<LocalDate, Boolean> result = new LinkedHashMap<>();
		for (GrassDay day : days) {
			if (!day.date().isBefore(fromDay) && !day.date().isAfter(toDay)) {
				result.put(day.date(), day.hasGrass());
			}
		}
		return result;
	}

}
