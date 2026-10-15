package com.jandilog.admin.dto;

import com.jandilog.judgment.domain.HoldReason;
import com.jandilog.judgment.domain.JudgmentStatus;
import com.jandilog.judgment.domain.SkipReason;
import com.jandilog.member.dto.MemberBrief;

// 판정 결과 목록의 회원 한 행 (AD-01 ⑦). 팀 이름은 싣지 않는다 (기능명세서 9장).
// warningCount: 누적 경고, 보류 주가 남은 회원은 계산하지 않아 null (Q-03).
// manualHold: 사람이 처리해야 하는 보류(아이디 불일치이거나 자동 재시도를 다 씀). 자동 재시도가 남은 보류는 false
public record AdminJudgmentRow(
		Long judgmentId,
		MemberBrief member,
		JudgmentStatus status,
		SkipReason skipReason,
		HoldReason holdReason,
		Integer verifiedDays,
		Integer recordCount,
		Integer warningCount,
		boolean corrected,
		boolean manualHold) {
}
