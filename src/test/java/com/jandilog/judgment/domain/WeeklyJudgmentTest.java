package com.jandilog.judgment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

// weekly_judgment 엔티티의 상태 전이. 보류는 확정이 아니고, 재실행 결과가 이전 값을 덮어쓴다 (기능명세서 5장)
class WeeklyJudgmentTest {

	private static final LocalDate WEEK = LocalDate.of(2026, 9, 28);
	private static final LocalDateTime JUDGED_AT = LocalDateTime.of(2026, 10, 5, 7, 0);

	private static List<DayResult> sevenDays() {
		return IntStream.range(0, 7).mapToObj(i -> new DayResult(WEEK.plusDays(i), i < 3, false))
				.toList();
	}

	@Test
	void 계산_결과로_만들면_상태와_수치를_그대로_담고_정정_표시는_없다() {
		JudgmentResult result = JudgmentResult.judged(true, 3, 1, sevenDays(), JUDGED_AT);

		WeeklyJudgment judgment = WeeklyJudgment.of(11L, WEEK, result);

		assertThat(judgment.getMemberId()).isEqualTo(11L);
		assertThat(judgment.getWeekStart()).isEqualTo(WEEK);
		assertThat(judgment.getStatus()).isEqualTo(JudgmentStatus.PASS);
		assertThat(judgment.getVerifiedDays()).isEqualTo(3);
		assertThat(judgment.getRecordCount()).isEqualTo(1);
		assertThat(judgment.getJudgedAt()).isEqualTo(JUDGED_AT);
		assertThat(judgment.getSkipReason()).isNull();
		assertThat(judgment.getHoldReason()).isNull();
		assertThat(judgment.getRetryCount()).isZero();
		assertThat(judgment.isCorrected()).isFalse();
	}

	@Test
	void 보류는_확정이_아니고_사유를_담는다() {
		WeeklyJudgment judgment = WeeklyJudgment.of(11L, WEEK, JudgmentResult.hold(HoldReason.API_ERROR));

		assertThat(judgment.getStatus()).isEqualTo(JudgmentStatus.HOLD);
		assertThat(judgment.getHoldReason()).isEqualTo(HoldReason.API_ERROR);
		assertThat(judgment.isConfirmed()).isFalse();
		assertThat(judgment.getJudgedAt()).isNull();
		assertThat(judgment.getVerifiedDays()).isNull();
	}

	@Test
	void 통과_미달_제외_면제는_확정이다() {
		WeeklyJudgment pass = WeeklyJudgment.of(1L, WEEK, JudgmentResult.judged(true, 3, 1, sevenDays(), JUDGED_AT));
		WeeklyJudgment fail = WeeklyJudgment.of(1L, WEEK, JudgmentResult.judged(false, 1, 0, sevenDays(), JUDGED_AT));
		WeeklyJudgment excluded = WeeklyJudgment.of(1L, WEEK, JudgmentResult.skipped(SkipReason.NO_TEAM, JUDGED_AT));
		WeeklyJudgment exempt = WeeklyJudgment.of(1L, WEEK,
				JudgmentResult.skipped(SkipReason.EXEMPTION_PERIOD, JUDGED_AT));

		assertThat(List.of(pass, fail, excluded, exempt)).allMatch(WeeklyJudgment::isConfirmed);
	}

	@Test
	void 제외와_면제는_사유와_함께_수치가_비어_있다() {
		WeeklyJudgment excluded = WeeklyJudgment.of(1L, WEEK, JudgmentResult.skipped(SkipReason.FIRST_WEEK, JUDGED_AT));
		WeeklyJudgment exempt = WeeklyJudgment.of(1L, WEEK,
				JudgmentResult.skipped(SkipReason.PERSONAL_EXEMPTION, JUDGED_AT));

		assertThat(excluded.getStatus()).isEqualTo(JudgmentStatus.EXCLUDED);
		assertThat(excluded.getSkipReason()).isEqualTo(SkipReason.FIRST_WEEK);
		assertThat(exempt.getStatus()).isEqualTo(JudgmentStatus.EXEMPT);
		assertThat(exempt.getSkipReason()).isEqualTo(SkipReason.PERSONAL_EXEMPTION);
		assertThat(List.of(excluded, exempt)).allSatisfy(j -> {
			assertThat(j.getVerifiedDays()).isNull();
			assertThat(j.getRecordCount()).isNull();
		});
	}

	@Test
	void 보류를_재실행해_확정하면_보류_사유가_지워지고_수치가_채워진다() {
		WeeklyJudgment judgment = WeeklyJudgment.of(11L, WEEK, JudgmentResult.hold(HoldReason.IDENTITY_MISMATCH));

		judgment.apply(JudgmentResult.judged(false, 2, 0, sevenDays(), JUDGED_AT));

		assertThat(judgment.getStatus()).isEqualTo(JudgmentStatus.FAIL);
		assertThat(judgment.getHoldReason()).isNull();
		assertThat(judgment.getVerifiedDays()).isEqualTo(2);
		assertThat(judgment.getRecordCount()).isZero();
		assertThat(judgment.getJudgedAt()).isEqualTo(JUDGED_AT);
		assertThat(judgment.isConfirmed()).isTrue();
	}

	@Test
	void 보류를_다시_돌려_또_보류면_새_사유로_바뀐다() {
		WeeklyJudgment judgment = WeeklyJudgment.of(11L, WEEK, JudgmentResult.hold(HoldReason.API_ERROR));

		judgment.apply(JudgmentResult.hold(HoldReason.IDENTITY_MISMATCH));

		assertThat(judgment.getStatus()).isEqualTo(JudgmentStatus.HOLD);
		assertThat(judgment.getHoldReason()).isEqualTo(HoldReason.IDENTITY_MISMATCH);
		assertThat(judgment.isConfirmed()).isFalse();
	}

	@Test
	void 보류를_재실행해_제외로_확정하면_보류_사유가_지워지고_제외_사유가_붙는다() {
		WeeklyJudgment judgment = WeeklyJudgment.of(11L, WEEK, JudgmentResult.hold(HoldReason.API_ERROR));

		judgment.apply(JudgmentResult.skipped(SkipReason.PERSONAL_EXEMPTION, JUDGED_AT));

		assertThat(judgment.getStatus()).isEqualTo(JudgmentStatus.EXEMPT);
		assertThat(judgment.getSkipReason()).isEqualTo(SkipReason.PERSONAL_EXEMPTION);
		assertThat(judgment.getHoldReason()).isNull();
	}

	@Test
	void 재시도_횟수는_하나씩_늘고_재실행_결과_적용으로는_바뀌지_않는다() {
		WeeklyJudgment judgment = WeeklyJudgment.of(11L, WEEK, JudgmentResult.hold(HoldReason.API_ERROR));

		judgment.increaseRetryCount();
		judgment.increaseRetryCount();
		judgment.apply(JudgmentResult.hold(HoldReason.API_ERROR));

		assertThat(judgment.getRetryCount()).isEqualTo(2);
	}

}
