package com.jandilog.team.graphql;

import java.util.List;

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;

import com.jandilog.common.exception.ApiException;
import com.jandilog.common.security.AuthenticatedMember;
import com.jandilog.post.dto.CursorPage;
import com.jandilog.team.dto.CreateTeamInput;
import com.jandilog.team.dto.CreateTeamPayload;
import com.jandilog.team.dto.ReceivedInvitationResponse;
import com.jandilog.team.dto.SentInvitationResponse;
import com.jandilog.team.dto.TeamMemberResponse;
import com.jandilog.team.dto.TeamResponse;
import com.jandilog.team.dto.UpdateTeamInput;
import com.jandilog.team.service.AcceptInvitationResult;
import com.jandilog.team.service.TeamAccessService;
import com.jandilog.team.service.TeamInvitationService;
import com.jandilog.team.service.TeamJoinService;
import com.jandilog.team.service.TeamLeaveService;
import com.jandilog.team.service.TeamService;

// 팀 (기능명세서 2장). 팀장 전용·팀원 전용 검사는 서비스가 팀을 읽어서 하고(관리자도 예외 없음),
// 여기서는 ACTIVE 회원(ROLE_MEMBER)만 통과시킨다
@Controller
public class TeamController {

	private final TeamService teamService;
	private final TeamJoinService joinService;
	private final TeamInvitationService invitationService;
	private final TeamLeaveService leaveService;

	public TeamController(TeamService teamService, TeamJoinService joinService,
			TeamInvitationService invitationService, TeamLeaveService leaveService) {
		this.teamService = teamService;
		this.joinService = joinService;
		this.invitationService = invitationService;
		this.leaveService = leaveService;
	}

	@QueryMapping
	@PreAuthorize("hasRole('MEMBER')")
	public List<TeamResponse> myTeams(@AuthenticationPrincipal AuthenticatedMember member) {
		return teamService.myTeams(member.id());
	}

	@QueryMapping
	@PreAuthorize("hasRole('MEMBER')")
	public TeamResponse team(@AuthenticationPrincipal AuthenticatedMember member, @Argument String id) {
		return teamService.get(member.id(), TeamAccessService.parseTeamId(id));
	}

	@QueryMapping
	@PreAuthorize("hasRole('MEMBER')")
	public CursorPage<TeamMemberResponse> teamMembers(@AuthenticationPrincipal AuthenticatedMember member,
			@Argument String teamId, @Argument String after) {
		return teamService.members(member.id(), TeamAccessService.parseTeamId(teamId), after);
	}

	@QueryMapping
	@PreAuthorize("hasRole('MEMBER')")
	public String teamInviteCode(@AuthenticationPrincipal AuthenticatedMember member, @Argument String teamId) {
		return teamService.inviteCode(member.id(), TeamAccessService.parseTeamId(teamId));
	}

	@QueryMapping
	@PreAuthorize("hasRole('MEMBER')")
	public CursorPage<ReceivedInvitationResponse> receivedTeamInvitations(
			@AuthenticationPrincipal AuthenticatedMember member, @Argument String after) {
		return invitationService.received(member.id(), after);
	}

	@QueryMapping
	@PreAuthorize("hasRole('MEMBER')")
	public CursorPage<SentInvitationResponse> sentTeamInvitations(@AuthenticationPrincipal AuthenticatedMember member,
			@Argument String teamId, @Argument String after) {
		return invitationService.sent(member.id(), TeamAccessService.parseTeamId(teamId), after);
	}

	@MutationMapping
	@PreAuthorize("hasRole('MEMBER')")
	public CreateTeamPayload createTeam(@AuthenticationPrincipal AuthenticatedMember member,
			@Argument CreateTeamInput input) {
		return teamService.create(member.id(), input);
	}

	@MutationMapping
	@PreAuthorize("hasRole('MEMBER')")
	public TeamResponse updateTeam(@AuthenticationPrincipal AuthenticatedMember member, @Argument String teamId,
			@Argument UpdateTeamInput input) {
		return teamService.update(member.id(), TeamAccessService.parseTeamId(teamId), input);
	}

	@MutationMapping
	@PreAuthorize("hasRole('MEMBER')")
	public TeamResponse joinTeamByCode(@AuthenticationPrincipal AuthenticatedMember member, @Argument String code) {
		return joinService.joinByCode(member.id(), code);
	}

	@MutationMapping
	@PreAuthorize("hasRole('MEMBER')")
	public SentInvitationResponse inviteToTeam(@AuthenticationPrincipal AuthenticatedMember member,
			@Argument String teamId, @Argument String githubLogin) {
		return invitationService.invite(member.id(), TeamAccessService.parseTeamId(teamId), githubLogin);
	}

	@MutationMapping
	@PreAuthorize("hasRole('MEMBER')")
	public boolean cancelTeamInvitation(@AuthenticationPrincipal AuthenticatedMember member,
			@Argument String invitationId) {
		invitationService.cancel(member.id(), TeamAccessService.parseTeamId(invitationId));
		return true;
	}

	// 서비스가 커밋한 뒤에 사유를 오류로 바꾼다. 이미 참가했거나(E-17) 팀이 없어진(E-18) 초대는 정리한 결과가 남아야 한다
	@MutationMapping
	@PreAuthorize("hasRole('MEMBER')")
	public TeamResponse acceptTeamInvitation(@AuthenticationPrincipal AuthenticatedMember member,
			@Argument String invitationId) {
		AcceptInvitationResult result = invitationService.accept(member.id(),
				TeamAccessService.parseTeamId(invitationId));
		if (result.error() != null) {
			throw new ApiException(result.error());
		}
		return result.team();
	}

	@MutationMapping
	@PreAuthorize("hasRole('MEMBER')")
	public boolean declineTeamInvitation(@AuthenticationPrincipal AuthenticatedMember member,
			@Argument String invitationId) {
		invitationService.decline(member.id(), TeamAccessService.parseTeamId(invitationId));
		return true;
	}

	@MutationMapping
	@PreAuthorize("hasRole('MEMBER')")
	public boolean kickTeamMember(@AuthenticationPrincipal AuthenticatedMember member, @Argument String teamId,
			@Argument String memberId) {
		leaveService.kick(member.id(), TeamAccessService.parseTeamId(teamId), TeamAccessService.parseTeamId(memberId));
		return true;
	}

	@MutationMapping
	@PreAuthorize("hasRole('MEMBER')")
	public boolean leaveTeam(@AuthenticationPrincipal AuthenticatedMember member, @Argument String teamId) {
		leaveService.leave(member.id(), TeamAccessService.parseTeamId(teamId));
		return true;
	}

	@MutationMapping
	@PreAuthorize("hasRole('MEMBER')")
	public TeamResponse transferTeamLeadership(@AuthenticationPrincipal AuthenticatedMember member,
			@Argument String teamId, @Argument String newLeaderId) {
		return leaveService.transferLeadership(member.id(), TeamAccessService.parseTeamId(teamId),
				TeamAccessService.parseTeamId(newLeaderId));
	}

	@MutationMapping
	@PreAuthorize("hasRole('MEMBER')")
	public boolean deleteTeam(@AuthenticationPrincipal AuthenticatedMember member, @Argument String teamId) {
		leaveService.delete(member.id(), TeamAccessService.parseTeamId(teamId));
		return true;
	}

}
