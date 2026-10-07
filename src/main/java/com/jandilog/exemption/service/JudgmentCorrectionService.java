package com.jandilog.exemption.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.jandilog.admin.domain.AdminActionLog;
import com.jandilog.admin.domain.AdminActionType;
import com.jandilog.admin.repository.AdminActionLogRepository;
import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.common.validation.InputRules;
import com.jandilog.exemption.domain.JudgmentCorrection;
import com.jandilog.exemption.dto.CorrectedJudgment;
import com.jandilog.exemption.dto.CorrectionPreview;
import com.jandilog.exemption.dto.JudgmentCorrectionInput;
import com.jandilog.exemption.repository.JudgmentCorrectionRepository;
import com.jandilog.exemption.repository.JudgmentLookupRepository;
import com.jandilog.judgment.domain.JudgmentStatus;
import com.jandilog.judgment.domain.JudgmentTeam;
import com.jandilog.judgment.domain.SkipReason;
import com.jandilog.judgment.domain.WeeklyJudgment;
import com.jandilog.judgment.repository.JudgmentTeamRepository;
import com.jandilog.judgment.repository.WeeklyJudgmentRepository;
import com.jandilog.member.domain.Member;
import com.jandilog.member.dto.MemberBrief;
import com.jandilog.member.repository.MemberRepository;
import com.jandilog.warning.domain.Warning;
import com.jandilog.warning.domain.WarningTeam;
import com.jandilog.warning.dto.RecalcImpact;
import com.jandilog.warning.repository.WarningRepository;
import com.jandilog.warning.repository.WarningTeamRepository;
import com.jandilog.warning.service.RecalcImpactService;
import com.jandilog.warning.service.WarningRecalculationService;

// 판정 정정 (관리자, 기능명세서 7장 · DB명세서 1-14 · AD-01 ⑩). 결과는 통과·미달·면제 셋뿐이다 (Q-05).
// 경고 수와 연속 통과는 저장하지 않고 판정 결과에서 다시 구하므로, 여기서는 판정 행과 경고 레코드만 맞춘다.
// 미리보기는 계산만 하고, 반영은 회원 행 락 안에서 한 번 더 검증한 뒤 정정 이력과 관리자 행동 로그를 함께 남긴다
@Service
public class JudgmentCorrectionService {

	static final int REASON_MAX_LENGTH = 500;
	private static final String TARGET_TYPE = "WEEKLY_JUDGMENT";
	private static final Set<JudgmentStatus> CORRECTION_RESULTS = EnumSet.of(JudgmentStatus.PASS, JudgmentStatus.FAIL,
			JudgmentStatus.EXEMPT);

	private final JudgmentLookupRepository lookupRepository;
	private final WeeklyJudgmentRepository judgmentRepository;
	private final JudgmentTeamRepository judgmentTeamRepository;
	private final WarningRepository warningRepository;
	private final WarningTeamRepository warningTeamRepository;
	private final JudgmentCorrectionRepository correctionRepository;
	private final AdminActionLogRepository actionLogRepository;
	private final MemberRepository memberRepository;
	private final WarningRecalculationService recalculationService;
	private final RecalcImpactService impactService;
	private final Clock clock;

	public JudgmentCorrectionService(JudgmentLookupRepository lookupRepository,
			WeeklyJudgmentRepository judgmentRepository, JudgmentTeamRepository judgmentTeamRepository,
			WarningRepository warningRepository, WarningTeamRepository warningTeamRepository,
			JudgmentCorrectionRepository correctionRepository, AdminActionLogRepository actionLogRepository,
			MemberRepository memberRepository, WarningRecalculationService recalculationService,
			RecalcImpactService impactService, Clock clock) {
		this.lookupRepository = lookupRepository;
		this.judgmentRepository = judgmentRepository;
		this.judgmentTeamRepository = judgmentTeamRepository;
		this.warningRepository = warningRepository;
		this.warningTeamRepository = warningTeamRepository;
		this.correctionRepository = correctionRepository;
		this.actionLogRepository = actionLogRepository;
		this.memberRepository = memberRepository;
		this.recalculationService = recalculationService;
		this.impactService = impactService;
		this.clock = clock;
	}

	// 정정하면 경고가 몇 개에서 몇 개로 바뀌는지 보여준다 (E-49, E-50). 아무것도 바꾸지 않는다
	@Transactional(readOnly = true)
	public CorrectionPreview preview(String judgmentId, JudgmentStatus target) {
		WeeklyJudgment judgment = judgmentRepository.findById(InputRules.id(judgmentId))
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		requireCorrectable(judgment, target);
		Member member = memberRepository.findById(judgment.getMemberId()).orElseThrow();
		RecalcImpact impact = impactService.ofStatusChange(judgment.getMemberId(), judgment.getWeekStart(), target);
		return new CorrectionPreview(judgment.getId(), MemberBrief.from(member), judgment.getWeekStart().toString(),
				judgment.getStatus(), target, impact);
	}

	// 정정을 반영하고, 반영된 판정과 다시 계산한 경고 상태를 돌려준다
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public CorrectedJudgment correct(long adminId, JudgmentCorrectionInput input) {
		long judgmentId = InputRules.id(input.judgmentId());
		JudgmentStatus target = input.status();
		String reason = InputRules.reason(input.reason(), REASON_MAX_LENGTH, ErrorCode.REASON_REQUIRED);

		// 회원 락을 먼저 잡고 판정을 읽는다. 다른 관리자가 먼저 바꾼 결과가 보인다
		long memberId = lookupRepository.findMemberIdById(judgmentId)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		recalculationService.lockMember(memberId);
		WeeklyJudgment judgment = judgmentRepository.findById(judgmentId)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (input.expectedStatus() != null && input.expectedStatus() != judgment.getStatus()) {
			throw new ApiException(ErrorCode.JUDGMENT_CHANGED);
		}
		requireCorrectable(judgment, target);

		LocalDateTime now = LocalDateTime.now(clock);
		JudgmentStatus before = judgment.getStatus();
		judgment.correctTo(target, target == JudgmentStatus.EXEMPT ? SkipReason.PERSONAL_EXEMPTION : null);
		if (target == JudgmentStatus.FAIL) {
			ensureWarning(judgment, now);
		}
		correctionRepository.save(JudgmentCorrection.of(judgmentId, before, target, reason, adminId, now));
		actionLogRepository.save(AdminActionLog.of(adminId, AdminActionType.CORRECT_JUDGMENT, TARGET_TYPE,
				Long.toString(judgmentId), reason, now));

		Member member = memberRepository.findById(memberId).orElseThrow();
		return CorrectedJudgment.of(judgmentId, MemberBrief.from(member), judgment.getWeekStart().toString(), target,
				recalculationService.recalculate(memberId));
	}

	// 정정 결과는 통과·미달·면제 셋뿐이고(Q-05), 보류는 확정 전이라 정정하지 않고 재실행한다.
	// 제외(첫 참가·팀 없음)는 면제로만 정정한다 (기능명세서 7장). 미달로 정정하려면 경고에 붙일 소속 스냅샷이 있어야 한다
	private void requireCorrectable(WeeklyJudgment judgment, JudgmentStatus target) {
		JudgmentStatus current = judgment.getStatus();
		boolean valid = target != null && CORRECTION_RESULTS.contains(target) && current != JudgmentStatus.HOLD
				&& current != target && (current != JudgmentStatus.EXCLUDED || target == JudgmentStatus.EXEMPT);
		if (!valid) {
			throw new ApiException(ErrorCode.JUDGMENT_NOT_CORRECTABLE);
		}
		if (target == JudgmentStatus.FAIL && judgmentTeamRepository.findByJudgmentId(judgment.getId()).isEmpty()) {
			throw new ApiException(ErrorCode.JUDGMENT_NOT_CORRECTABLE);
		}
	}

	// 미달로 정정하면 그 주 경고를 부여한다 (E-49). 이미 레코드가 있으면(삭제 표시 포함) 새로 만들지 않는다:
	// 같은 주 경고는 사람당 하나이고(UNIQUE), 삭제된 경고는 관리자 복구로만 되살린다 (E-60)
	private void ensureWarning(WeeklyJudgment judgment, LocalDateTime now) {
		if (warningRepository.findByMemberIdAndWeekStart(judgment.getMemberId(), judgment.getWeekStart()).isPresent()) {
			return;
		}
		List<JudgmentTeam> teams = judgmentTeamRepository.findByJudgmentId(judgment.getId());
		Warning warning = warningRepository
				.saveAndFlush(Warning.create(judgment.getMemberId(), judgment.getWeekStart(), now));
		for (JudgmentTeam team : teams) {
			warningTeamRepository.save(WarningTeam.of(warning.getId(), team.getTeamId()));
		}
	}

}
