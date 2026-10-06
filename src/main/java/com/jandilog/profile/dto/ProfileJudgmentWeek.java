package com.jandilog.profile.dto;

import com.jandilog.judgment.dto.JudgmentHistoryItem;
import com.jandilog.judgment.dto.WeeklyActivityResponse;

// 프로필 판정 이력 한 행 (PR-01 ⑩). 통과·미달 주만 인증일·기록글을 보이고 면제·제외·보류 주는 null이다 (AC-01 ⑤ "—").
// 팀 이름은 싣지 않는다
public record ProfileJudgmentWeek(
		String weekStart,
		String weekEnd,
		ProfileJudgmentResult result,
		Integer verifiedDays,
		Integer recordCount) {

	// 저장된 판정 행. 소급 면제로 면제가 된 주는 수치가 남아 있어도 null로 낸다
	public static ProfileJudgmentWeek from(JudgmentHistoryItem item) {
		ProfileJudgmentResult result = ProfileJudgmentResult.of(item.status());
		boolean numbered = result == ProfileJudgmentResult.PASS || result == ProfileJudgmentResult.FAIL;
		return new ProfileJudgmentWeek(item.weekStart(), item.weekEnd(), result,
				numbered ? item.verifiedDays() : null, numbered ? item.recordCount() : null);
	}

	// 이번 주 진행 중 행. 수치는 본인 주간 활동과 같은 계산이고 잔디 캐시가 없으면 인증일만 null이다
	public static ProfileJudgmentWeek inProgress(WeeklyActivityResponse activity) {
		return new ProfileJudgmentWeek(activity.weekStart(), activity.weekEnd(), ProfileJudgmentResult.IN_PROGRESS,
				activity.verifiedDays(), activity.recordCount());
	}

}
