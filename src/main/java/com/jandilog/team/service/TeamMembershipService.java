package com.jandilog.team.service;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jandilog.team.domain.TeamMember;
import com.jandilog.team.repository.TeamMemberRepository;

// 소속 스냅샷용 조회 (판정 2단계가 쓴다). "주 종료 시각의 소속"을 team_member 이력으로 되짚는다 (DB명세서 1-3, 4-2):
// 그 시각 이전에 참가했고(joined_at <= at), 나간 시각이 없거나 그 시각 이후인(left_at > at) 팀.
// 팀이 삭제되면 소속을 TEAM_DELETED로 닫으므로 삭제 시각 이후 시점에는 자연히 빠진다
@Service
public class TeamMembershipService {

	private final TeamMemberRepository teamMemberRepository;

	public TeamMembershipService(TeamMemberRepository teamMemberRepository) {
		this.teamMemberRepository = teamMemberRepository;
	}

	// 한 회원의 기준 시각 소속 팀 id (오름차순)
	@Transactional(readOnly = true)
	public List<Long> teamIdsAt(long memberId, LocalDateTime at) {
		return teamMemberRepository.findTeamIdsAt(memberId, at);
	}

	// 여러 회원을 한 번에: 소속이 없는 회원도 빈 목록으로 들어 있다. 판정 대상 전체를 훑을 때 쿼리를 한 번만 쓰게 한다
	@Transactional(readOnly = true)
	public Map<Long, List<Long>> teamIdsAt(Collection<Long> memberIds, LocalDateTime at) {
		Map<Long, TreeSet<Long>> grouped = new HashMap<>();
		for (Long memberId : memberIds) {
			grouped.put(memberId, new TreeSet<>(Comparator.naturalOrder()));
		}
		if (!memberIds.isEmpty()) {
			for (TeamMember membership : teamMemberRepository.findMembershipsAt(memberIds, at)) {
				grouped.get(membership.getMemberId()).add(membership.getTeamId());
			}
		}
		Map<Long, List<Long>> result = new HashMap<>();
		grouped.forEach((memberId, teamIds) -> result.put(memberId, List.copyOf(teamIds)));
		return result;
	}

}
