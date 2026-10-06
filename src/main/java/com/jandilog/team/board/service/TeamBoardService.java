package com.jandilog.team.board.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jandilog.judgment.domain.JudgmentWeek;
import com.jandilog.post.domain.Post;
import com.jandilog.post.dto.BoardAuthor;
import com.jandilog.post.service.BoardAuthorService;
import com.jandilog.team.board.domain.MemberWeek;
import com.jandilog.team.board.domain.TeamBoardStatus;
import com.jandilog.team.board.dto.TeamBoardDayResponse;
import com.jandilog.team.board.dto.TeamBoardMemberResponse;
import com.jandilog.team.board.dto.TeamBoardPostResponse;
import com.jandilog.team.board.dto.TeamBoardResponse;
import com.jandilog.team.board.dto.TeamWeekSummaryResponse;
import com.jandilog.team.board.repository.BoardMemberRow;
import com.jandilog.team.board.repository.TeamBoardPostRepository;
import com.jandilog.team.board.repository.TeamBoardRepository;
import com.jandilog.team.board.repository.WarningWeek;
import com.jandilog.team.domain.Team;
import com.jandilog.team.service.TeamAccessService;
import com.jandilog.warning.domain.WarningRecalcResult;
import com.jandilog.warning.service.WarningRecalculationService;

// 팀 현황판(TM-05)과 내 팀 목록의 이번 주 요약(TM-01 ⑧). 기능명세서 2장 팀 현황판.
// 이번 주(월~일, KST) 값만 다루고 GitHub를 부르지 않는다(잔디는 하루 한 번 갱신한 캐시). 기록글 수·경고 수는 조회할 때 읽는다
@Service
public class TeamBoardService {

	// 화면설계서 TM-05 ⑦: 이 팀의 기록글 최신 5건 (제안)
	static final int RECENT_POST_LIMIT = 5;
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private final TeamAccessService access;
	private final TeamBoardRepository repository;
	private final TeamBoardPostRepository postRepository;
	private final MemberWeekService memberWeekService;
	private final WarningRecalculationService warningService;
	private final BoardAuthorService authorService;
	private final Clock clock;

	public TeamBoardService(TeamAccessService access, TeamBoardRepository repository,
			TeamBoardPostRepository postRepository, MemberWeekService memberWeekService,
			WarningRecalculationService warningService, BoardAuthorService authorService, Clock clock) {
		this.access = access;
		this.repository = repository;
		this.postRepository = postRepository;
		this.memberWeekService = memberWeekService;
		this.warningService = warningService;
		this.authorService = authorService;
		this.clock = clock;
	}

	// 그 팀의 현재 팀원·팀장만 (비소속·관리자 E-09), 없거나 삭제된 팀은 E-53
	@Transactional(readOnly = true)
	public TeamBoardResponse teamBoard(long viewerId, long teamId) {
		Team team = access.requireAlive(teamId);
		access.requireMembership(teamId, viewerId);
		LocalDate weekStart = currentWeekStart();

		List<BoardMemberRow> rows = leaderFirst(repository.findCurrentMembers(List.of(teamId)), team);
		Map<Long, MemberWeek> weeks = memberWeekService.load(subjects(rows), weekStart);
		Map<Long, Integer> warnings = teamWarningCounts(teamId, rows);

		List<TeamBoardMemberResponse> members = new ArrayList<>();
		for (BoardMemberRow row : rows) {
			MemberWeek week = weeks.get(row.memberId());
			members.add(new TeamBoardMemberResponse(row.memberId(), row.nickname(), row.githubLogin(),
					row.memberId() == viewerId, week.days().stream().map(TeamBoardDayResponse::from).toList(),
					week.verifiedDays(), week.recordCount(), warnings.get(row.memberId()), week.status()));
		}
		return new TeamBoardResponse(weekStart.toString(), weekStart.plusDays(6).toString(),
				grassFetchedAt(weeks.values()), weeks.values().stream().anyMatch(w -> w.verifiedDays() == null),
				members, recentPosts(teamId));
	}

	// 팀들의 이번 주 통과·미달·아직 인원. 팀 목록 전체를 쿼리 몇 번으로 센다. 호출하는 쪽이 팀 열람 권한을 이미 확인한 팀만 넘긴다
	@Transactional(readOnly = true)
	public Map<Long, TeamWeekSummaryResponse> weekSummaries(Collection<Long> teamIds) {
		LocalDate weekStart = currentWeekStart();
		List<BoardMemberRow> rows = repository.findCurrentMembers(teamIds.stream().distinct().toList());
		Map<Long, MemberWeek> weeks = memberWeekService.load(subjects(rows), weekStart);

		Map<Long, int[]> tally = new HashMap<>();
		for (BoardMemberRow row : rows) {
			int[] counts = tally.computeIfAbsent(row.teamId(), id -> new int[3]);
			TeamBoardStatus status = weeks.get(row.memberId()).status();
			switch (status) {
				case PASS -> counts[0]++;
				case FAIL -> counts[1]++;
				case NOT_YET -> counts[2]++;
				case EXCLUDED -> {
					// 판정 제외·면제는 어느 쪽에도 세지 않는다
				}
			}
		}
		Map<Long, TeamWeekSummaryResponse> result = new HashMap<>();
		for (Long teamId : teamIds) {
			int[] counts = tally.getOrDefault(teamId, new int[3]);
			result.put(teamId, new TeamWeekSummaryResponse(weekStart.toString(), counts[0], counts[1], counts[2]));
		}
		return result;
	}

	private LocalDate currentWeekStart() {
		return JudgmentWeek.mondayOf(LocalDate.now(clock.withZone(KST)));
	}

	// 팀장이 맨 앞, 나머지는 참가한 순서. 팀원 목록(teamMembers)과 같은 순서다
	private static List<BoardMemberRow> leaderFirst(List<BoardMemberRow> rows, Team team) {
		return rows.stream().sorted(Comparator.comparing((BoardMemberRow row) -> !team.isLeader(row.memberId())))
				.toList();
	}

	// 같은 회원이 여러 팀에 있어도 한 번만 계산하도록 회원 단위로 모은다
	private static List<MemberWeekService.Subject> subjects(List<BoardMemberRow> rows) {
		Map<Long, Set<Long>> teamsByMember = new LinkedHashMap<>();
		Map<Long, BoardMemberRow> firstRow = new HashMap<>();
		for (BoardMemberRow row : rows) {
			teamsByMember.computeIfAbsent(row.memberId(), id -> new HashSet<>()).add(row.teamId());
			firstRow.putIfAbsent(row.memberId(), row);
		}
		List<MemberWeekService.Subject> subjects = new ArrayList<>();
		teamsByMember.forEach((memberId, teamIds) -> subjects.add(
				new MemberWeekService.Subject(memberId, firstRow.get(memberId).firstTeamJoinedAt(), teamIds)));
		return subjects;
	}

	// 이 팀 카테고리가 붙은 경고 중 지금 살아 있고 차감되지 않은 것만 센다 (기능명세서 2장·6장).
	// 차감·이행 기준선은 경고 재계산과 같은 값을 쓰므로 내 경고 요약과 어긋나지 않는다.
	// 판정 보류가 남은 회원은 재계산을 미루므로 알 수 없어 null이다 (Q-03)
	private Map<Long, Integer> teamWarningCounts(long teamId, List<BoardMemberRow> rows) {
		List<Long> memberIds = rows.stream().map(BoardMemberRow::memberId).toList();
		Set<WarningWeek> teamWarnings = new HashSet<>(repository.findTeamWarnings(teamId, memberIds));
		Map<Long, Integer> counts = new HashMap<>();
		for (Long memberId : memberIds) {
			if (warningService.calculate(memberId) instanceof WarningRecalcResult.Calculated state) {
				counts.put(memberId, (int) state.activeWarningWeeks().stream()
						.filter(week -> teamWarnings.contains(new WarningWeek(memberId, week))).count());
			}
			else {
				counts.put(memberId, null);
			}
		}
		return counts;
	}

	// 가장 오래된 캐시의 갱신 시각. "오늘 오전 6시 기준" 표기에 쓴다
	private static String grassFetchedAt(Collection<MemberWeek> weeks) {
		return weeks.stream().map(MemberWeek::grassFetchedAt).filter(Objects::nonNull).min(Comparator.naturalOrder())
				.map(DateTimeFormatter.ISO_LOCAL_DATE_TIME::format).orElse(null);
	}

	private List<TeamBoardPostResponse> recentPosts(long teamId) {
		List<Post> posts = postRepository.findLatestRecords(teamId, RECENT_POST_LIMIT);
		Map<Long, BoardAuthor> authors = authorService
				.findByIds(posts.stream().map(Post::getAuthorId).distinct().toList());
		List<TeamBoardPostResponse> result = new ArrayList<>();
		for (Post post : posts) {
			BoardAuthor author = authors.get(post.getAuthorId());
			if (author != null) {
				result.add(new TeamBoardPostResponse(post.getId().toHexString(), post.getType(), post.getTitle(), author,
						kst(post.getCreatedAt())));
			}
		}
		return result;
	}

	private static String kst(Instant instant) {
		return instant == null ? null
				: DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(LocalDateTime.ofInstant(instant, KST));
	}

}
