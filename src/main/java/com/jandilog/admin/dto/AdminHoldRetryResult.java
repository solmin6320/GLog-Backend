package com.jandilog.admin.dto;

import com.jandilog.judgment.domain.HoldReason;
import com.jandilog.judgment.domain.JudgmentStatus;
import com.jandilog.member.dto.MemberBrief;

// 보류 한 건 재시도 결과. resolved는 이번에 보류가 풀려 확정됐다는 뜻이고, 이미 확정돼 건너뛴 건과 여전히 보류인 건은 false
public record AdminHoldRetryResult(
		MemberBrief member,
		String weekStart,
		JudgmentStatus status,
		HoldReason holdReason,
		boolean resolved) {
}
