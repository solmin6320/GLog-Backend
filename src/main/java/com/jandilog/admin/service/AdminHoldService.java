package com.jandilog.admin.service;

import org.springframework.stereotype.Service;

import com.jandilog.admin.dto.AdminHoldRerunSummary;
import com.jandilog.admin.dto.AdminHoldRetryResult;
import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.common.validation.InputRules;
import com.jandilog.judgment.domain.JudgmentStatus;
import com.jandilog.judgment.domain.WeeklyJudgment;
import com.jandilog.judgment.service.JudgmentHoldService;
import com.jandilog.judgment.service.JudgmentRecordService.Outcome;
import com.jandilog.judgment.service.JudgmentRecordService.Type;
import com.jandilog.member.domain.Member;
import com.jandilog.member.dto.MemberBrief;
import com.jandilog.member.repository.MemberRepository;

// 대시보드의 보류 재시도 (기능명세서 5·9장, AD-01 ⑦⑨). 재실행 자체는 JudgmentHoldService가 하고 여기서는 화면 응답으로 바꾼다.
// 재실행은 보류 건에만 적용되고 확정된 건은 건너뛴다 (E-35). GitHub를 부르므로 DB 트랜잭션으로 감싸지 않는다
@Service
public class AdminHoldService {

	private final JudgmentHoldService holdService;
	private final MemberRepository memberRepository;

	public AdminHoldService(JudgmentHoldService holdService, MemberRepository memberRepository) {
		this.holdService = holdService;
		this.memberRepository = memberRepository;
	}

	// 행마다 [재시도]. 판정 행이 없으면 NOT_FOUND
	public AdminHoldRetryResult retryOne(String memberId, String weekStart) {
		long id = InputRules.id(memberId);
		Member member = memberRepository.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		Outcome outcome = holdService.rerunOne(id, InputRules.weekStart(weekStart));
		WeeklyJudgment judgment = outcome.judgment();
		boolean resolved = outcome.type() == Type.UPDATED && judgment.getStatus() != JudgmentStatus.HOLD;
		return new AdminHoldRetryResult(MemberBrief.from(member), judgment.getWeekStart().toString(),
				judgment.getStatus(), judgment.getHoldReason(), resolved);
	}

	// [전체 재시도]: 사람이 봐야 하는 보류 건만 다시 판정한다
	public AdminHoldRerunSummary retryAll() {
		JudgmentHoldService.RerunSummary summary = holdService.rerunAll();
		return new AdminHoldRerunSummary(summary.attempted(), summary.resolved(), summary.stillHold(),
				summary.failed());
	}

}
