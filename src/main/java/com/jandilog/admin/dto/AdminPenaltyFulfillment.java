package com.jandilog.admin.dto;

import com.jandilog.member.dto.MemberBrief;

// 벌칙 이행 체크 결과. 이 시각이 되돌릴 수 없는 고정점이 된다 (E-58)
public record AdminPenaltyFulfillment(MemberBrief member, String fulfilledAt, String reachedWeek) {
}
