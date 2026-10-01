package com.jandilog.team.service;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.jandilog.team.repository.TeamWarningRepository;
import com.jandilog.warning.domain.Warning;
import com.jandilog.warning.domain.WarningDeleteReason;
import com.jandilog.warning.domain.WarningTeam;
import com.jandilog.warning.repository.WarningRepository;
import com.jandilog.warning.service.WarningRecalculationService;

// 팀 변동이 경고에 미치는 영향 (기능명세서 6장, DB명세서 1-10·4-3·4-4).
// 추방·팀 삭제는 그 팀 카테고리만 빼고 남은 카테고리가 없으면 경고를 소프트 삭제하며, 아이디 재초대 복귀는 되살린다 (Q-02).
// 모든 메서드는 호출한 쪽 트랜잭션 안에서 회원 행을 잠근 뒤(lockMember) 바꾸고 recalculate로 끝낸다 (Q-08)
@Service
public class TeamWarningService {

	private final TeamWarningRepository teamWarningRepository;
	private final WarningRepository warningRepository;
	private final WarningRecalculationService recalculationService;

	public TeamWarningService(TeamWarningRepository teamWarningRepository, WarningRepository warningRepository,
			WarningRecalculationService recalculationService) {
		this.teamWarningRepository = teamWarningRepository;
		this.warningRepository = warningRepository;
		this.recalculationService = recalculationService;
	}

	// 추방: 그 회원 경고에서 이 팀 카테고리를 뺀다. 남은 카테고리가 없으면 KICKED로 소프트 삭제
	@Transactional(propagation = Propagation.MANDATORY)
	public void removeCategoryOnKick(long memberId, long teamId, LocalDateTime now) {
		recalculationService.lockMember(memberId);
		removeCategories(teamWarningRepository.findLiveCategories(teamId, memberId), WarningDeleteReason.KICKED, now);
		recalculationService.recalculate(memberId);
	}

	// 팀 삭제: 이 팀 카테고리가 붙은 모든 경고에서 뺀다(자진 탈퇴해 경고만 남은 사람 포함). 영향받은 회원 각각 재계산.
	// 회원 행 잠금은 id 오름차순으로 잡아 같은 시각의 다른 팀 삭제와 교착하지 않게 한다
	@Transactional(propagation = Propagation.MANDATORY)
	public void removeCategoriesOnTeamDelete(long teamId, LocalDateTime now) {
		List<Long> memberIds = teamWarningRepository.findMemberIdsWithLiveCategory(teamId).stream().sorted().toList();
		for (Long memberId : memberIds) {
			recalculationService.lockMember(memberId);
		}
		removeCategories(teamWarningRepository.findLiveCategoriesOfTeam(teamId), WarningDeleteReason.TEAM_DELETED, now);
		for (Long memberId : memberIds) {
			recalculationService.recalculate(memberId);
		}
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

	// 카테고리를 빼고, 경고마다 남은 카테고리를 세어 0이면 살아 있는 경고만 소프트 삭제한다
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
		for (Warning warning : warningRepository.findAllById(warningIds)) {
			if (warning.isAlive() && teamWarningRepository.countLiveCategories(warning.getId()) == 0) {
				warning.softDelete(reason, now);
			}
		}
		warningRepository.flush();
	}

}
