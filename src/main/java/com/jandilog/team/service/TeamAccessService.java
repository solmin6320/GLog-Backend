package com.jandilog.team.service;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.member.domain.MemberRole;
import com.jandilog.member.domain.MemberStatus;
import com.jandilog.member.repository.MemberRepository;
import com.jandilog.team.domain.Team;
import com.jandilog.team.domain.TeamMember;
import com.jandilog.team.repository.TeamMemberRepository;
import com.jandilog.team.repository.TeamRepository;

// 팀 컨텍스트 권한 검사. 팀장 전용·팀원 전용은 @PreAuthorize가 아니라 팀을 읽어서 정한다 (기능명세서 2장).
// 없는/삭제된 팀은 E-53, 팀원이 아니거나 팀장이 아니면 E-09.
// 시스템 관리자(ACTIVE + ADMIN)는 ...OrAdmin 검사에서만 모든 팀에 통과하고, 나머지 검사(참가·탈퇴·글 연결 등)에는 예외가 없다
@Service
public class TeamAccessService {

	private static final Logger log = LoggerFactory.getLogger(TeamAccessService.class);

	private final TeamRepository teamRepository;
	private final TeamMemberRepository teamMemberRepository;
	private final MemberRepository memberRepository;

	public TeamAccessService(TeamRepository teamRepository, TeamMemberRepository teamMemberRepository,
			MemberRepository memberRepository) {
		this.teamRepository = teamRepository;
		this.teamMemberRepository = teamMemberRepository;
		this.memberRepository = memberRepository;
	}

	// GraphQL ID 문자열을 팀 id로. 숫자가 아니면 없는 팀과 같게 처리한다
	public static long parseTeamId(String raw) {
		try {
			long id = Long.parseLong(raw == null ? "" : raw.strip());
			if (id > 0) {
				return id;
			}
		} catch (NumberFormatException e) {
			// 아래에서 같은 오류로 처리
		}
		throw new ApiException(ErrorCode.NOT_FOUND);
	}

	public Team requireAlive(long teamId) {
		return teamRepository.findById(teamId).filter(team -> !team.isDeleted())
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	// 팀 행을 잠그고 읽는다. 소속을 바꾸는 흐름은 모두 여기서 시작한다
	public Team requireAliveForUpdate(long teamId) {
		return teamRepository.findByIdForUpdate(teamId).filter(team -> !team.isDeleted())
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	public TeamMember requireMembership(long teamId, long memberId) {
		return teamMemberRepository.findByTeamIdAndMemberIdAndLeftAtIsNull(teamId, memberId)
				.orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN));
	}

	public void requireLeader(Team team, long memberId) {
		if (!team.isLeader(memberId)) {
			throw new ApiException(ErrorCode.FORBIDDEN);
		}
	}

	// 지금 ACTIVE 관리자인가. 요청마다 DB로 확인하고 클라이언트가 보낸 값은 보지 않는다
	public boolean isAdmin(long memberId) {
		return memberRepository.existsByIdAndStatusAndRole(memberId, MemberStatus.ACTIVE, MemberRole.ADMIN);
	}

	// 팀장 전용 기능의 검사. 팀장이거나 시스템 관리자면 통과하고, 관리자 권한으로 통과했으면 true
	public boolean requireLeaderOrAdmin(Team team, long memberId) {
		if (team.isLeader(memberId)) {
			return false;
		}
		if (isAdmin(memberId)) {
			return true;
		}
		throw new ApiException(ErrorCode.FORBIDDEN);
	}

	// 팀원 전용 조회의 검사. 그 팀의 현재 팀원이거나 시스템 관리자면 통과한다
	public void requireMemberOrAdmin(long teamId, long memberId) {
		if (!teamMemberRepository.existsByTeamIdAndMemberIdAndLeftAtIsNull(teamId, memberId) && !isAdmin(memberId)) {
			throw new ApiException(ErrorCode.FORBIDDEN);
		}
	}

	// 관리자 권한으로 쓴 팀 기능을 서버 로그에만 남긴다 (관리자 행동 로그 테이블은 만들지 않는다). 숫자 id만 적는다
	public void logAdminUse(String action, long adminId, long teamId, Long targetId) {
		log.info("관리자 팀 기능 사용: action={}, adminId={}, teamId={}, targetId={}", action, adminId, teamId, targetId);
	}

	// 글·댓글 수정용 팀장 권한: 연결된 팀이 삭제되지 않았고 내가 지금 그 팀의 팀장일 때만 true (기능명세서 2장, BD-03·BD-04).
	// 연결이 없거나 팀이 삭제됐으면 권한도 없고, 위임으로 팀장이 바뀌면 읽는 시점의 새 팀장에게 옮겨간다
	public boolean isLeaderOfAliveTeam(Long teamId, long memberId) {
		return teamId != null && teamRepository.findById(teamId).filter(team -> !team.isDeleted())
				.map(team -> team.isLeader(memberId)).orElse(false);
	}

	// 글에 연결하는 팀: 지금 내가 소속인 삭제되지 않은 팀만 (기능명세서 2장·3장, E-23과 같은 쪽의 입력 검증)
	public void requireLinkable(Long teamId, long memberId) {
		if (teamId == null) {
			return;
		}
		Optional<Team> team = teamRepository.findById(teamId).filter(t -> !t.isDeleted());
		if (team.isEmpty() || !teamMemberRepository.existsByTeamIdAndMemberIdAndLeftAtIsNull(teamId, memberId)) {
			throw new ApiException(ErrorCode.POST_TEAM_INVALID);
		}
	}

}
