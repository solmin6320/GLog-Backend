package com.jandilog.team.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.jandilog.admin.domain.AdminActionLog;
import com.jandilog.admin.domain.AdminActionType;
import com.jandilog.admin.repository.AdminActionLogRepository;
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

	// admin_action_log.target_type 값. 추방은 끝난 team_member 행의 id, 팀 삭제는 team의 id
	private static final String TARGET_TYPE_TEAM_MEMBER = "TEAM_MEMBER";
	private static final String TARGET_TYPE_TEAM = "TEAM";

	private final TeamRepository teamRepository;
	private final TeamMemberRepository teamMemberRepository;
	private final TeamAccessService access;
	private final TeamService teamService;
	private final TeamBanService banService;
	private final TeamWarningService warningService;
	private final TeamPostLinkService postLinkService;
	private final AdminActionLogRepository actionLogRepository;
	private final Clock clock;

	public TeamLeaveService(TeamRepository teamRepository, TeamMemberRepository teamMemberRepository,
			TeamAccessService access, TeamService teamService, TeamBanService banService,
			TeamWarningService warningService, TeamPostLinkService postLinkService,
			AdminActionLogRepository actionLogRepository, Clock clock) {
		this.teamRepository = teamRepository;
		this.teamMemberRepository = teamMemberRepository;
		this.access = access;
		this.teamService = teamService;
		this.banService = banService;
		this.warningService = warningService;
		this.postLinkService = postLinkService;
		this.actionLogRepository = actionLogRepository;
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

	// 팀장 넘기기(팀장 또는 시스템 관리자): 현재 팀원에게만. 되돌릴 수 없고, 넘긴 사람은 일반 팀원이 된다
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public TeamResponse transferLeadership(long actorId, long teamId, long newLeaderId) {
		Team team = access.requireAliveForUpdate(teamId);
		boolean asAdmin = access.requireLeaderOrAdmin(team, actorId);
		if (team.isLeader(newLeaderId)) {
			throw new ApiException(ErrorCode.INVALID_INPUT);
		}
		if (!teamMemberRepository.existsByTeamIdAndMemberIdAndLeftAtIsNull(teamId, newLeaderId)) {
			throw new ApiException(ErrorCode.TEAM_MEMBER_NOT_FOUND);
		}
		team.changeLeader(newLeaderId);
		teamRepository.saveAndFlush(team);
		TeamResponse response = teamService.response(team, actorId, access.requireJoinedAtOrAdmin(teamId, actorId));
		if (asAdmin) {
			access.logAdminUse("팀장 위임", actorId, teamId, newLeaderId);
		}
		return response;
	}

	// 팀원 추방(팀장 또는 시스템 관리자): 소속을 KICKED로 닫고, 그 팀 경고 카테고리를 빼고(남은 카테고리가 없으면 경고 삭제 표시),
	// Redis에 1년 차단 키를 건다. 팀장은 누가 시켜도 추방할 수 없다(위임 후 추방)
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public void kick(long actorId, long teamId, long targetId) {
		Team team = access.requireAliveForUpdate(teamId);
		boolean asAdmin = access.requireLeaderOrAdmin(team, actorId);
		// 팀장과 본인은 추방할 수 없다. 팀원인 관리자가 자기를 추방하면 탈퇴와 달리 경고 카테고리가 빠진다
		if (team.isLeader(targetId) || targetId == actorId) {
			throw new ApiException(ErrorCode.INVALID_INPUT);
		}
		TeamMember target = teamMemberRepository.findByTeamIdAndMemberIdAndLeftAtIsNull(teamId, targetId)
				.orElseThrow(() -> new ApiException(ErrorCode.TEAM_MEMBER_NOT_FOUND));
		LocalDateTime now = now();

		target.leave(now, TeamLeaveType.KICKED);
		teamMemberRepository.flush();
		warningService.removeCategoryOnKick(targetId, teamId, now);
		// 관리자 권한으로 추방했으면 같은 트랜잭션에 기록한다 (대상은 끝난 소속 행). Redis 앞에 둬서 DB 실패가 키를 남기지 않게 한다
		if (asAdmin) {
			recordAdminAction(actorId, AdminActionType.KICK_MEMBER, TARGET_TYPE_TEAM_MEMBER, target.getId(), now);
		}
		// 마지막 단계: Redis가 실패하면 앞선 DB 변경도 롤백된다
		banService.ban(teamId, targetId, now);
		if (asAdmin) {
			access.logAdminUse("추방", actorId, teamId, targetId);
		}
	}

	// 팀 삭제(팀장 또는 시스템 관리자): 팀 행은 deleted_at만 찍고 남긴다. 소속은 TEAM_DELETED로 닫고,
	// 경고 카테고리를 빼고, 글의 팀 연결은 Mongo·post_index 모두 끊는다 (DB명세서 4-4, E-21·E-22).
	// 대기 중인 초대는 그대로 두고, 수락 시점에 E-18로 정리한다
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public void delete(long actorId, long teamId) {
		Team team = access.requireAliveForUpdate(teamId);
		boolean asAdmin = access.requireLeaderOrAdmin(team, actorId);
		LocalDateTime now = now();

		team.markDeleted(now);
		List<TeamMember> members = teamMemberRepository.findByTeamIdAndLeftAtIsNullOrderByJoinedAtAscIdAsc(teamId);
		for (TeamMember member : members) {
			member.leave(now, TeamLeaveType.TEAM_DELETED);
		}
		teamRepository.saveAndFlush(team);
		teamMemberRepository.flush();

		warningService.removeCategoriesOnTeamDelete(teamId, now);
		// 관리자 권한으로 삭제했으면 같은 트랜잭션에 기록한다. Mongo 앞에 둬서 DB 실패가 글 연결 해제를 남기지 않게 한다
		if (asAdmin) {
			recordAdminAction(actorId, AdminActionType.DELETE_TEAM, TARGET_TYPE_TEAM, teamId, now);
		}
		// 마지막 단계: Mongo가 실패하면 앞선 PostgreSQL 변경도 롤백된다
		postLinkService.unlinkTeam(teamId);
		if (asAdmin) {
			access.logAdminUse("팀 삭제", actorId, teamId, null);
		}
	}

	// 되돌릴 수 없는 관리자 행동 기록 (기능명세서 9장, DB명세서 1-16). 사유는 받지 않는다
	private void recordAdminAction(long adminId, AdminActionType action, String targetType, long targetId,
			LocalDateTime now) {
		actionLogRepository.save(AdminActionLog.of(adminId, action, targetType, Long.toString(targetId), null, now));
	}

	private LocalDateTime now() {
		return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
	}

}
