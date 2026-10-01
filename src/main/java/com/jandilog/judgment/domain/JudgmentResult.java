package com.jandilog.judgment.domain;

import java.time.LocalDateTime;
import java.util.List;

// 주간 판정 계산 결과. weekly_judgment 한 행과 judgment_day 7행으로 옮겨 저장한다.
// 제외·면제·보류 주는 verifiedDays·recordCount가 null이고 days가 비어 있다 (화면설계서 AC-01 "—")
public record JudgmentResult(
		JudgmentStatus status,
		SkipReason skipReason,
		HoldReason holdReason,
		Integer verifiedDays,
		Integer recordCount,
		List<DayResult> days,
		LocalDateTime judgedAt) {

	public JudgmentResult {
		days = days == null ? List.of() : List.copyOf(days);
	}

	public static JudgmentResult skipped(SkipReason reason, LocalDateTime judgedAt) {
		return new JudgmentResult(reason.status(), reason, null, null, null, List.of(), judgedAt);
	}

	public static JudgmentResult hold(HoldReason reason) {
		return new JudgmentResult(JudgmentStatus.HOLD, null, reason, null, null, List.of(), null);
	}

	public static JudgmentResult judged(boolean pass, int verifiedDays, int recordCount, List<DayResult> days,
			LocalDateTime judgedAt) {
		return new JudgmentResult(pass ? JudgmentStatus.PASS : JudgmentStatus.FAIL, null, null, verifiedDays,
				recordCount, days, judgedAt);
	}

	public boolean isConfirmed() {
		return status.isConfirmed();
	}

	// 미달이면 경고 1개를 부여한다 (사람당 1개, 기능명세서 6장)
	public boolean warningRequired() {
		return status == JudgmentStatus.FAIL;
	}

}
