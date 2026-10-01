package com.jandilog.admin.service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
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
		boolean hasNext = rest.size() > CursorCodec.PAGE_SIZE;
		List<Target> page = hasNext ? rest.subList(0, CursorCodec.PAGE_SIZE) : rest;
		String nextCursor = hasNext ? CursorKey.encode(page.get(page.size() - 1)) : null;

		Map<Long, Member> members = memberRepository.findAllById(page.stream().map(Target::memberId).toList())
				.stream().collect(Collectors.toMap(Member::getId, Function.identity()));
		List<AdminPenaltyTarget> items = new ArrayList<>();
		for (Target target : page) {
			Member member = members.get(target.memberId());
			if (member != null) {
				items.add(new AdminPenaltyTarget(MemberBrief.from(member), target.warningCount(),
						target.reachedWeek().toString(), teamsOf(target.memberId())));
			}
		}
		return new AdminPenaltyTargetPage(items, nextCursor, all.size());
	}

	// 이행 체크: 경고 0 · 연속 통과 0이 되고 되돌릴 수 없다. 벌칙 대상이 아니면 NOT_PENALTY_TARGET
	public AdminPenaltyFulfillment fulfill(long adminId, String memberId) {
		long id = InputRules.id(memberId);
		Member member = memberRepository.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		PenaltyFulfillment fulfillment = fulfillmentService.fulfill(adminId, id);
		return new AdminPenaltyFulfillment(MemberBrief.from(member), KstFormats.of(fulfillment.getFulfilledAt()),
				fulfillment.getReachedWeek().toString());
	}

	// 지금 세는 경고(살아 있고 차감되지 않은 것)에 붙은 팀. 삭제된 팀 카테고리는 이미 빠져 있다
	private List<AdminWarningTeam> teamsOf(long memberId) {
		if (!(recalculationService.calculate(memberId) instanceof WarningRecalcResult.Calculated state)) {
			return List.of();
		}
		Set<LocalDate> activeWeeks = Set.copyOf(state.activeWarningWeeks());
		List<Long> warningIds = warningRepository.findByMemberIdAndDeletedAtIsNullOrderByWeekStartAsc(memberId)
				.stream()
				.filter(warning -> activeWeeks.contains(warning.getWeekStart()))
				.map(Warning::getId)
				.toList();
		if (warningIds.isEmpty()) {
			return List.of();
		}
		Set<Long> teamIds = new LinkedHashSet<>();
		for (WarningTeam category : warningTeamRepository.findByWarningIdIn(warningIds)) {
			if (!category.isRemoved()) {
				teamIds.add(category.getTeamId());
			}
		}
		Map<Long, TeamReadModel.TeamName> names = teamReadModel.findByIds(teamIds);
		return teamIds.stream()
				.filter(names::containsKey)
				.map(teamId -> new AdminWarningTeam(teamId, names.get(teamId).name()))
				.sorted((a, b) -> a.name().compareTo(b.name()))
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
