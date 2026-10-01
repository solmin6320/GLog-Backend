package com.jandilog.exemption.dto;

import com.jandilog.common.time.KstFormats;
import com.jandilog.exemption.domain.PersonalExemption;
import com.jandilog.exemption.domain.PersonalExemptionStatus;
import com.jandilog.member.dto.MemberBrief;

// 개인 면제 요청 한 건. 팀 이름은 싣지 않는다 (화면설계서 AD-01 ⑦).
// overlapsExemptionPeriod: 그 주가 이미 전체 면제 기간이라 따로 승인하지 않아도 된다 (E-44, 두 면제는 독립 보존)
public record PersonalExemptionResponse(
		Long id,
		MemberBrief member,
		MemberBrief requester,
		String weekStart,
		String weekEnd,
		PersonalExemptionStatus status,
		String reason,
		String rejectReason,
		String respondedAt,
		String createdAt,
		boolean overlapsExemptionPeriod) {

	public static PersonalExemptionResponse of(PersonalExemption exemption, MemberBrief member, MemberBrief requester,
			boolean overlapsExemptionPeriod) {
		return new PersonalExemptionResponse(exemption.getId(), member, requester, exemption.getWeekStart().toString(),
				exemption.getWeekStart().plusDays(6).toString(), exemption.getStatus(), exemption.getReason(),
				exemption.getRejectReason(), KstFormats.of(exemption.getRespondedAt()),
				KstFormats.of(exemption.getCreatedAt()), overlapsExemptionPeriod);
	}

}
