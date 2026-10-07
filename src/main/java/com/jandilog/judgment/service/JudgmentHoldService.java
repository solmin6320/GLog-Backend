package com.jandilog.judgment.service;

import java.time.LocalDate;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.judgment.domain.HoldReason;
import com.jandilog.judgment.domain.JudgmentStatus;
import com.jandilog.judgment.domain.WeeklyJudgment;
import com.jandilog.judgment.repository.WeeklyJudgmentRepository;
import com.jandilog.judgment.service.JudgmentRecordService.Outcome;

// 관리자 대시보드의 보류 목록과 재실행 (기능명세서 5장, E-32·E-35). 권한 검사와 GraphQL은 호출하는 쪽 몫이다.
// 목록에는 사람이 봐야 하는 건만 오른다: 자동 재시도가 소진됐거나 아이디 불일치(본인 재로그인 필요).
// 재실행은 보류 건에만 적용되고 확정된 건은 건너뛴다. 회원 + 주차시작일로 멱등하다
@Service
public class JudgmentHoldService {

	private static final Logger log = LoggerFactory.getLogger(JudgmentHoldService.class);

	// retriesExhausted: 자동 재시도 3회를 다 썼는가. reason이 IDENTITY_MISMATCH면 재시도로는 풀리지 않는다
	public record HoldItem(long memberId, LocalDate weekStart, HoldReason reason, int retryCount,
			boolean retriesExhausted) {
	}

	// attempted: 다시 돌린 보류 건 수, resolved: 그중 확정된 건, stillHold: 여전히 보류, failed: 예외
	public record RerunSummary(int attempted, int resolved, int stillHold, int failed) {
	}

	private final WeeklyJudgmentRepository judgmentRepository;
	private final MemberJudgmentService memberJudgmentService;

	public JudgmentHoldService(WeeklyJudgmentRepository judgmentRepository,
			MemberJudgmentService memberJudgmentService) {
		this.judgmentRepository = judgmentRepository;
		this.memberJudgmentService = memberJudgmentService;
	}

	// 오래된 주부터. 보류가 없으면 빈 목록이고, 화면은 보류 표시를 아예 그리지 않는다
	public List<HoldItem> listManualHolds() {
		return judgmentRepository
				.findManualHolds(JudgmentStatus.HOLD, HoldReason.IDENTITY_MISMATCH, WeeklyJudgment.MAX_AUTO_RETRIES)
				.stream()
				.map(j -> new HoldItem(j.getMemberId(), j.getWeekStart(), j.getHoldReason(), j.getRetryCount(),
						j.getRetryCount() >= WeeklyJudgment.MAX_AUTO_RETRIES))
				.toList();
	}

	// [전체 재시도]: 목록에 오른 보류 건만 다시 판정한다
	public RerunSummary rerunAll() {
		List<HoldItem> holds = listManualHolds();
		int resolved = 0;
		int stillHold = 0;
		int failed = 0;
		for (HoldItem hold : holds) {
			try {
				Outcome outcome = memberJudgmentService.judge(hold.memberId(), hold.weekStart(), false);
				if (outcome.judgment().getStatus() == JudgmentStatus.HOLD) {
					stillHold++;
				}
				else {
					resolved++;
				}
			}
			catch (RuntimeException e) {
				failed++;
				log.error("보류 재실행 중 오류 memberId={} weekStart={}", hold.memberId(), hold.weekStart(), e);
			}
		}
		return new RerunSummary(holds.size(), resolved, stillHold, failed);
	}

	// 행마다 [재시도]. 판정 행이 없으면 보류 건이 아니므로 새로 만들지 않고 NOT_FOUND, 이미 확정된 건은 건너뛴 결과를 돌려준다
	public Outcome rerunOne(long memberId, LocalDate weekStart) {
		judgmentRepository.findByMemberIdAndWeekStart(memberId, weekStart)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		return memberJudgmentService.judge(memberId, weekStart, false);
	}

}
