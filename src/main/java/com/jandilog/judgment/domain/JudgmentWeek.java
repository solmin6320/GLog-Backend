package com.jandilog.judgment.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;

// 주차는 그 주 월요일 LocalDate로 식별한다. 한 주는 월 00:00 ~ 일 23:59 KST (DB명세서 1-0)
public final class JudgmentWeek {

	private JudgmentWeek() {
	}

	public static LocalDate mondayOf(LocalDate date) {
		return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
	}

	public static boolean isMonday(LocalDate date) {
		return date.getDayOfWeek() == DayOfWeek.MONDAY;
	}

	// 주가 끝나는 순간(다음 주 월요일 00:00). 이 시각 직전이 일요일 23:59:59다
	public static LocalDateTime endExclusive(LocalDate weekStart) {
		return weekStart.plusDays(7).atStartOfDay();
	}

}
