package com.jandilog.judgment.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

// 잔디 조회 결과 값 객체: 구간을 빠짐없이 오름차순으로 채워야 하고, 못 덮는 구간은 0칸으로 오해하지 않게 거부한다
class GrassSnapshotTest {

	private static final LocalDate FROM = LocalDate.of(2026, 9, 21);
	private static final LocalDate TO = LocalDate.of(2026, 10, 5);
	private static final LocalDateTime FETCHED_AT = LocalDateTime.of(2026, 10, 5, 6, 0);

	private static List<GrassDay> days(LocalDate from, LocalDate to, int count) {
		List<GrassDay> days = new ArrayList<>();
		for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
			days.add(new GrassDay(day, count));
		}
		return days;
	}

	private static GrassSnapshot snapshot() {
		return new GrassSnapshot(7L, FROM, TO, FETCHED_AT, days(FROM, TO, 1));
	}

	@Test
	void 기여_수_1_이상이면_잔디가_있다() {
		assertThat(new GrassDay(FROM, 0).hasGrass()).isFalse();
		assertThat(new GrassDay(FROM, 1).hasGrass()).isTrue();
		assertThat(new GrassDay(FROM, 25).hasGrass()).isTrue();
	}

	@Test
	void 일자_기여_수는_음수일_수_없고_날짜는_필수다() {
		assertThatThrownBy(() -> new GrassDay(FROM, -1)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new GrassDay(null, 1)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void 구간을_빠짐없이_채운_결과를_받는다() {
		GrassSnapshot snapshot = snapshot();

		assertThat(snapshot.days()).hasSize(15);
		assertThat(snapshot.fetchedAt()).isEqualTo(FETCHED_AT);
		assertThat(snapshot.totalContributions()).isEqualTo(15);
	}

	@Test
	void 하루가_빠지면_거부한다() {
		List<GrassDay> missing = days(FROM, TO, 1);
		missing.remove(3);

		assertThatThrownBy(() -> new GrassSnapshot(7L, FROM, TO, FETCHED_AT, missing))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void 날짜_순서가_뒤섞이면_거부한다() {
		List<GrassDay> shuffled = days(FROM, TO, 1);
		GrassDay first = shuffled.get(0);
		shuffled.set(0, shuffled.get(1));
		shuffled.set(1, first);

		assertThatThrownBy(() -> new GrassSnapshot(7L, FROM, TO, FETCHED_AT, shuffled))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void 구간_밖_날짜가_섞이면_거부한다() {
		List<GrassDay> shifted = days(FROM.plusDays(1), TO.plusDays(1), 1);

		assertThatThrownBy(() -> new GrassSnapshot(7L, FROM, TO, FETCHED_AT, shifted))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void 종료일이_시작일보다_앞이면_거부한다() {
		assertThatThrownBy(() -> new GrassSnapshot(7L, TO, FROM, FETCHED_AT, List.of()))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void 빈_값이_있으면_거부한다() {
		assertThatThrownBy(() -> new GrassSnapshot(7L, null, TO, FETCHED_AT, days(FROM, TO, 1)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new GrassSnapshot(7L, FROM, null, FETCHED_AT, days(FROM, TO, 1)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new GrassSnapshot(7L, FROM, TO, null, days(FROM, TO, 1)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new GrassSnapshot(7L, FROM, TO, FETCHED_AT, null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void 하루짜리_구간도_만들_수_있다() {
		GrassSnapshot one = new GrassSnapshot(7L, FROM, FROM, FETCHED_AT, days(FROM, FROM, 4));

		assertThat(one.days()).hasSize(1);
		assertThat(one.totalContributions()).isEqualTo(4);
	}

	@Test
	void 일자_목록은_복사해서_바꿀_수_없다() {
		List<GrassDay> source = days(FROM, TO, 1);
		GrassSnapshot snapshot = new GrassSnapshot(7L, FROM, TO, FETCHED_AT, source);

		source.clear();

		assertThat(snapshot.days()).hasSize(15);
		assertThatThrownBy(() -> snapshot.days().clear()).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void covers는_양끝을_포함해_구간_안일_때만_참이다() {
		GrassSnapshot snapshot = snapshot();

		assertThat(snapshot.covers(FROM, TO)).isTrue();
		assertThat(snapshot.covers(FROM.plusDays(7), FROM.plusDays(13))).isTrue();
		assertThat(snapshot.covers(FROM.minusDays(1), TO)).isFalse();
		assertThat(snapshot.covers(FROM, TO.plusDays(1))).isFalse();
	}

	@Test
	void 구간의_일자별_잔디_유무를_돌려준다() {
		List<GrassDay> mixed = new ArrayList<>();
		for (LocalDate day = FROM; !day.isAfter(TO); day = day.plusDays(1)) {
			mixed.add(new GrassDay(day, day.equals(LocalDate.of(2026, 9, 29)) ? 3 : 0));
		}
		GrassSnapshot snapshot = new GrassSnapshot(7L, FROM, TO, FETCHED_AT, mixed);

		Map<LocalDate, Boolean> week = snapshot.hasGrassBetween(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 4));

		assertThat(week).hasSize(7);
		assertThat(week).containsEntry(LocalDate.of(2026, 9, 28), false)
				.containsEntry(LocalDate.of(2026, 9, 29), true)
				.containsEntry(LocalDate.of(2026, 10, 4), false);
		assertThat(week.keySet()).first().isEqualTo(LocalDate.of(2026, 9, 28));
	}

	@Test
	void 요청_구간을_덮지_못하면_0칸으로_오해하지_않고_거부한다() {
		GrassSnapshot snapshot = snapshot();

		assertThatThrownBy(() -> snapshot.hasGrassBetween(FROM.minusDays(1), FROM.plusDays(5)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> snapshot.hasGrassBetween(TO.minusDays(2), TO.plusDays(1)))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void 총_기여가_0이면_비공개_기여_표시를_의심할_수_있게_합계가_0이다() {
		GrassSnapshot empty = new GrassSnapshot(7L, FROM, TO, FETCHED_AT, days(FROM, TO, 0));

		assertThat(empty.totalContributions()).isZero();
	}

}
