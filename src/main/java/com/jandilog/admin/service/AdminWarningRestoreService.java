package com.jandilog.admin.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.jandilog.admin.domain.AdminActionLog;
import com.jandilog.admin.domain.AdminActionType;
import com.jandilog.admin.dto.AdminDeletedWarning;
import com.jandilog.admin.dto.AdminDeletedWarningPage;
import com.jandilog.admin.dto.AdminRestoredWarning;
import com.jandilog.admin.dto.AdminWarningRestorePreview;
import com.jandilog.admin.dto.AdminWarningTeam;
import com.jandilog.admin.repository.AdminActionLogRepository;
import com.jandilog.admin.repository.AdminWarningQueryRepository;
import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.common.pagination.CursorCodec;
import com.jandilog.common.time.KstFormats;
import com.jandilog.common.validation.InputRules;
import com.jandilog.judgment.domain.JudgmentStatus;
import com.jandilog.member.domain.Member;
import com.jandilog.member.dto.MemberBrief;
import com.jandilog.member.repository.MemberRepository;
import com.jandilog.team.query.TeamReadModel;
import com.jandilog.warning.domain.Warning;
import com.jandilog.warning.domain.WarningTeam;
import com.jandilog.warning.dto.RecalcImpact;
import com.jandilog.warning.repository.WarningRepository;
import com.jandilog.warning.repository.WarningTeamRepository;
import com.jandilog.warning.service.RecalcImpactService;
import com.jandilog.warning.service.WarningRecalculationService;

// 경고 복구 (기능명세서 6·9장, AD-01 ⑦⑧, E-42 · E-43 · E-59). 삭제 표시만 풀고 실제로 지우지 않았던 경고를 되살린다.
// 복구하면 삭제 표시를 풀고, 아직 있는 팀의 카테고리는 되살리고, 팀이 없어진 카테고리는 되살리지 않아 "삭제된 팀"으로 표시된다.
// 경고 수는 저장하지 않으므로 반영 뒤 회원 락 안에서 다시 계산한다. 벌칙 이행 이전 주의 경고는 기록만 되살아난다(재계산이 기준선으로 거른다)
@Service
public class AdminWarningRestoreService {

	private static final String TARGET_TYPE = "WARNING";

	private final AdminWarningQueryRepository queryRepository;
	private final WarningRepository warningRepository;
	private final WarningTeamRepository warningTeamRepository;
	private final AdminActionLogRepository actionLogRepository;
	private final MemberRepository memberRepository;
	private final WarningRecalculationService recalculationService;
	private final RecalcImpactService impactService;
	private final TeamReadModel teamReadModel;
	private final Clock clock;

	public AdminWarningRestoreService(AdminWarningQueryRepository queryRepository,
			WarningRepository warningRepository, WarningTeamRepository warningTeamRepository,
			AdminActionLogRepository actionLogRepository, MemberRepository memberRepository,
			WarningRecalculationService recalculationService, RecalcImpactService impactService,
			TeamReadModel teamReadModel, Clock clock) {
		this.queryRepository = queryRepository;
		this.warningRepository = warningRepository;
		this.warningTeamRepository = warningTeamRepository;
		this.actionLogRepository = actionLogRepository;
		this.memberRepository = memberRepository;
		this.recalculationService = recalculationService;
		this.impactService = impactService;
		this.teamReadModel = teamReadModel;
		this.clock = clock;
	}

	// 삭제된 경고 목록: 삭제가 최근인 순 커서 20개. 정정 · 소급 면제로 회수된 경고는 나오지 않는다
	@Transactional(readOnly = true)
	public AdminDeletedWarningPage list(String cursor) {
		long before = CursorCodec.decodeLong(cursor, Long.MAX_VALUE);
		if (before <= 0) {
			throw new ApiException(ErrorCode.INVALID_INPUT);
		}
		List<Warning> rows = queryRepository.findRestorablePage(JudgmentStatus.FAIL, before,
				PageRequest.of(0, CursorCodec.PAGE_SIZE + 1));
		boolean hasNext = rows.size() > CursorCodec.PAGE_SIZE;
		List<Warning> page = hasNext ? rows.subList(0, CursorCodec.PAGE_SIZE) : rows;
		String nextCursor = hasNext ? CursorCodec.encode(page.get(page.size() - 1).getId()) : null;

		Map<Long, Member> members = memberRepository.findAllById(page.stream().map(Warning::getMemberId).toList())
				.stream().collect(Collectors.toMap(Member::getId, Function.identity()));
		List<AdminDeletedWarning> items = new ArrayList<>();
		for (Warning warning : page) {
			Member member = members.get(warning.getMemberId());
			if (member == null) {
				continue;
			}
			List<AdminWarningTeam> teams = restoreTeams(warning.getId());
			items.add(new AdminDeletedWarning(warning.getId(), MemberBrief.from(member),
					warning.getWeekStart().toString(), warning.getDeleteReason(),
					KstFormats.of(warning.getDeletedAt()), teams, teams.isEmpty()));
		}
		return new AdminDeletedWarningPage(items, nextCursor);
	}

	// 복구하면 경고 수가 몇 개에서 몇 개로 바뀌는지, 벌칙 대상에 다시 들어가는지를 먼저 보여준다. 아무것도 바꾸지 않는다
	@Transactional(readOnly = true)
	public AdminWarningRestorePreview preview(String warningId) {
		long id = InputRules.id(warningId);
		Warning warning = warningRepository.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		requireRestorable(warning);
		Member member = memberRepository.findById(warning.getMemberId()).orElseThrow();
		List<AdminWarningTeam> teams = restoreTeams(id);
		RecalcImpact impact = impactService.ofWarningRestore(warning.getMemberId(), warning.getWeekStart());
		return new AdminWarningRestorePreview(id, MemberBrief.from(member), warning.getWeekStart().toString(), teams,
				teams.isEmpty(), impact);
	}

	// 회원 락을 먼저 잡고 경고를 읽는다. 다른 관리자가 먼저 복구했으면 ALREADY_HANDLED
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public AdminRestoredWarning restore(long adminId, String warningId) {
		long id = InputRules.id(warningId);
		long memberId = queryRepository.findMemberIdById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		recalculationService.lockMember(memberId);
		Warning warning = warningRepository.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (warning.isAlive()) {
			throw new ApiException(ErrorCode.ALREADY_HANDLED);
		}
		requireRestorable(warning);

		warning.restore();
		List<WarningTeam> categories = warningTeamRepository.findByWarningId(id);
		Set<Long> aliveTeamIds = aliveTeamIds(categories);
		for (WarningTeam category : categories) {
			if (category.isRemoved() && aliveTeamIds.contains(category.getTeamId())) {
				category.restore();
			}
		}
		actionLogRepository.save(AdminActionLog.of(adminId, AdminActionType.RESTORE_WARNING, TARGET_TYPE,
				Long.toString(id), null, LocalDateTime.now(clock)));

		Member member = memberRepository.findById(memberId).orElseThrow();
		return AdminRestoredWarning.of(id, MemberBrief.from(member), warning.getWeekStart().toString(),
				recalculationService.recalculate(memberId));
	}

	// 삭제 표시가 있고 그 주 판정이 미달인 경고만 복구 대상이다
	private void requireRestorable(Warning warning) {
		if (warning.isAlive()) {
			throw new ApiException(ErrorCode.ALREADY_HANDLED);
		}
		if (queryRepository.countRestorable(warning.getId(), JudgmentStatus.FAIL) == 0) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
	}

	// 복구하면 되살아나는 카테고리: 아직 있는 팀 것만
	private List<AdminWarningTeam> restoreTeams(long warningId) {
		List<WarningTeam> categories = warningTeamRepository.findByWarningId(warningId);
		Map<Long, TeamReadModel.TeamName> names = teamReadModel
				.findByIds(categories.stream().map(WarningTeam::getTeamId).toList());
		return categories.stream()
				.map(WarningTeam::getTeamId)
				.filter(teamId -> names.containsKey(teamId) && !names.get(teamId).deleted())
				.map(teamId -> new AdminWarningTeam(teamId, names.get(teamId).name()))
				.sorted((a, b) -> a.name().compareTo(b.name()))
				.toList();
	}

	private Set<Long> aliveTeamIds(List<WarningTeam> categories) {
		Map<Long, TeamReadModel.TeamName> names = teamReadModel
				.findByIds(categories.stream().map(WarningTeam::getTeamId).toList());
		return names.values().stream()
				.filter(team -> !team.deleted())
				.map(TeamReadModel.TeamName::id)
				.collect(Collectors.toSet());
	}

}
