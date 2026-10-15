package com.jandilog.team.service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.member.domain.Member;
import com.jandilog.member.repository.MemberRepository;
import com.jandilog.post.dto.CursorPage;
import com.jandilog.team.domain.Team;
import com.jandilog.team.domain.TeamJoinedBy;
import com.jandilog.team.domain.TeamMember;
import com.jandilog.team.dto.CreateTeamInput;
import com.jandilog.team.dto.CreateTeamPayload;
import com.jandilog.team.dto.TeamMemberResponse;
import com.jandilog.team.dto.TeamPersonResponse;
import com.jandilog.team.dto.TeamResponse;
import com.jandilog.team.dto.UpdateTeamInput;
import com.jandilog.team.repository.TeamMemberCount;
import com.jandilog.team.repository.TeamMemberRepository;
import com.jandilog.team.repository.TeamRepository;

// 팀 생성·조회·공개 설정 변경과 초대코드 재확인 (기능명세서 2장, 화면설계서 TM-01·TM-02·TM-06).
// 팀을 바꾸는 흐름은 READ_COMMITTED + 팀 행 잠금으로 직렬화해서, 앞선 변경이 커밋된 뒤의 값을 보고 판단한다
@Service
public class TeamService {

	// 화면설계서 TM-02 ①②: 팀 이름 1~20자, 소개 100자 이내 (제안)
	static final int NAME_MAX_LENGTH = 20;
	static final int DESCRIPTION_MAX_LENGTH = 100;
	// 헷갈리는 글자(0 O 1 I)를 뺀 32자. 10자면 50비트라 추측으로 맞히기 어렵다
	private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
	private static final int CODE_LENGTH = 10;

	private final TeamRepository teamRepository;
	private final TeamMemberRepository teamMemberRepository;
	private final MemberRepository memberRepository;
	private final TeamAccessService access;
	private final TeamMembershipWriter membershipWriter;
	private final Clock clock;
	private final SecureRandom random = new SecureRandom();

	public TeamService(TeamRepository teamRepository, TeamMemberRepository teamMemberRepository,
			MemberRepository memberRepository, TeamAccessService access, TeamMembershipWriter membershipWriter,
			Clock clock) {
		this.teamRepository = teamRepository;
		this.teamMemberRepository = teamMemberRepository;
		this.memberRepository = memberRepository;
		this.access = access;
		this.membershipWriter = membershipWriter;
		this.clock = clock;
	}

	// 만든 사람이 팀장이자 첫 팀원이다. 초대코드는 이때 한 번만 정해지고 평문으로 저장한다 (Q-01)
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public CreateTeamPayload create(long memberId, CreateTeamInput input) {
		String name = normalizeName(input.name());
		String description = normalizeDescription(input.description());
		boolean publicTeam = input.isPublic() == null || input.isPublic();
		LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);

		String code = newInviteCode();
		Team team = teamRepository.saveAndFlush(Team.create(name, description, memberId, code, publicTeam, now));
		// 팀장도 소속 행이 있어야 판정 소속·팀원 수에 잡힌다. 초대코드로 들어온 것과 같은 방식으로 적는다
		TeamMember membership = membershipWriter.add(team.getId(), memberId, TeamJoinedBy.INVITE_CODE, code, now);

		TeamResponse response = TeamResponse.of(team, personOf(memberId), memberId, 1, membership.getJoinedAt());
		return new CreateTeamPayload(response, code);
	}

	// TM-01: 내 팀 목록. 참가한 순서(오래된 순), 비공개 팀도 실제 이름. 건수 제한이 없어 페이지 없이 모두 돌려준다
	@Transactional(readOnly = true)
	public List<TeamResponse> myTeams(long memberId) {
		List<TeamMember> memberships = teamMemberRepository
				.findByMemberIdAndLeftAtIsNullOrderByJoinedAtAscIdAsc(memberId);
		if (memberships.isEmpty()) {
			return List.of();
		}
		List<Long> teamIds = memberships.stream().map(TeamMember::getTeamId).toList();
		Map<Long, Team> teams = teamRepository.findByIdIn(teamIds).stream()
				.filter(team -> !team.isDeleted())
				.collect(Collectors.toMap(Team::getId, Function.identity()));
		Map<Long, Long> counts = counts(teamIds);
		Map<Long, Member> leaders = membersById(teams.values().stream().map(Team::getLeaderId).toList());

		List<TeamResponse> result = new ArrayList<>();
		for (TeamMember membership : memberships) {
			Team team = teams.get(membership.getTeamId());
			if (team != null) {
				result.add(TeamResponse.of(team, TeamPersonResponse.from(leaders.get(team.getLeaderId())), memberId,
						counts.getOrDefault(team.getId(), 0L).intValue(), membership.getJoinedAt()));
			}
		}
		return result;
	}

	// 팀 기본 정보. 그 팀의 팀원·팀장만 (E-09), 없는/삭제된 팀은 E-53
	@Transactional(readOnly = true)
	public TeamResponse get(long viewerId, long teamId) {
		Team team = access.requireAlive(teamId);
		TeamMember membership = access.requireMembership(teamId, viewerId);
		return response(team, viewerId, membership.getJoinedAt());
	}

	// 팀원 목록: 팀장이 맨 앞, 나머지는 참가한 순서. 그 팀의 팀원·팀장만. 커서 기반 20개 (Q-06)
	@Transactional(readOnly = true)
	public CursorPage<TeamMemberResponse> members(long viewerId, long teamId, String cursor) {
		Team team = access.requireAlive(teamId);
		access.requireMembership(teamId, viewerId);
		TeamCursor after = TeamCursor.parse(cursor);

		// 한 건 더 읽어서 다음 페이지가 있는지 판단한다. 팀장은 첫 페이지에만 나온다
		List<TeamMember> rows = after == null
				? teamMemberRepository.findActivePage(teamId, team.getLeaderId(), TeamCursor.fetchSize())
				: teamMemberRepository.findActivePageAfter(teamId, team.getLeaderId(), after.time(), after.id(),
						TeamCursor.fetchSize());
		String nextCursor = TeamCursor.nextOf(rows, row -> new TeamCursor(row.getJoinedAt(), row.getId()));
		List<TeamMember> page = TeamCursor.trim(rows);
		Map<Long, Member> members = membersById(page.stream().map(TeamMember::getMemberId).toList());

		List<TeamMemberResponse> items = new ArrayList<>();
		for (TeamMember row : page) {
			Member member = members.get(row.getMemberId());
			if (member != null) {
				items.add(TeamMemberResponse.of(member, team.isLeader(row.getMemberId()), row.getJoinedAt()));
			}
		}
		return new CursorPage<>(items, nextCursor);
	}

	// 초대코드 재확인: 그 팀의 팀장만. 재발급은 없고 처음 정해진 코드를 그대로 보여준다 (Q-01)
	@Transactional(readOnly = true)
	public String inviteCode(long viewerId, long teamId) {
		Team team = access.requireAlive(teamId);
		access.requireLeader(team, viewerId);
		return team.getInviteCode();
	}

	// 공개 설정 변경(팀장만). 팀 이름·소개 수정은 명세에 없다 (기능명세서 2장)
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public TeamResponse update(long viewerId, long teamId, UpdateTeamInput input) {
		Team team = access.requireAliveForUpdate(teamId);
		access.requireLeader(team, viewerId);

		team.changeVisibility(input.isPublic());
		teamRepository.saveAndFlush(team);

		TeamMember membership = access.requireMembership(teamId, viewerId);
		return response(team, viewerId, membership.getJoinedAt());
	}

	TeamResponse response(Team team, long viewerId, LocalDateTime joinedAt) {
		int count = (int) teamMemberRepository.countByTeamIdAndLeftAtIsNull(team.getId());
		return TeamResponse.of(team, personOf(team.getLeaderId()), viewerId, count, joinedAt);
	}

	TeamPersonResponse personOf(long memberId) {
		return TeamPersonResponse.from(memberRepository.findById(memberId)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)));
	}

	Map<Long, Member> membersById(Collection<Long> ids) {
		Map<Long, Member> members = new HashMap<>();
		for (Member member : memberRepository.findAllById(ids)) {
			members.put(member.getId(), member);
		}
		return members;
	}

	private Map<Long, Long> counts(Collection<Long> teamIds) {
		Map<Long, Long> counts = new HashMap<>();
		for (TeamMemberCount count : teamMemberRepository.countActiveByTeamIds(teamIds)) {
			counts.put(count.teamId(), count.count());
		}
		return counts;
	}

	// 앞뒤 공백을 지우고, 비었으면 EX-TM02-01, 20자(코드포인트 기준)를 넘으면 입력 오류
	static String normalizeName(String raw) {
		String name = raw == null ? "" : raw.strip();
		if (name.isEmpty()) {
			throw new ApiException(ErrorCode.TEAM_NAME_REQUIRED);
		}
		if (name.codePointCount(0, name.length()) > NAME_MAX_LENGTH) {
			throw new ApiException(ErrorCode.INVALID_INPUT);
		}
		return name;
	}

	// 선택 항목. 비었으면 null로 저장하고, 100자를 넘으면 입력 오류
	static String normalizeDescription(String raw) {
		String description = raw == null ? "" : raw.strip();
		if (description.isEmpty()) {
			return null;
		}
		if (description.codePointCount(0, description.length()) > DESCRIPTION_MAX_LENGTH) {
			throw new ApiException(ErrorCode.INVALID_INPUT);
		}
		return description;
	}

	private String newInviteCode() {
		for (int attempt = 0; attempt < 10; attempt++) {
			StringBuilder code = new StringBuilder(CODE_LENGTH);
			for (int i = 0; i < CODE_LENGTH; i++) {
				code.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
			}
			if (!teamRepository.existsByInviteCode(code.toString())) {
				return code.toString();
			}
		}
		throw new IllegalStateException("초대코드를 만들지 못했어요");
	}

}
