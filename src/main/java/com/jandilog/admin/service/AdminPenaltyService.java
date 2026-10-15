package com.jandilog.admin.service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jandilog.admin.dto.AdminPenaltyFulfillment;
import com.jandilog.admin.dto.AdminPenaltyTarget;
import com.jandilog.admin.dto.AdminPenaltyTargetPage;
import com.jandilog.admin.dto.AdminWarningTeam;
import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.common.pagination.CursorCodec;
import com.jandilog.common.pagination.PageSlice;
import com.jandilog.common.time.KstFormats;
import com.jandilog.common.validation.InputRules;
import com.jandilog.member.domain.Member;
import com.jandilog.member.dto.MemberBrief;
import com.jandilog.member.repository.MemberRepository;
import com.jandilog.team.query.TeamReadModel;
import com.jandilog.warning.domain.PenaltyFulfillment;
import com.jandilog.warning.domain.Warning;
import com.jandilog.warning.domain.WarningRecalcResult;
import com.jandilog.warning.domain.WarningTeam;
import com.jandilog.warning.repository.WarningRepository;
import com.jandilog.warning.repository.WarningTeamRepository;
import com.jandilog.warning.service.PenaltyFulfillmentService;
import com.jandilog.warning.service.PenaltyTargetService;
import com.jandilog.warning.service.PenaltyTargetService.Target;
import com.jandilog.warning.service.WarningRecalculationService;

// 경고 복구 · 벌칙 탭의 벌칙 관리 (기능명세서 6·9장, AD-01 ⑤⑥): 벌칙 대상 목록과 이행 체크.
// 이 화면에서만 비공개 팀 이름을 가리지 않는다 (경고가 어느 팀에서 나왔는지 알아야 벌칙을 통보할 수 있다)
@Service
public class AdminPenaltyService {

	private final PenaltyTargetService targetService;
	private final PenaltyFulfillmentService fulfillmentService;
	private final WarningRecalculationService recalculationService;
	private final WarningRepository warningRepository;
	private final WarningTeamRepository warningTeamRepository;
	private final MemberRepository memberRepository;
	private final TeamReadModel teamReadModel;

	public AdminPenaltyService(PenaltyTargetService targetService, PenaltyFulfillmentService fulfillmentService,
			WarningRecalculationService recalculationService, WarningRepository warningRepository,
			WarningTeamRepository warningTeamRepository, MemberRepository memberRepository,
			TeamReadModel teamReadModel) {
		this.targetService = targetService;
		this.fulfillmentService = fulfillmentService;
		this.recalculationService = recalculationService;
		this.warningRepository = warningRepository;
		this.warningTeamRepository = warningTeamRepository;
		this.memberRepository = memberRepository;
		this.teamReadModel = teamReadModel;
	}

	// 도달한 주가 오래된 순 커서 20명. 커서는 마지막 행의 (도달 주, 회원 id)
	@Transactional(readOnly = true)
	public AdminPenaltyTargetPage list(String cursor) {
		List<Target> all = targetService.findTargets();
		CursorKey after = CursorKey.decode(cursor);
		List<Target> rest = after == null ? all : all.stream().filter(target -> after.isBefore(target)).toList();
		PageSlice<Target> page = PageSlice.of(rest, CursorKey::encode);

		Map<Long, Member> members = memberRepository.findAllById(page.rows().stream().map(Target::memberId).toList())
				.stream().collect(Collectors.toMap(Member::getId, Function.identity()));
		Map<Long, List<AdminWarningTeam>> teams = teamsOf(members.keySet());
		List<AdminPenaltyTarget> items = new ArrayList<>();
		for (Target target : page.rows()) {
			Member member = members.get(target.memberId());
			if (member != null) {
				items.add(new AdminPenaltyTarget(MemberBrief.from(member), target.warningCount(),
						target.reachedWeek().toString(), teams.getOrDefault(target.memberId(), List.of())));
			}
		}
		return new AdminPenaltyTargetPage(items, page.nextCursor(), all.size());
	}

	// 이행 체크: 경고 0 · 연속 통과 0이 되고 되돌릴 수 없다. 벌칙 대상이 아니면 NOT_PENALTY_TARGET
	public AdminPenaltyFulfillment fulfill(long adminId, String memberId) {
		long id = InputRules.id(memberId);
		Member member = memberRepository.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		PenaltyFulfillment fulfillment = fulfillmentService.fulfill(adminId, id);
		return new AdminPenaltyFulfillment(MemberBrief.from(member), KstFormats.of(fulfillment.getFulfilledAt()),
				fulfillment.getReachedWeek().toString());
	}

	// 회원별로 지금 세는 경고(살아 있고 차감되지 않은 것)에 붙은 팀. 삭제된 팀 카테고리는 이미 빠져 있다.
	// 한 페이지 회원을 재계산·경고·카테고리·팀 이름 순으로 한 번씩만 읽는다. 팀이 없거나 보류 중인 회원은 결과에 없다
	private Map<Long, List<AdminWarningTeam>> teamsOf(Collection<Long> memberIds) {
		Map<Long, Long> memberByWarning = new HashMap<>();
		for (Warning warning : activeWarnings(memberIds)) {
			memberByWarning.put(warning.getId(), warning.getMemberId());
		}
		if (memberByWarning.isEmpty()) {
			return Map.of();
		}
		Map<Long, Set<Long>> teamIdsByMember = new HashMap<>();
		for (WarningTeam category : warningTeamRepository.findByWarningIdIn(memberByWarning.keySet())) {
			if (!category.isRemoved()) {
				teamIdsByMember.computeIfAbsent(memberByWarning.get(category.getWarningId()), id -> new LinkedHashSet<>())
						.add(category.getTeamId());
			}
		}
		Map<Long, TeamReadModel.TeamName> names = teamReadModel
				.findByIds(teamIdsByMember.values().stream().flatMap(Set::stream).collect(Collectors.toSet()));
		Map<Long, List<AdminWarningTeam>> result = new HashMap<>();
		teamIdsByMember.forEach((memberId, teamIds) -> result.put(memberId, teamIds.stream()
				.filter(names::containsKey)
				.map(teamId -> new AdminWarningTeam(teamId, names.get(teamId).name()))
				.sorted((a, b) -> a.name().compareTo(b.name()))
				.toList()));
		return result;
	}

	// 살아 있는 경고 중 재계산에서 아직 세는 주의 것. 보류 중인 회원은 셀 수 없어 뺀다
	private List<Warning> activeWarnings(Collection<Long> memberIds) {
		if (memberIds.isEmpty()) {
			return List.of();
		}
		Map<Long, Set<LocalDate>> activeWeeks = new HashMap<>();
		recalculationService.calculateAll(memberIds).forEach((memberId, result) -> {
			if (result instanceof WarningRecalcResult.Calculated state) {
				activeWeeks.put(memberId, Set.copyOf(state.activeWarningWeeks()));
			}
		});
		if (activeWeeks.isEmpty()) {
			return List.of();
		}
		return warningRepository.findByMemberIdInAndDeletedAtIsNull(activeWeeks.keySet()).stream()
				.filter(warning -> activeWeeks.get(warning.getMemberId()).contains(warning.getWeekStart()))
				.toList();
	}

	// "도달 주:회원 id"를 Base64url로 감싼 커서. 정렬은 PenaltyTargetService와 같다 (도달 주, 회원 id)
	private record CursorKey(LocalDate reachedWeek, long memberId) {

		static String encode(Target target) {
			return CursorCodec.encode(target.reachedWeek() + ":" + target.memberId());
		}

		static CursorKey decode(String cursor) {
			String raw = CursorCodec.decode(cursor);
			if (raw == null) {
				return null;
			}
			try {
				int split = raw.indexOf(':');
				return new CursorKey(LocalDate.parse(raw.substring(0, split)), Long.parseLong(raw.substring(split + 1)));
			}
			catch (DateTimeParseException | NumberFormatException | IndexOutOfBoundsException e) {
				throw new ApiException(ErrorCode.INVALID_INPUT);
			}
		}

		boolean isBefore(Target target) {
			int byWeek = reachedWeek.compareTo(target.reachedWeek());
			return byWeek < 0 || (byWeek == 0 && memberId < target.memberId());
		}

	}

}
