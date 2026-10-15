package com.jandilog.team.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.team.domain.Team;
import com.jandilog.team.domain.TeamLeaveType;
import com.jandilog.team.domain.TeamMember;
import com.jandilog.team.dto.TeamResponse;
import com.jandilog.team.repository.TeamMemberRepository;
import com.jandilog.team.repository.TeamRepository;

// 팀원 추방·자진 탈퇴·팀장 위임·팀 삭제 (기능명세서 2·6장, 화면설계서 TM-05·TM-06, E-19~E-22).
// 모두 팀 행 잠금부터 잡는다. 경고를 바꾸는 흐름(추방·팀 삭제)은 그 안에서 회원 행을 잠그고 재계산까지 같은 트랜잭션으로 끝낸다
@Service
public class TeamLeaveService {

	private final TeamRepository teamRepository;
	private final TeamMemberRepository teamMemberRepository;
	private final TeamAccessService access;
	private final TeamService teamService;
	private final TeamBanService banService;
	private final TeamWarningService warningService;
	private final TeamPostLinkService postLinkService;
	private final Clock clock;

	public TeamLeaveService(TeamRepository teamRepository, TeamMemberRepository teamMemberRepository,
			TeamAccessService access, TeamService teamService, TeamBanService banService,
			TeamWarningService warningService, TeamPostLinkService postLinkService, Clock clock) {
		this.teamRepository = teamRepository;
		this.teamMemberRepository = teamMemberRepository;
		this.access = access;
		this.teamService = teamService;
		this.banService = banService;
		this.warningService = warningService;
		this.postLinkService = postLinkService;
		this.clock = clock;
	}

	// 자진 탈퇴: 경고는 그대로 둔다. 팀장은 나갈 수 없다 — 넘길 팀원이 있으면 E-19, 혼자면 팀 삭제만 가능(E-20)
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public void leave(long memberId, long teamId) {
		Team team = access.requireAliveForUpdate(teamId);
		TeamMember membership = access.requireMembership(teamId, memberId);
		if (team.isLeader(memberId)) {
			boolean alone = teamMemberRepository.countByTeamIdAndLeftAtIsNull(teamId) <= 1;
			throw new ApiException(alone ? ErrorCode.LEADER_LAST_MEMBER : ErrorCode.LEADER_MUST_TRANSFER);
		}
		membership.leave(now(), TeamLeaveType.SELF);
	}

	// 팀장 넘기기: 현재 팀원에게만. 되돌릴 수 없고, 넘긴 사람은 일반 팀원이 된다
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public TeamResponse transferLeadership(long leaderId, long teamId, long newLeaderId) {
		Team team = access.requireAliveForUpdate(teamId);
		access.requireLeader(team, leaderId);
		if (newLeaderId == leaderId) {
			throw new ApiException(ErrorCode.INVALID_INPUT);
		}
		if (!teamMemberRepository.existsByTeamIdAndMemberIdAndLeftAtIsNull(teamId, newLeaderId)) {
			throw new ApiException(ErrorCode.TEAM_MEMBER_NOT_FOUND);
		}
		team.changeLeader(newLeaderId);
		teamRepository.saveAndFlush(team);
		TeamMember mine = access.requireMembership(teamId, leaderId);
		return teamService.response(team, leaderId, mine.getJoinedAt());
	}

	// 팀원 추방(팀장만): 소속을 KICKED로 닫고, 그 팀 경고 카테고리를 빼고(남은 카테고리가 없으면 경고 삭제 표시),
	// Redis에 1년 차단 키를 건다. 팀장 본인은 추방할 수 없다
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public void kick(long leaderId, long teamId, long targetId) {
		Team team = access.requireAliveForUpdate(teamId);
		access.requireLeader(team, leaderId);
		if (targetId == leaderId) {
			throw new ApiException(ErrorCode.INVALID_INPUT);
		}
		TeamMember target = teamMemberRepository.findByTeamIdAndMemberIdAndLeftAtIsNull(teamId, targetId)
				.orElseThrow(() -> new ApiException(ErrorCode.TEAM_MEMBER_NOT_FOUND));
		LocalDateTime now = now();

		target.leave(now, TeamLeaveType.KICKED);
		teamMemberRepository.flush();
		warningService.removeCategoryOnKick(targetId, teamId, now);
		// 마지막 단계: Redis가 실패하면 앞선 DB 변경도 롤백된다
		banService.ban(teamId, targetId, now);
	}

	// 팀 삭제(팀장만): 팀 행은 deleted_at만 찍고 남긴다. 소속은 TEAM_DELETED로 닫고,
	// 경고 카테고리를 빼고, 글의 팀 연결은 Mongo·post_index 모두 끊는다 (DB명세서 4-4, E-21·E-22).
	// 대기 중인 초대는 그대로 두고, 수락 시점에 E-18로 정리한다
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public void delete(long leaderId, long teamId) {
		Team team = access.requireAliveForUpdate(teamId);
		access.requireLeader(team, leaderId);
		LocalDateTime now = now();

		team.markDeleted(now);
		List<TeamMember> members = teamMemberRepository.findByTeamIdAndLeftAtIsNullOrderByJoinedAtAscIdAsc(teamId);
		for (TeamMember member : members) {
			member.leave(now, TeamLeaveType.TEAM_DELETED);
		}
		teamRepository.saveAndFlush(team);
		teamMemberRepository.flush();

		warningService.removeCategoriesOnTeamDelete(teamId, now);
		// 마지막 단계: Mongo가 실패하면 앞선 MariaDB 변경도 롤백된다
		postLinkService.unlinkTeam(teamId);
	}

	private LocalDateTime now() {
		return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
	}

}
