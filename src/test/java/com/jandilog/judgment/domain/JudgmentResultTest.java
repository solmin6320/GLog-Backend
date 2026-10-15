package com.jandilog.judgment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

// 판정 결과·상태·사유·잔디 조회 결과 값 객체 (DB명세서 1-6 상태 표)
class JudgmentResultTest {

	private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 5, 7, 0);
	private static final LocalDate MONDAY = LocalDate.of(2026, 9, 28);

	@Test
	void 확정_상태는_HOLD만_아니다() {
		for (JudgmentStatus status : JudgmentStatus.values()) {
			assertThat(status.isConfirmed()).as(status.name()).isEqualTo(status != JudgmentStatus.HOLD);
		}
	}

	@Test
	void 경고와_연속_계산에서_건너뛰는_상태는_면제와_제외다() {
		assertThat(JudgmentStatus.EXEMPT.isSkipped()).isTrue();
		assertThat(JudgmentStatus.EXCLUDED.isSkipped()).isTrue();
		assertThat(JudgmentStatus.PASS.isSkipped()).isFalse();
		assertThat(JudgmentStatus.FAIL.isSkipped()).isFalse();
		assertThat(JudgmentStatus.HOLD.isSkipped()).isFalse();
	}

	@Test
	void skip_reason과_status_대응은_고정이다() {
		assertThat(SkipReason.NO_TEAM.status()).isEqualTo(JudgmentStatus.EXCLUDED);
		assertThat(SkipReason.FIRST_WEEK.status()).isEqualTo(JudgmentStatus.EXCLUDED);
		assertThat(SkipReason.EXEMPTION_PERIOD.status()).isEqualTo(JudgmentStatus.EXEMPT);
		assertThat(SkipReason.PERSONAL_EXEMPTION.status()).isEqualTo(JudgmentStatus.EXEMPT);
	}

	@Test
	void 사유_ENUM_이름은_DB_ENUM_값과_같다() {
		assertThat(JudgmentStatus.values()).extracting(Enum::name)
				.containsExactly("PASS", "FAIL", "EXEMPT", "EXCLUDED", "HOLD");
		assertThat(SkipReason.values()).extracting(Enum::name)
				.containsExactly("NO_TEAM", "FIRST_WEEK", "EXEMPTION_PERIOD", "PERSONAL_EXEMPTION");
		assertThat(HoldReason.values()).extracting(Enum::name).containsExactly("API_ERROR", "IDENTITY_MISMATCH");
	}

	@ParameterizedTest
	@EnumSource(SkipReason.class)
	void 제외_면제_결과는_사유에_맞는_상태이고_수치가_없다(SkipReason reason) {
		JudgmentResult result = JudgmentResult.skipped(reason, AT);

		assertThat(result.status()).isEqualTo(reason.status());
		assertThat(result.skipReason()).isEqualTo(reason);
		assertThat(result.holdReason()).isNull();
		assertThat(result.verifiedDays()).isNull();
		assertThat(result.recordCount()).isNull();
		assertThat(result.days()).isEmpty();
		assertThat(result.judgedAt()).isEqualTo(AT);
		assertThat(result.isConfirmed()).isTrue();
		assertThat(result.warningRequired()).isFalse();
	}

	@Test
	void 미달만_경고를_요구한다() {
		List<DayResult> days = sevenDays();

		assertThat(JudgmentResult.judged(false, 2, 0, days, AT).warningRequired()).isTrue();
		assertThat(JudgmentResult.judged(true, 3, 1, days, AT).warningRequired()).isFalse();
		assertThat(JudgmentResult.hold(HoldReason.API_ERROR).warningRequired()).isFalse();
		assertThat(JudgmentResult.skipped(SkipReason.NO_TEAM, AT).warningRequired()).isFalse();
		assertThat(JudgmentResult.skipped(SkipReason.PERSONAL_EXEMPTION, AT).warningRequired()).isFalse();
	}

	@Test
	void 보류_결과는_확정이_아니고_판정_시각이_없다() {
		JudgmentResult result = JudgmentResult.hold(HoldReason.IDENTITY_MISMATCH);

		assertThat(result.status()).isEqualTo(JudgmentStatus.HOLD);
		assertThat(result.isConfirmed()).isFalse();
		assertThat(result.judgedAt()).isNull();
		assertThat(result.holdReason()).isEqualTo(HoldReason.IDENTITY_MISMATCH);
	}

	@Test
	void 일자별_근거_목록은_복사해서_바꿀_수_없다() {
		List<DayResult> source = new ArrayList<>(sevenDays());
		JudgmentResult result = JudgmentResult.judged(true, 3, 1, source, AT);

		source.clear();

		assertThat(result.days()).hasSize(7);
		assertThatThrownBy(() -> result.days().clear()).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void 인증일은_잔디_또는_기록글이고_둘_다여도_1일이다() {
		assertThat(new DayResult(MONDAY, false, false).verified()).isFalse();
		assertThat(new DayResult(MONDAY, true, false).verified()).isTrue();
		assertThat(new DayResult(MONDAY, false, true).verified()).isTrue();
		assertThat(new DayResult(MONDAY, true, true).verified()).isTrue();
	}

	@Test
	void 잔디_조회_결과는_성공_맵과_실패_사유_중_하나만_가진다() {
		Map<LocalDate, Boolean> map = Map.of(MONDAY, true);

		assertThat(GrassLookup.available(map).isFailed()).isFalse();
		assertThat(GrassLookup.failed(HoldReason.API_ERROR).isFailed()).isTrue();
		assertThatThrownBy(() -> new GrassLookup(null, null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new GrassLookup(map, HoldReason.API_ERROR))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void 잔디_조회_결과의_맵은_복사해서_바꿀_수_없다() {
		Map<LocalDate, Boolean> source = new HashMap<>(Map.of(MONDAY, true));
		GrassLookup lookup = GrassLookup.available(source);

		source.put(MONDAY.plusDays(1), true);

		assertThat(lookup.hasGrassByDay()).containsOnlyKeys(MONDAY);
		assertThatThrownBy(() -> lookup.hasGrassByDay().put(MONDAY.plusDays(2), true))
				.isInstanceOf(UnsupportedOperationException.class);
	}

	private static List<DayResult> sevenDays() {
		List<DayResult> days = new ArrayList<>();
		for (int i = 0; i < 7; i++) {
			days.add(new DayResult(MONDAY.plusDays(i), i < 3, i == 0));
		}
		return days;
	}

}
