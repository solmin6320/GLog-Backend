package com.jandilog.judgment.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.jandilog.judgment.domain.DayResult;
import com.jandilog.judgment.domain.HoldReason;
import com.jandilog.judgment.domain.JudgmentResult;
import com.jandilog.judgment.domain.JudgmentStatus;
import com.jandilog.judgment.domain.SkipReason;
import com.jandilog.judgment.domain.WeeklyJudgment;

// 한 주 판정 응답은 통과·미달 주에만 인증일·기록글을 담는다. 면제·제외·보류는 저장값이 있어도 null이다
class WeekJudgmentResponseTest {

	private static final LocalDate WEEK = LocalDate.of(2026, 9, 28);
	private static final LocalDateTime JUDGED_AT = LocalDateTime.of(2026, 10, 5, 7, 0);

	private static List<DayResult> sevenDays() {
		return IntStream.range(0, 7).mapToObj(i -> new DayResult(WEEK.plusDays(i), true, false)).toList();
	}

	@Test
	void 통과_주는_저장된_인증일과_기록글을_담는다() {
		WeeklyJudgment pass = WeeklyJudgment.of(1L, WEEK, JudgmentResult.judged(true, 4, 2, sevenDays(), JUDGED_AT));

		WeekJudgmentResponse response = WeekJudgmentResponse.from(pass);

		assertThat(response.status()).isEqualTo(JudgmentStatus.PASS);
		assertThat(response.verifiedDays()).isEqualTo(4);
		assertThat(response.recordCount()).isEqualTo(2);
		assertThat(response.judgedAt()).isEqualTo("2026-10-05T07:00:00");
	}

	@Test
	void 미달_주도_저장된_인증일과_기록글을_담는다() {
		WeeklyJudgment fail = WeeklyJudgment.of(1L, WEEK, JudgmentResult.judged(false, 2, 0, sevenDays(), JUDGED_AT));

		WeekJudgmentResponse response = WeekJudgmentResponse.from(fail);

		assertThat(response.status()).isEqualTo(JudgmentStatus.FAIL);
		assertThat(response.verifiedDays()).isEqualTo(2);
		assertThat(response.recordCount()).isZero();
	}

	@Test
	void 면제_주에_예전_수치가_남아_있어도_인증일과_기록글은_null이다() {
		// 통과였다가 소급 면제된 주를 가정해 상태만 면제이고 수치는 남은 행을 만든다
		JudgmentResult stale = new JudgmentResult(JudgmentStatus.EXEMPT, SkipReason.PERSONAL_EXEMPTION, null, 4, 2,
				sevenDays(), JUDGED_AT);

		WeekJudgmentResponse response = WeekJudgmentResponse.from(WeeklyJudgment.of(1L, WEEK, stale));

		assertThat(response.status()).isEqualTo(JudgmentStatus.EXEMPT);
		assertThat(response.skipReason()).isEqualTo(SkipReason.PERSONAL_EXEMPTION);
		assertThat(response.verifiedDays()).isNull();
		assertThat(response.recordCount()).isNull();
	}

	@Test
	void 제외_주에_수치가_남아_있어도_인증일과_기록글은_null이다() {
		JudgmentResult stale = new JudgmentResult(JudgmentStatus.EXCLUDED, SkipReason.FIRST_WEEK, null, 3, 1,
				sevenDays(), JUDGED_AT);

		WeekJudgmentResponse response = WeekJudgmentResponse.from(WeeklyJudgment.of(1L, WEEK, stale));

		assertThat(response.status()).isEqualTo(JudgmentStatus.EXCLUDED);
		assertThat(response.verifiedDays()).isNull();
		assertThat(response.recordCount()).isNull();
	}

	@Test
	void 처음부터_면제인_주는_인증일과_기록글이_null이고_사유가_담긴다() {
		JudgmentResult skipped = JudgmentResult.skipped(SkipReason.EXEMPTION_PERIOD, JUDGED_AT);

		WeekJudgmentResponse response = WeekJudgmentResponse.from(WeeklyJudgment.of(1L, WEEK, skipped));

		assertThat(response.skipReason()).isEqualTo(SkipReason.EXEMPTION_PERIOD);
		assertThat(response.verifiedDays()).isNull();
		assertThat(response.recordCount()).isNull();
	}

	@Test
	void 보류_주는_인증일과_기록글이_null이고_보류_사유와_확정_시각_없음이_담긴다() {
		WeeklyJudgment hold = WeeklyJudgment.of(1L, WEEK, JudgmentResult.hold(HoldReason.IDENTITY_MISMATCH));

		WeekJudgmentResponse response = WeekJudgmentResponse.from(hold);

		assertThat(response.status()).isEqualTo(JudgmentStatus.HOLD);
		assertThat(response.holdReason()).isEqualTo(HoldReason.IDENTITY_MISMATCH);
		assertThat(response.verifiedDays()).isNull();
		assertThat(response.recordCount()).isNull();
		assertThat(response.judgedAt()).isNull();
	}

}
