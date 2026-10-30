package com.jandilog.team.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.jandilog.team.domain.Team;
import com.jandilog.team.domain.TeamJoinedBy;
import com.jandilog.team.domain.TeamLeaveType;
import com.jandilog.team.domain.TeamMember;
import com.jandilog.team.repository.TeamMemberRepository;
import com.jandilog.team.repository.TeamRepository;
import com.jandilog.team.repository.TeamWarningRepository;
import com.jandilog.warning.domain.Warning;
import com.jandilog.warning.domain.WarningDeleteReason;
import com.jandilog.warning.domain.WarningTeam;
import com.jandilog.warning.repository.WarningRepository;
import com.jandilog.warning.service.WarningRecalculationService;

// 팀 변동이 경고에 미치는 영향 (기능명세서 6장, DB명세서 1-10·4-3·4-4).
// 추방·팀 삭제는 그 팀 카테고리만 빼고 남은 카테고리가 없으면 경고를 소프트 삭제하며, 아이디 재초대 복귀는 되살린다 (Q-02).
// 팀 변동 메서드는 호출한 쪽 트랜잭션 안에서 회원 행을 잠근 뒤(lockMember) 바꾸고 recalculate로 끝낸다 (Q-08).
// 늦게 만든 경고에 변동을 반영하는 applyTeamChangesSince는 이미 락을 잡은 판정 저장·정정이 부르고, 재계산은 그쪽이 한다
@Service
public class TeamWarningService {

	private final TeamWarningRepository teamWarningRepository;
	private final WarningRepository warningRepository;
	private final WarningRecalculationService recalculationService;
	private final TeamRepository teamRepository;
	private final TeamMemberRepository teamMemberRepository;

	public TeamWarningService(TeamWarningRepository teamWarningRepository, WarningRepository warningRepository,
			WarningRecalculationService recalculationService, TeamRepository teamRepository,
			TeamMemberRepository teamMemberRepository) {
		this.teamWarningRepository = teamWarningRepository;
		this.warningRepository = warningRepository;
		this.recalculationService = recalculationService;
		this.teamRepository = teamRepository;
		this.teamMemberRepository = teamMemberRepository;
	}

	// 소속 스냅샷 시각(snapshotAt) 뒤에 만든 경고(월요일 판정 전 변동, 보류 재실행, 기동 시 보충, 미달 정정)에 그 사이 팀 변동을 반영한다.
	// 경고가 그때 있었다면 추방·팀 삭제가 뺐을 카테고리를 빼고, 아이디 재초대 복귀가 되살렸을 카테고리는 남긴다.
	// 남은 카테고리가 없으면 마지막으로 뺀 사유와 시각으로 소프트 삭제한다. 호출한 쪽이 회원 락을 잡은 트랜잭션 안에서 부른다
	@Transactional(propagation = Propagation.MANDATORY)
	public void applyTeamChangesSince(Warning warning, List<WarningTeam> categories, LocalDateTime snapshotAt) {
		if (categories.isEmpty()) {
			return;
		}
		Map<Long, Removal> removals = removalsSince(warning.getMemberId(),
				categories.stream().map(WarningTeam::getTeamId).toList(), snapshotAt);

		Removal last = null;
		boolean anyLive = false;
		for (WarningTeam category : categories) {
			Removal removal = removals.get(category.getTeamId());
			if (removal == null) {
				anyLive = true;
				continue;
			}
			category.remove(removal.at());
			if (last == null || removal.at().isAfter(last.at())) {
				last = removal;
			}
		}
		if (!anyLive && last != null && warning.isAlive()) {
			warning.softDelete(last.reason(), last.at());
		}
		warningRepository.flush();
	}

	// applyTeamChangesSince의 결과를 미리 본다: 이 팀들을 카테고리로 지금 경고를 만들면 남는 카테고리가 없는가 (정정 미리보기)
	@Transactional(readOnly = true)
	public boolean wouldRemoveAllCategories(long memberId, Collection<Long> teamIds, LocalDateTime snapshotAt) {
		return !teamIds.isEmpty() && removalsSince(memberId, teamIds, snapshotAt).keySet().containsAll(teamIds);
	}

	// 팀 id별로 스냅샷 뒤 빠져 있어야 하는 카테고리의 마지막 제거. 남아 있어야 하는 팀은 결과에 없다
	private Map<Long, Removal> removalsSince(long memberId, Collection<Long> teamIds, LocalDateTime snapshotAt) {
		Map<Long, List<TeamMember>> memberships = teamMemberRepository.findByMemberIdAndTeamIdIn(memberId, teamIds)
				.stream().collect(Collectors.groupingBy(TeamMember::getTeamId));
		Map<Long, Team> teams = teamRepository.findByIdIn(teamIds).stream()
				.collect(Collectors.toMap(Team::getId, Function.identity()));
		Map<Long, Removal> removals = new HashMap<>();
		for (Long teamId : teamIds) {
			Removal removal = removalSince(memberships.getOrDefault(teamId, List.of()), teams.get(teamId), snapshotAt);
			if (removal != null) {
				removals.put(teamId, removal);
			}
		}
		return removals;
	}

	// 스냅샷 뒤 그 팀 카테고리가 지금 빠져 있어야 하면 마지막으로 뺀 시각과 사유, 아니면 null.
	// 추방은 빼고 아이디 초대 수락은 되살리며(Q-02), 팀 삭제는 되돌릴 수 없다
	private static Removal removalSince(List<TeamMember> memberships, Team team, LocalDateTime snapshotAt) {
		if (team != null && team.isDeleted()) {
			return new Removal(team.getDeletedAt(), WarningDeleteReason.TEAM_DELETED);
		}
		List<Removal> events = new ArrayList<>();
		for (TeamMember membership : memberships) {
			if (membership.getJoinedBy() == TeamJoinedBy.INVITATION && membership.getJoinedAt().isAfter(snapshotAt)) {
				// 되살림은 사유 없이 표시한다
				events.add(new Removal(membership.getJoinedAt(), null));
			}
			if (membership.getLeaveType() == TeamLeaveType.KICKED && membership.getLeftAt().isAfter(snapshotAt)) {
				events.add(new Removal(membership.getLeftAt(), WarningDeleteReason.KICKED));
			}
		}
		events.sort(Comparator.comparing(Removal::at));
		Removal current = null;
		for (Removal event : events) {
			current = event.reason() == null ? null : event;
		}
		return current;
	}

	private record Removal(LocalDateTime at, WarningDeleteReason reason) {
	}

	// 추방: 그 회원 경고에서 이 팀 카테고리를 뺀다. 남은 카테고리가 없으면 KICKED로 소프트 삭제
	@Transactional(propagation = Propagation.MANDATORY)
	public void removeCategoryOnKick(long memberId, long teamId, LocalDateTime now) {
		recalculationService.lockMember(memberId);
		removeCategories(teamWarningRepository.findLiveCategories(teamId, memberId), WarningDeleteReason.KICKED, now);
		recalculationService.recalculate(memberId);
	}

	// 팀 삭제: 이 팀 카테고리가 붙은 모든 경고에서 뺀다(자진 탈퇴해 경고만 남은 사람 포함). 영향받은 회원을 한 번에 재계산.
	// 회원 행 잠금은 id 오름차순(lockMembers)으로 잡아 같은 시각의 다른 팀 삭제와 교착하지 않게 한다
	@Transactional(propagation = Propagation.MANDATORY)
	public void removeCategoriesOnTeamDelete(long teamId, LocalDateTime now) {
		List<Long> memberIds = teamWarningRepository.findMemberIdsWithLiveCategory(teamId);
		recalculationService.lockMembers(memberIds);
		removeCategories(teamWarningRepository.findLiveCategoriesOfTeam(teamId), WarningDeleteReason.TEAM_DELETED, now);
		recalculationService.recalculateAll(memberIds);
	}

	// 추방자가 아이디 초대로 돌아오면 이 팀 카테고리를 되살리고 경고의 삭제 표시를 푼다 (E-14, Q-02)
	@Transactional(propagation = Propagation.MANDATORY)
	public void restoreCategoriesOnReturn(long memberId, long teamId) {
		recalculationService.lockMember(memberId);
		List<WarningTeam> removed = teamWarningRepository.findRemovedCategories(teamId, memberId);
		if (!removed.isEmpty()) {
			Set<Long> warningIds = new LinkedHashSet<>();
			for (WarningTeam category : removed) {
				category.restore();
				warningIds.add(category.getWarningId());
			}
			for (Warning warning : warningRepository.findAllById(warningIds)) {
				if (!warning.isAlive()) {
					warning.restore();
				}
			}
			warningRepository.flush();
		}
		recalculationService.recalculate(memberId);
	}

	// 카테고리를 빼고, 남은 카테고리가 없는 살아 있는 경고만 소프트 삭제한다. 남은 카테고리는 경고 전체를 한 번에 확인한다
	private void removeCategories(List<WarningTeam> categories, WarningDeleteReason reason, LocalDateTime now) {
		if (categories.isEmpty()) {
			return;
		}
		Set<Long> warningIds = new LinkedHashSet<>();
		for (WarningTeam category : categories) {
			category.remove(now);
			warningIds.add(category.getWarningId());
		}
		warningRepository.flush();
		List<Warning> warnings = warningRepository.findAllById(warningIds);
		Set<Long> stillCategorized = new HashSet<>(teamWarningRepository.findWarningIdsWithLiveCategory(warningIds));
		for (Warning warning : warnings) {
			if (warning.isAlive() && !stillCategorized.contains(warning.getId())) {
				warning.softDelete(reason, now);
			}
		}
		warningRepository.flush();
	}

}
