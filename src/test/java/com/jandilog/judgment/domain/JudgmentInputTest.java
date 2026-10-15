package com.jandilog.judgment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

// 판정 입력의 검증과 방어적 복사 (주차는 월요일 LocalDate로만 식별한다, DB명세서 1-0)
class JudgmentInputTest {

	private static final LocalDate MONDAY = LocalDate.of(2026, 9, 28);

	private static JudgmentInput input(LocalDate weekStart, Set<Long> teams, Map<LocalDate, Integer> records) {
		return new JudgmentInput(weekStart, teams, null, false, false, null, records);
	}

	@Test
	void 월요일_weekStart는_받는다() {
		assertThat(input(MONDAY, Set.of(1L), Map.of()).weekStart()).isEqualTo(MONDAY);
	}

	@ParameterizedTest
	@EnumSource(value = DayOfWeek.class, names = "MONDAY", mode = EnumSource.Mode.EXCLUDE)
	void 월요일이_아닌_weekStart는_거부한다(DayOfWeek dayOfWeek) {
		LocalDate notMonday = MONDAY.plusDays(dayOfWeek.getValue() - 1);

		assertThatThrownBy(() -> input(notMonday, Set.of(1L), Map.of())).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void weekStart가_null이면_거부한다() {
		assertThatThrownBy(() -> input(null, Set.of(1L), Map.of())).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void 소속_스냅샷_팀_목록은_여러_팀도_그대로_보존한다() {
		JudgmentInput input = input(MONDAY, Set.of(11L, 22L, 33L), Map.of());

		assertThat(input.teamIdsAtWeekEnd()).containsExactlyInAnyOrder(11L, 22L, 33L);
	}

	@Test
	void 넘겨받은_팀_집합을_나중에_바꿔도_입력은_변하지_않는다() {
		Set<Long> teams = new HashSet<>(Set.of(11L, 22L));
		JudgmentInput input = input(MONDAY, teams, Map.of());

		teams.add(33L);
		teams.remove(11L);

		assertThat(input.teamIdsAtWeekEnd()).containsExactlyInAnyOrder(11L, 22L);
	}

	@Test
	void 입력의_팀_집합은_바꿀_수_없다() {
		JudgmentInput input = input(MONDAY, Set.of(11L), Map.of());

		assertThatThrownBy(() -> input.teamIdsAtWeekEnd().add(99L)).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void 팀_목록과_기록글_맵이_null이면_빈_값으로_다룬다() {
		JudgmentInput input = input(MONDAY, null, null);

		assertThat(input.teamIdsAtWeekEnd()).isEmpty();
		assertThat(input.recordPostCounts()).isEmpty();
	}

	@Test
	void 넘겨받은_기록글_맵을_나중에_바꿔도_입력은_변하지_않는다() {
		Map<LocalDate, Integer> records = new HashMap<>(Map.of(MONDAY, 1));
		JudgmentInput input = input(MONDAY, Set.of(1L), records);

		records.put(MONDAY.plusDays(1), 5);
		records.put(MONDAY, 9);

		assertThat(input.recordPostCounts()).containsOnly(Map.entry(MONDAY, 1));
	}

}
