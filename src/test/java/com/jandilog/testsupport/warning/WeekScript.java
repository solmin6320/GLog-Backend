package com.jandilog.testsupport.warning;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import com.jandilog.judgment.domain.JudgmentStatus;
import com.jandilog.judgment.domain.JudgmentWeek;
import com.jandilog.warning.domain.WeekEntry;

// 경고 재계산 테스트용 주차 표기. 글자 하나가 한 주이고 0번째 글자가 FIRST 주(월요일)다
//  P 통과 / F 미달(경고 살아 있음) / D 미달(경고 삭제 표시) / E 면제 / X 제외 / H 보류 / . 판정 행 없음
public final class WeekScript {

	public static final LocalDate FIRST = LocalDate.of(2026, 1, 5);

	private WeekScript() {
	}

	public static LocalDate week(int index) {
		return FIRST.plusWeeks(index);
	}

	// index번째 주가 끝나는 순간(다음 월요일 00:00)
	public static LocalDateTime endOf(int index) {
		return JudgmentWeek.endExclusive(week(index));
	}

	public static List<LocalDate> weeks(int... indexes) {
		List<LocalDate> weeks = new ArrayList<>();
		for (int index : indexes) {
			weeks.add(week(index));
		}
		return weeks;
	}

	public static List<WeekEntry> parse(String script) {
		List<WeekEntry> entries = new ArrayList<>();
		for (int i = 0; i < script.length(); i++) {
			char symbol = script.charAt(i);
			if (symbol != '.') {
				entries.add(entry(i, symbol));
			}
		}
		return entries;
	}

	public static WeekEntry entry(int index, char symbol) {
		return switch (symbol) {
			case 'P' -> new WeekEntry(week(index), JudgmentStatus.PASS, false);
			case 'F' -> new WeekEntry(week(index), JudgmentStatus.FAIL, false);
			case 'D' -> new WeekEntry(week(index), JudgmentStatus.FAIL, true);
			case 'E' -> new WeekEntry(week(index), JudgmentStatus.EXEMPT, false);
			case 'X' -> new WeekEntry(week(index), JudgmentStatus.EXCLUDED, false);
			case 'H' -> new WeekEntry(week(index), JudgmentStatus.HOLD, false);
			default -> throw new IllegalArgumentException("모르는 표기: " + symbol);
		};
	}

	// 표기의 index번째 글자만 바꾼 새 표기
	public static String with(String script, int index, char symbol) {
		StringBuilder builder = new StringBuilder(script);
		builder.setCharAt(index, symbol);
		return builder.toString();
	}

	// 실패 메시지용: 주차 목록을 표기로 되돌린다. 빈 주는 '.'
	public static String render(Collection<WeekEntry> entries) {
		if (entries.isEmpty()) {
			return "";
		}
		int last = entries.stream().max(Comparator.comparing(WeekEntry::weekStart)).map(e -> indexOf(e.weekStart()))
				.orElse(0);
		char[] chars = new char[last + 1];
		java.util.Arrays.fill(chars, '.');
		for (WeekEntry entry : entries) {
			chars[indexOf(entry.weekStart())] = symbolOf(entry);
		}
		return new String(chars);
	}

	public static int indexOf(LocalDate weekStart) {
		return (int) java.time.temporal.ChronoUnit.WEEKS.between(FIRST, weekStart);
	}

	public static char symbolOf(WeekEntry entry) {
		return switch (entry.status()) {
			case PASS -> 'P';
			case FAIL -> entry.warningDeleted() ? 'D' : 'F';
			case EXEMPT -> 'E';
			case EXCLUDED -> 'X';
			case HOLD -> 'H';
		};
	}

}
