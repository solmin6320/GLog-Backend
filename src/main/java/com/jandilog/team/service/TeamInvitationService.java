package com.jandilog.team.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.member.domain.Member;
import com.jandilog.member.domain.MemberStatus;
import com.jandilog.team.domain.InvitationStatus;
import com.jandilog.team.domain.Team;
import com.jandilog.team.domain.TeamInvitation;
import com.jandilog.team.domain.TeamJoinedBy;
import com.jandilog.team.domain.TeamMember;
import com.jandilog.team.dto.ReceivedInvitationResponse;
import com.jandilog.team.dto.SentInvitationResponse;
import com.jandilog.team.dto.TeamPersonResponse;
import com.jandilog.team.dto.TeamTimes;
import com.jandilog.team.repository.TeamInvitationRepository;
import com.jandilog.team.repository.TeamMemberAccountRepository;
import com.jandilog.team.repository.TeamMemberRepository;
import com.jandilog.team.repository.TeamRepository;

// 아이디 초대 (기능명세서 2장, 화면설계서 TM-04·TM-06). 팀장이 GitHub 아이디로 초대하고, 상대가 받은 초대에서 수락·거절한다.
// 추방자의 재초대는 허용하며(E-13) 초대코드 차단과 무관하다. 복귀하면 그 팀 경고 카테고리를 되살린다 (Q-02)
@Service
public class TeamInvitationService {

	// GitHub 아이디 형식: 영문자·숫자·하이픈, 최대 39자
	private static final Pattern GITHUB_LOGIN = Pattern.compile("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$");

	private final TeamRepository teamRepository;
	private final TeamMemberRepository teamMemberRepository;
	private final TeamInvitationRepository invitationRepository;
	private final TeamMemberAccountRepository accountRepository;
	private final TeamAccessService access;
	private final TeamService teamService;
	private final TeamMembershipWriter membershipWriter;
	private final TeamWarningService warningService;
	private final Clock clock;

	public TeamInvitationService(TeamRepository teamRepository, TeamMemberRepository teamMemberRepository,
			TeamInvitationRepository invitationRepository, TeamMemberAccountRepository accountRepository,
			TeamAccessService access, TeamService teamService, TeamMembershipWriter membershipWriter,
			TeamWarningService warningService, Clock clock) {
		this.teamRepository = teamRepository;
		this.teamMemberRepository = teamMemberRepository;
		this.invitationRepository = invitationRepository;
		this.accountRepository = accountRepository;
		this.access = access;
		this.teamService = teamService;
		this.membershipWriter = membershipWriter;
		this.warningService = warningService;
		this.clock = clock;
	}

	// 팀장이 GitHub 아이디로 초대한다. 가입한(승인된) 회원 중에서만 찾고(E-15), 이미 팀원이면 만들지 않으며(E-16),
	// 같은 사람에게 대기 중인 초대가 있으면 하나 더 만들지 않는다
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public SentInvitationResponse invite(long leaderId, long teamId, String rawLogin) {
		Team team = access.requireAliveForUpdate(teamId);
		access.requireLeader(team, leaderId);

		Member invitee = findInvitee(rawLogin);
		if (teamMemberRepository.existsByTeamIdAndMemberIdAndLeftAtIsNull(teamId, invitee.getId())) {
			throw new ApiException(ErrorCode.INVITEE_ALREADY_MEMBER);
		}
		// 팀 행 잠금 안에서 확인하므로 동시에 두 번 보내도 한 건만 만들어진다
		if (invitationRepository.existsByTeamIdAndInviteeIdAndStatus(teamId, invitee.getId(),
				InvitationStatus.PENDING)) {
			throw new ApiException(ErrorCode.INVITATION_DUPLICATE);
		}
		TeamInvitation invitation = invitationRepository.saveAndFlush(TeamInvitation.create(teamId, invitee.getId(),
				LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS)));
		return new SentInvitationResponse(invitation.getId(), TeamPersonResponse.from(invitee),
				TeamTimes.format(invitation.getCreatedAt()));
	}

	// TM-06 ③ 보낸 초대(대기 중), 최신순. 팀장만
	@Transactional(readOnly = true)
	public List<SentInvitationResponse> sent(long leaderId, long teamId) {
		Team team = access.requireAlive(teamId);
		access.requireLeader(team, leaderId);
		List<TeamInvitation> invitations = invitationRepository
				.findByTeamIdAndStatusOrderByCreatedAtDescIdDesc(teamId, InvitationStatus.PENDING);
		Map<Long, Member> invitees = teamService
				.membersById(invitations.stream().map(TeamInvitation::getInviteeId).toList());

		List<SentInvitationResponse> result = new ArrayList<>();
		for (TeamInvitation invitation : invitations) {
			Member invitee = invitees.get(invitation.getInviteeId());
			if (invitee != null) {
				result.add(new SentInvitationResponse(invitation.getId(), TeamPersonResponse.from(invitee),
						TeamTimes.format(invitation.getCreatedAt())));
			}
		}
		return result;
	}

	// 팀장이 보낸 초대를 철회한다(TM-06 ③ [취소]). 대기 중인 초대만 지운다
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public void cancel(long leaderId, long invitationId) {
		long teamId = invitationRepository.findTeamIdById(invitationId)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		Team team = access.requireAliveForUpdate(teamId);
		access.requireLeader(team, leaderId);
		TeamInvitation invitation = invitationRepository.findByIdForUpdate(invitationId)
				.filter(TeamInvitation::isPending)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		invitationRepository.delete(invitation);
	}

	// TM-04: 받은 초대, 최신순. 삭제된 팀의 초대는 빠진다. 비공개 팀도 당사자에게는 실제 이름
	@Transactional(readOnly = true)
	public List<ReceivedInvitationResponse> received(long memberId) {
		List<TeamInvitation> invitations = invitationRepository
				.findByInviteeIdAndStatusOrderByCreatedAtDescIdDesc(memberId, InvitationStatus.PENDING);
		if (invitations.isEmpty()) {
			return List.of();
		}
		Map<Long, Team> teams = new HashMap<>();
		for (Team team : teamRepository.findByIdIn(invitations.stream().map(TeamInvitation::getTeamId).toList())) {
			teams.put(team.getId(), team);
		}
		Map<Long, Member> leaders = teamService
				.membersById(teams.values().stream().map(Team::getLeaderId).distinct().toList());

		List<ReceivedInvitationResponse> result = new ArrayList<>();
		for (TeamInvitation invitation : invitations) {
			Team team = teams.get(invitation.getTeamId());
			Member leader = team == null ? null : leaders.get(team.getLeaderId());
			if (team != null && !team.isDeleted() && leader != null) {
				result.add(new ReceivedInvitationResponse(invitation.getId(), team.getId(), team.getName(),
						TeamPersonResponse.from(leader), TeamTimes.format(invitation.getCreatedAt())));
			}
		}
		return result;
	}

	// 수락 시점에 이미 다른 경로로 참가했거나(E-17) 팀이 삭제됐으면(E-18) 초대를 정리하고 그 사유를 값으로 돌려준다.
	// 정리 결과가 커밋돼야 해서 예외를 던지지 않는다. 오류로 바꾸는 일은 커밋 뒤 컨트롤러가 한다
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public AcceptInvitationResult accept(long memberId, long invitationId) {
		long teamId = invitationRepository.findTeamIdById(invitationId)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		// 팀 잠금 → 초대 잠금 순서. 팀을 잠근 뒤 초대를 새로 읽어 그 사이의 철회·거절을 반영한다
		Team team = teamRepository.findByIdForUpdate(teamId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		TeamInvitation invitation = invitationRepository.findByIdForUpdate(invitationId)
				.filter(i -> i.getInviteeId() == memberId && i.isPending())
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);

		if (team.isDeleted()) {
			invitation.decline(now);
			return AcceptInvitationResult.failed(ErrorCode.TEAM_GONE);
		}
		if (teamMemberRepository.existsByTeamIdAndMemberIdAndLeftAtIsNull(teamId, memberId)) {
			invitation.accept(now);
			return AcceptInvitationResult.failed(ErrorCode.INVITATION_ALREADY_JOINED);
		}

		TeamMember membership = membershipWriter.add(teamId, memberId, TeamJoinedBy.INVITATION, null, now);
		invitation.accept(now);
		// 추방됐던 사람이 돌아오면 그 팀 카테고리를 되살리고 경고 삭제 표시를 푼다 (E-14, Q-02)
		warningService.restoreCategoriesOnReturn(memberId, teamId);
		return AcceptInvitationResult.joined(teamService.response(team, memberId, membership.getJoinedAt()));
	}

	// 거절: 그 초대를 닫는다. 받은 초대 목록에서 사라진다
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public void decline(long memberId, long invitationId) {
		TeamInvitation invitation = invitationRepository.findByIdForUpdate(invitationId)
				.filter(i -> i.getInviteeId() == memberId && i.isPending())
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		invitation.decline(LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS));
	}

	// 가입한 회원(승인 완료)만 초대할 수 있다. 승인 대기·거절 계정은 팀 기능을 쓸 수 없으니 없는 회원처럼 본다 (E-15)
	private Member findInvitee(String rawLogin) {
		String login = rawLogin == null ? "" : rawLogin.strip();
		if (login.startsWith("@")) {
			login = login.substring(1);
		}
		if (!GITHUB_LOGIN.matcher(login).matches()) {
			throw new ApiException(ErrorCode.INVITEE_NOT_FOUND);
		}
		return accountRepository.findByLoginAndStatus(login, MemberStatus.ACTIVE).stream().findFirst()
				.orElseThrow(() -> new ApiException(ErrorCode.INVITEE_NOT_FOUND));
	}

}
