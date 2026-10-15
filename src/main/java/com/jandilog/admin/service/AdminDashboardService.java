package com.jandilog.admin.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jandilog.admin.dto.AdminDashboardSummary;
import com.jandilog.admin.dto.AdminHold;
import com.jandilog.admin.dto.AdminHoldPage;
import com.jandilog.admin.dto.AdminJudgmentFilter;
import com.jandilog.admin.dto.AdminJudgmentPage;
import com.jandilog.admin.dto.AdminJudgmentRow;
import com.jandilog.admin.repository.AdminJudgmentQueryRepository;
import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.common.pagination.CursorCodec;
import com.jandilog.common.time.KstFormats;
import com.jandilog.common.validation.InputRules;
import com.jandilog.exemption.domain.PersonalExemptionStatus;
import com.jandilog.exemption.repository.PersonalExemptionRepository;
import com.jandilog.judgment.domain.HoldReason;
import com.jandilog.judgment.domain.JudgmentStatus;
import com.jandilog.judgment.domain.JudgmentWeek;
import com.jandilog.judgment.domain.SkipReason;
import com.jandilog.judgment.domain.WeeklyJudgment;
import com.jandilog.member.domain.Member;
import com.jandilog.member.domain.MemberStatus;
import com.jandilog.member.dto.MemberBrief;
import com.jandilog.member.repository.MemberRepository;
import com.jandilog.warning.domain.WarningRecalcResult;
import com.jandilog.warning.service.PenaltyTargetService;
import com.jandilog.warning.service.WarningRecalculationService;

// 관리자 대시보드 조회 (기능명세서 9장, AD-01 ⑤~⑨): 처리 대기 요약, 주차별 판정 결과, 보류 목록.
// 조회만 하고 처리(보류 재시도·판정 정정)는 AdminHoldService·JudgmentCorrectionService가 맡는다
@Service
public class AdminDashboardService {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private final AdminJudgmentQueryRepository judgmentQueryRepository;
	private final MemberRepository memberRepository;
	private final PersonalExemptionRepository exemptionRepository;
	private final PenaltyTargetService penaltyTargetService;
	private final WarningRecalculationService recalculationService;
	private final Clock clock;

	public AdminDashboardService(AdminJudgmentQueryRepository judgmentQueryRepository,
			MemberRepository memberRepository, PersonalExemptionRepository exemptionRepository,
			PenaltyTargetService penaltyTargetService, WarningRecalculationService recalculationService,
			Clock clock) {
		this.judgmentQueryRepository = judgmentQueryRepository;
		this.memberRepository = memberRepository;
		this.exemptionRepository = exemptionRepository;
		this.penaltyTargetService = penaltyTargetService;
		this.recalculationService = recalculationService;
		this.clock = clock;
	}

	// 이번 월요일 판정의 대상 주 = 직전 주 월요일. 판정 결과의 기본 주차이고 "이번 주 미달"이 세는 주다
	public LocalDate judgedWeekStart() {
		return JudgmentWeek.mondayOf(LocalDate.now(clock.withZone(KST))).minusWeeks(1);
	}

	// 승인 대기 / 이번 주 미달 / 벌칙 대상 / 면제 요청 대기 (AD-01 ⑤). 메뉴 배지와 같은 값이다
	@Transactional(readOnly = true)
	public AdminDashboardSummary summary() {
		LocalDate week = judgedWeekStart();
		return new AdminDashboardSummary(
				week.toString(),
				(int) memberRepository.countApproval(List.of(MemberStatus.PENDING), "%"),
				(int) judgmentQueryRepository.countByWeekAndStatus(week, JudgmentStatus.FAIL),
				penaltyTargetService.findTargets().size(),
				(int) exemptionRepository.countByStatus(PersonalExemptionStatus.PENDING));
	}

	// 주차 선택 · 회원 검색 · 결과 필터 + 집계 + 회원별 결과 (AD-01 ⑥⑦⑧). 주차를 생략하면 직전 주
	@Transactional(readOnly = true)
	public AdminJudgmentPage judgments(String weekStart, String keyword, AdminJudgmentFilter filter, String cursor) {
		LocalDate week = weekStart == null || weekStart.isBlank() ? judgedWeekStart() : InputRules.weekStart(weekStart);
		AdminJudgmentFilter effective = filter == null ? AdminJudgmentFilter.ALL : filter;
		long afterId = CursorCodec.decodeLong(cursor, 0L);
		if (afterId < 0) {
			throw new ApiException(ErrorCode.INVALID_INPUT);
		}

		// 한 건 더 읽어서 다음 페이지가 있는지 판단한다
		List<WeeklyJudgment> rows = judgmentQueryRepository.findResultPage(week, effective.statuses(),
				InputRules.likePattern(InputRules.keyword(keyword)), afterId, PageRequest.of(0, CursorCodec.PAGE_SIZE + 1));
		boolean hasNext = rows.size() > CursorCodec.PAGE_SIZE;
		List<WeeklyJudgment> page = hasNext ? rows.subList(0, CursorCodec.PAGE_SIZE) : rows;
		String nextCursor = hasNext ? CursorCodec.encode(page.get(page.size() - 1).getId()) : null;

		Map<Long, Member> members = memberRepository
				.findAllById(page.stream().map(WeeklyJudgment::getMemberId).toList()).stream()
				.collect(Collectors.toMap(Member::getId, Function.identity()));
		List<AdminJudgmentRow> items = page.stream()
				.filter(judgment -> members.containsKey(judgment.getMemberId()))
				.map(judgment -> toRow(judgment, members.get(judgment.getMemberId())))
				.toList();

		return new AdminJudgmentPage(
				week.toString(),
				week.plusDays(6).toString(),
				KstFormats.of(judgmentQueryRepository.findLastJudgedAt(week)),
				(int) judgmentQueryRepository.countByWeekAndStatus(week, JudgmentStatus.PASS),
				(int) judgmentQueryRepository.countByWeekAndStatus(week, JudgmentStatus.FAIL),
				(int) judgmentQueryRepository.countByWeekAndStatus(week, JudgmentStatus.EXEMPT),
				(int) judgmentQueryRepository.countByWeekAndSkipReason(week, JudgmentStatus.EXCLUDED,
						SkipReason.FIRST_WEEK),
				(int) judgmentQueryRepository.countManualHoldsOfWeek(week, JudgmentStatus.HOLD,
						HoldReason.IDENTITY_MISMATCH, WeeklyJudgment.MAX_AUTO_RETRIES),
				items, nextCursor);
	}

	// 모든 주의 사람이 봐야 하는 보류. 자동 재시도가 남은 건은 올리지 않는다 (AD-01 ⑨)
	@Transactional(readOnly = true)
	public AdminHoldPage holds(String cursor) {
		long afterId = CursorCodec.decodeLong(cursor, 0L);
		if (afterId < 0) {
			throw new ApiException(ErrorCode.INVALID_INPUT);
		}
		List<WeeklyJudgment> rows = judgmentQueryRepository.findManualHoldPage(JudgmentStatus.HOLD,
				HoldReason.IDENTITY_MISMATCH, WeeklyJudgment.MAX_AUTO_RETRIES, afterId,
				PageRequest.of(0, CursorCodec.PAGE_SIZE + 1));
		boolean hasNext = rows.size() > CursorCodec.PAGE_SIZE;
		List<WeeklyJudgment> page = hasNext ? rows.subList(0, CursorCodec.PAGE_SIZE) : rows;
		String nextCursor = hasNext ? CursorCodec.encode(page.get(page.size() - 1).getId()) : null;

		Map<Long, Member> members = memberRepository
				.findAllById(page.stream().map(WeeklyJudgment::getMemberId).toList()).stream()
				.collect(Collectors.toMap(Member::getId, Function.identity()));
		List<AdminHold> items = page.stream()
				.filter(judgment -> members.containsKey(judgment.getMemberId()))
				.map(judgment -> new AdminHold(MemberBrief.from(members.get(judgment.getMemberId())),
						judgment.getWeekStart().toString(), judgment.getHoldReason(), judgment.getRetryCount(),
						judgment.getRetryCount() >= WeeklyJudgment.MAX_AUTO_RETRIES))
				.toList();
		return new AdminHoldPage(items, nextCursor,
				(int) judgmentQueryRepository.countManualHolds(JudgmentStatus.HOLD, HoldReason.IDENTITY_MISMATCH,
						WeeklyJudgment.MAX_AUTO_RETRIES),
				(int) judgmentQueryRepository.countIdentityMismatchHolds(JudgmentStatus.HOLD,
						HoldReason.IDENTITY_MISMATCH));
	}

	private AdminJudgmentRow toRow(WeeklyJudgment judgment, Member member) {
		Integer warningCount = recalculationService.calculate(member.getId()) instanceof WarningRecalcResult.Calculated calculated
				? calculated.warningCount() : null;
		return new AdminJudgmentRow(judgment.getId(), MemberBrief.from(member), judgment.getStatus(),
				judgment.getSkipReason(), judgment.getHoldReason(), judgment.getVerifiedDays(),
				judgment.getRecordCount(), warningCount, judgment.isCorrected(), isManualHold(judgment));
	}

	private static boolean isManualHold(WeeklyJudgment judgment) {
		return judgment.getStatus() == JudgmentStatus.HOLD && !judgment.isAutoRetryPending();
	}

}
