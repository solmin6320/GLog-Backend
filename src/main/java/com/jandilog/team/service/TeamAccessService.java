package com.jandilog.team.service;

import java.util.Optional;

import org.springframework.stereotype.Service;

import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.team.domain.Team;
import com.jandilog.team.domain.TeamMember;
import com.jandilog.team.repository.TeamMemberRepository;
import com.jandilog.team.repository.TeamRepository;

// 팀 컨텍스트 권한 검사. 팀장 전용·팀원 전용은 @PreAuthorize가 아니라 팀을 읽어서 정한다 (기능명세서 2장).
// 없는/삭제된 팀은 E-53, 팀원이 아니거나 팀장이 아니면 E-09. 관리자라도 예외 없다
@Service
public class TeamAccessService {

	private final TeamRepository teamRepository;
	private final TeamMemberRepository teamMemberRepository;

	public TeamAccessService(TeamRepository teamRepository, TeamMemberRepository teamMemberRepository) {
		this.teamRepository = teamRepository;
		this.teamMemberRepository = teamMemberRepository;
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
