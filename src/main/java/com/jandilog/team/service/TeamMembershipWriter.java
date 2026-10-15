package com.jandilog.team.service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.jandilog.team.domain.TeamJoinedBy;
import com.jandilog.team.domain.TeamMember;
import com.jandilog.team.repository.TeamMemberAccountRepository;
import com.jandilog.team.repository.TeamMemberRepository;

// 팀에 사람을 넣는 공통 처리: 소속 행 추가 + 처음 참가 시각 기록.
// 호출하는 쪽이 팀 행 잠금과 중복 참가 검사를 마친 뒤 부른다
@Service
public class TeamMembershipWriter {

	private final TeamMemberRepository teamMemberRepository;
	private final TeamMemberAccountRepository accountRepository;

	public TeamMembershipWriter(TeamMemberRepository teamMemberRepository,
			TeamMemberAccountRepository accountRepository) {
		this.teamMemberRepository = teamMemberRepository;
		this.accountRepository = accountRepository;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public TeamMember add(long teamId, long memberId, TeamJoinedBy joinedBy, String usedInviteCode,
			LocalDateTime now) {
		LocalDateTime joinedAt = now.truncatedTo(ChronoUnit.SECONDS);
		// 같은 팀에서 나간 직후 같은 초에 다시 들어오면 (team_id, member_id, joined_at) UNIQUE에 걸리므로 1초 뒤로 민다
		LocalDateTime latest = teamMemberRepository.findFirstByTeamIdAndMemberIdOrderByJoinedAtDesc(teamId, memberId)
				.map(TeamMember::getJoinedAt).orElse(null);
		if (latest != null && !joinedAt.isAfter(latest)) {
			joinedAt = latest.plusSeconds(1);
		}
		TeamMember member = teamMemberRepository.saveAndFlush(
				TeamMember.join(teamId, memberId, joinedAt, joinedBy, usedInviteCode));
		// 생애 최초 1회만 기록한다. 나갔다 다시 들어와도 갱신하지 않는다 (DB명세서 1-1)
		accountRepository.markFirstTeamJoined(memberId, joinedAt);
		return member;
	}

}
