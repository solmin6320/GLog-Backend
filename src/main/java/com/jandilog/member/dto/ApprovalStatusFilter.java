package com.jandilog.member.dto;

import java.util.List;

import com.jandilog.member.domain.MemberStatus;

// 가입 승인 탭의 상태 필터. 전체는 이 탭이 다루는 두 상태(승인 대기·거절)
public enum ApprovalStatusFilter {
	PENDING(List.of(MemberStatus.PENDING)),
	REJECTED(List.of(MemberStatus.REJECTED)),
	ALL(List.of(MemberStatus.PENDING, MemberStatus.REJECTED));

	private final List<MemberStatus> statuses;

	ApprovalStatusFilter(List<MemberStatus> statuses) {
		this.statuses = statuses;
	}

	public List<MemberStatus> statuses() {
		return statuses;
	}
}
