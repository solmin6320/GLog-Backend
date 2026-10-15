package com.jandilog.admin.dto;

import com.jandilog.judgment.domain.HoldReason;
import com.jandilog.member.dto.MemberBrief;

// 보류 목록 한 건 (AD-01 ⑨). reason이 IDENTITY_MISMATCH면 재시도로는 안 풀리고 본인이 다시 로그인해야 한다
public record AdminHold(
		MemberBrief member,
		String weekStart,
		HoldReason reason,
		int retryCount,
		boolean retriesExhausted) {
}
