package com.jandilog.exemption.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.common.pagination.CursorCodec;
import com.jandilog.common.validation.InputRules;
import com.jandilog.exemption.domain.ExemptionPeriod;
import com.jandilog.exemption.domain.PersonalExemption;
import com.jandilog.exemption.domain.PersonalExemptionStatus;
import com.jandilog.exemption.dto.PersonalExemptionPage;
import com.jandilog.exemption.dto.PersonalExemptionResponse;
import com.jandilog.exemption.dto.RetroExemptionPreview;
import com.jandilog.exemption.repository.ExemptionPeriodRepository;
import com.jandilog.exemption.repository.PersonalExemptionRepository;
import com.jandilog.judgment.domain.SkipReason;
import com.jandilog.member.domain.Member;
import com.jandilog.member.dto.MemberBrief;
import com.jandilog.member.repository.MemberRepository;
import com.jandilog.team.query.TeamReadModel;
import com.jandilog.warning.service.WarningRecalculationService;

// 개인 면제: 팀장이 요청하고 관리자가 승인·거절한다 (기능명세서 7장 · DB명세서 1-13).
// 승인하면 회원 전체의 그 주 판정이 빠지고, 이미 판정된 주면 통과·미달이 면제로 바뀐다(소급, E-45).
// 같은 회원·주의 중복 요청 검사와 승인·거절은 회원 행 락 안에서 한다 (Q-08)
@Service
public class PersonalExemptionService {

	// TM-08 ③ 사유 200자 이내 (제안)
	static final int REQUEST_REASON_MAX_LENGTH = 200;
	static final int REJECT_REASON_MAX_LENGTH = 500;
	private static final Set<PersonalExemptionStatus> OPEN_STATUSES = EnumSet.of(PersonalExemptionStatus.PENDING,
			PersonalExemptionStatus.APPROVED);

	private final PersonalExemptionRepository exemptionRepository;
	private final ExemptionPeriodRepository periodRepository;
	private final MemberRepository memberRepository;
	private final TeamReadModel teams;
	private final WarningRecalculationService recalculationService;
	private final RetroactiveExemptionService retroactiveService;
	private final Clock clock;

	public PersonalExemptionService(PersonalExemptionRepository exemptionRepository,
			ExemptionPeriodRepository periodRepository, MemberRepository memberRepository, TeamReadModel teams,
			WarningRecalculationService recalculationService, RetroactiveExemptionService retroactiveService,
			Clock clock) {
		this.exemptionRepository = exemptionRepository;
		this.periodRepository = periodRepository;
		this.memberRepository = memberRepository;
		this.teams = teams;
		this.recalculationService = recalculationService;
		this.retroactiveService = retroactiveService;
		this.clock = clock;
	}

	// 그 팀의 팀장이 팀원(또는 본인)과 주간을 골라 요청한다. 관리자라도 남의 팀 팀장 자격은 없다 (기능명세서 9장)
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public PersonalExemptionResponse request(long requesterId, String teamId, String memberId, String weekStart,
			String reason) {
		long team = InputRules.id(teamId);
		long target = InputRules.id(memberId);
		LocalDate week = InputRules.weekStart(weekStart);
		String text = InputRules.reason(reason, REQUEST_REASON_MAX_LENGTH, ErrorCode.REASON_REQUIRED);

		long leaderId = teams.findLeaderIdOfAliveTeam(team).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (leaderId != requesterId) {
			throw new ApiException(ErrorCode.FORBIDDEN);
		}
		if (target != requesterId && !teams.isCurrentMember(team, target)) {
			throw new ApiException(ErrorCode.INVALID_INPUT);
		}

		recalculationService.lockMember(target);
		if (exemptionRepository.existsByMemberIdAndWeekStartAndStatusIn(target, week, OPEN_STATUSES)) {
			throw new ApiException(ErrorCode.EXEMPTION_REQUEST_DUPLICATE);
		}
		PersonalExemption saved = exemptionRepository
				.saveAndFlush(PersonalExemption.request(target, requesterId, week, text, LocalDateTime.now(clock)));
		return toResponses(List.of(saved)).get(0);
	}

	// 내가 보낸 요청 목록. 최신순 커서 20개 (TM-08 ⑤)
	@Transactional(readOnly = true)
	public PersonalExemptionPage listMine(long requesterId, String cursor) {
		long before = CursorCodec.decodeLong(cursor, Long.MAX_VALUE);
		List<PersonalExemption> rows = exemptionRepository.findByRequestedByAndIdLessThanOrderByIdDesc(requesterId,
				before, page());
		return toPage(rows);
	}

	// 관리자 목록. 기본은 대기 건, 상태별 최신순 커서 20개 (AD-01 면제 관리 ⑦)
	@Transactional(readOnly = true)
	public PersonalExemptionPage listForAdmin(PersonalExemptionStatus status, String cursor) {
		long before = CursorCodec.decodeLong(cursor, Long.MAX_VALUE);
		List<PersonalExemption> rows = exemptionRepository.findByStatusAndIdLessThanOrderByIdDesc(
				status == null ? PersonalExemptionStatus.PENDING : status, before, page());
		return toPage(rows);
	}

	// 대기 중인 요청 건수 (대시보드 요약)
	@Transactional(readOnly = true)
	public long countPending() {
		return exemptionRepository.countByStatus(PersonalExemptionStatus.PENDING);
	}

	// 승인하면 바뀌는 판정과 영향. 이미 판정된 주가 아니면 소급은 없다. 아무것도 바꾸지 않는다
	@Transactional(readOnly = true)
	public RetroExemptionPreview previewApproval(String id) {
		PersonalExemption exemption = exemptionRepository.findById(InputRules.id(id))
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (!exemption.isPending()) {
			throw new ApiException(ErrorCode.ALREADY_HANDLED);
		}
		return retroactiveService.previewMember(exemption.getMemberId(), exemption.getWeekStart());
	}

	@Transactional(isolation = Isolation.READ_COMMITTED)
	public PersonalExemptionResponse approve(String id) {
		PersonalExemption exemption = lockAndLoadPending(InputRules.id(id));
		exemption.approve(LocalDateTime.now(clock));
		retroactiveService.apply(exemption.getMemberId(), exemption.getWeekStart(), SkipReason.PERSONAL_EXEMPTION);
		return toResponses(List.of(exemption)).get(0);
	}

	// 거절은 사유 입력이 필수다 (E-48, 제안)
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public PersonalExemptionResponse reject(String id, String rejectReason) {
		String reason = InputRules.reason(rejectReason, REJECT_REASON_MAX_LENGTH, ErrorCode.REJECT_REASON_REQUIRED);
		PersonalExemption exemption = lockAndLoadPending(InputRules.id(id));
		exemption.reject(reason, LocalDateTime.now(clock));
		return toResponses(List.of(exemption)).get(0);
	}

	// 대상 회원 락을 먼저 잡고 요청을 읽는다. 락 뒤에 읽어야 다른 관리자가 먼저 처리한 결과가 보인다
	private PersonalExemption lockAndLoadPending(long id) {
		long memberId = exemptionRepository.findMemberIdById(id)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		recalculationService.lockMember(memberId);
		PersonalExemption exemption = exemptionRepository.findById(id)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (!exemption.isPending()) {
			throw new ApiException(ErrorCode.ALREADY_HANDLED);
		}
		return exemption;
	}

	private PersonalExemptionPage toPage(List<PersonalExemption> rows) {
		boolean hasNext = rows.size() > CursorCodec.PAGE_SIZE;
		List<PersonalExemption> page = hasNext ? rows.subList(0, CursorCodec.PAGE_SIZE) : rows;
		String nextCursor = hasNext ? CursorCodec.encode(page.get(page.size() - 1).getId()) : null;
		return new PersonalExemptionPage(toResponses(page), nextCursor);
	}

	// 대상·요청자 표시 정보와 면제 기간 겹침(E-44)을 목록 단위로 한 번에 읽는다
	private List<PersonalExemptionResponse> toResponses(List<PersonalExemption> rows) {
		Set<Long> memberIds = new HashSet<>();
		Set<LocalDate> weeks = new HashSet<>();
		for (PersonalExemption row : rows) {
			memberIds.add(row.getMemberId());
			memberIds.add(row.getRequestedBy());
			weeks.add(row.getWeekStart());
		}
		Map<Long, MemberBrief> members = memberRepository.findAllById(memberIds).stream()
				.collect(Collectors.toMap(Member::getId, MemberBrief::from));
		Set<LocalDate> periodWeeks = periodRepository.findByWeekStartIn(weeks).stream()
				.map(ExemptionPeriod::getWeekStart).collect(Collectors.toSet());
		return rows.stream()
				.map(row -> PersonalExemptionResponse.of(row, members.get(row.getMemberId()),
						members.get(row.getRequestedBy()), periodWeeks.contains(row.getWeekStart())))
				.toList();
	}

	private static Pageable page() {
		return PageRequest.of(0, CursorCodec.PAGE_SIZE + 1);
	}

}
