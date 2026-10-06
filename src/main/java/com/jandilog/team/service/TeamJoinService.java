package com.jandilog.team.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.team.domain.Team;
import com.jandilog.team.domain.TeamJoinedBy;
import com.jandilog.team.domain.TeamMember;
import com.jandilog.team.dto.TeamResponse;
import com.jandilog.team.repository.TeamMemberRepository;
import com.jandilog.team.repository.TeamRepository;

// 초대코드 참가 (기능명세서 2장, 화면설계서 TM-03). 검사 순서: 없는 코드(E-10) → 이미 참가(E-11) → 추방 차단(E-12)
@Service
public class TeamJoinService {

	private final TeamRepository teamRepository;
	private final TeamMemberRepository teamMemberRepository;
	private final TeamService teamService;
	private final TeamMembershipWriter membershipWriter;
	private final TeamBanService banService;
	private final Clock clock;

	public TeamJoinService(TeamRepository teamRepository, TeamMemberRepository teamMemberRepository,
			TeamService teamService, TeamMembershipWriter membershipWriter, TeamBanService banService, Clock clock) {
		this.teamRepository = teamRepository;
		this.teamMemberRepository = teamMemberRepository;
		this.teamService = teamService;
		this.membershipWriter = membershipWriter;
		this.banService = banService;
		this.clock = clock;
	}

	// 대소문자 구분 없이 처리한다(제안). 참가에 성공하면 사용한 코드를 소속 행에 저장한다
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public TeamResponse joinByCode(long memberId, String rawCode) {
		String code = rawCode == null ? "" : rawCode.strip().toUpperCase(Locale.ROOT);
		Long teamId = code.isEmpty() ? null : teamRepository.findAliveIdByInviteCode(code).orElse(null);
		if (teamId == null) {
			throw new ApiException(ErrorCode.TEAM_CODE_NOT_FOUND);
		}
		// 같은 팀의 참가·탈퇴·삭제와 겹치지 않게 팀 행을 잠그고, 잠근 뒤 삭제 여부를 다시 확인한다.
		// 팀 엔티티를 잠금 전에 읽어 두면 삭제 커밋 뒤에도 옛 값이 남으므로 id만 먼저 읽었다
		Team team = teamRepository.findByIdForUpdate(teamId).filter(t -> !t.isDeleted())
				.orElseThrow(() -> new ApiException(ErrorCode.TEAM_CODE_NOT_FOUND));

		if (teamMemberRepository.existsByTeamIdAndMemberIdAndLeftAtIsNull(team.getId(), memberId)) {
			throw new ApiException(ErrorCode.TEAM_ALREADY_JOINED);
		}
		// 남은 차단 기간은 알리지 않는다 (E-12)
		if (banService.isBanned(team.getId(), memberId)) {
			throw new ApiException(ErrorCode.TEAM_JOIN_BLOCKED);
		}

		LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
		TeamMember membership = membershipWriter.add(team.getId(), memberId, TeamJoinedBy.INVITE_CODE,
				team.getInviteCode(), now);
		return teamService.response(team, memberId, membership.getJoinedAt());
	}

}
