package com.jandilog.team.board.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jandilog.judgment.domain.JudgmentCalculator;
import com.jandilog.judgment.domain.JudgmentDay;
import com.jandilog.judgment.domain.JudgmentInput;
import com.jandilog.judgment.domain.WeeklyJudgment;
import com.jandilog.judgment.dto.GrassSnapshot;
import com.jandilog.judgment.repository.JudgmentSourceRepository;
import com.jandilog.judgment.service.GrassCacheService;
import com.jandilog.team.board.domain.MemberWeek;
import com.jandilog.team.board.domain.MemberWeekCalculator;
import com.jandilog.team.board.domain.TeamBoardStatus;
import com.jandilog.team.board.repository.RecordDayCount;
import com.jandilog.team.board.repository.TeamBoardRepository;

// 회원 여럿의 한 주 현황을 한 번에 모은다 (현황판 행·내 팀 목록 요약이 같이 쓴다).
// 잔디는 Redis 캐시만 읽고 GitHub를 부르지 않는다. 판정이 확정된 주는 확정 결과를, 아닌 주는 계산 결과를 쓴다.
// 판정 제외·면제는 판정과 같은 규칙(JudgmentCalculator.skipReason)으로 가린다
@Service
public class MemberWeekService {

	// teamIds: 판정 제외 계산에 쓰는 지금 소속 팀(현황판에 보이는 팀원은 항상 1개 이상)
	public record Subject(long memberId, LocalDateTime firstTeamJoinedAt, Set<Long> teamIds) {
	}

	private final TeamBoardRepository repository;
	private final JudgmentSourceRepository sourceRepository;
	private final GrassCacheService grassCacheService;

	public MemberWeekService(TeamBoardRepository repository, JudgmentSourceRepository sourceRepository,
			GrassCacheService grassCacheService) {
		this.repository = repository;
		this.sourceRepository = sourceRepository;
		this.grassCacheService = grassCacheService;
	}

	@Transactional(readOnly = true)
	public Map<Long, MemberWeek> load(Collection<Subject> subjects, LocalDate weekStart) {
		if (subjects.isEmpty()) {
			return Map.of();
		}
		WeekData data = readWeekData(subjects.stream().map(Subject::memberId).distinct().toList(), weekStart);

		Map<Long, MemberWeek> result = new HashMap<>();
		List<Subject> live = new ArrayList<>();
		for (Subject subject : subjects) {
			MemberWeek stored = storedWeek(subject.memberId(), data);
			if (stored != null) {
				result.put(subject.memberId(), stored);
			}
			else {
				live.add(subject);
			}
		}
		// 계산할 회원의 잔디 캐시는 회원마다 따로 읽지 않고 한 번에 읽는다
		Map<Long, GrassSnapshot> grass = grassCacheService
				.findLatestAll(live.stream().map(Subject::memberId).distinct().toList());
		for (Subject subject : live) {
			result.put(subject.memberId(), liveWeek(subject, weekStart, data, grass.get(subject.memberId())));
		}
		return result;
	}

	// 회원 전체의 확정 판정·저장 일자·기록글·개인 면제·면제 기간을 쿼리 한 번씩으로 읽는다
	private WeekData readWeekData(List<Long> memberIds, LocalDate weekStart) {
		Map<Long, WeeklyJudgment> judgments = repository.findConfirmedJudgments(memberIds, weekStart).stream()
				.collect(Collectors.toMap(WeeklyJudgment::getMemberId, j -> j));
		Map<Long, List<JudgmentDay>> storedDays = repository
				.findJudgmentDays(judgments.values().stream().map(WeeklyJudgment::getId).toList()).stream()
				.collect(Collectors.groupingBy(JudgmentDay::getJudgmentId));
		Map<Long, Map<LocalDate, Integer>> records = recordsByMember(memberIds, weekStart);
		Set<Long> exempted = repository.findMembersWithApprovedExemption(memberIds, weekStart);
		boolean exemptionPeriod = sourceRepository.isExemptionPeriod(weekStart);
		return new WeekData(judgments, storedDays, records, exempted, exemptionPeriod);
	}

	// 확정된 통과·미달 주는 확정 결과. 아니면 null
	private static MemberWeek storedWeek(long memberId, WeekData data) {
		WeeklyJudgment judgment = data.judgments().get(memberId);
		return judgment == null ? null
				: MemberWeekCalculator.confirmed(judgment, data.storedDays().getOrDefault(judgment.getId(), List.of()));
	}

	// 확정 결과가 없는 주는 잔디 캐시와 기록글로 계산하고, 제외·면제 주면 상태를 덮는다. snapshot은 캐시가 없으면 null
	private MemberWeek liveWeek(Subject subject, LocalDate weekStart, WeekData data, GrassSnapshot snapshot) {
		long memberId = subject.memberId();
		MemberWeek live = MemberWeekCalculator.live(weekStart, snapshot,
				data.records().getOrDefault(memberId, Map.of()));
		TeamBoardStatus fixed = fixedStatus(subject, data.judgments().get(memberId), weekStart, data.exemptionPeriod(),
				data.exempted().contains(memberId));
		return fixed == null ? live : live.withStatus(fixed);
	}

	private record WeekData(Map<Long, WeeklyJudgment> judgments, Map<Long, List<JudgmentDay>> storedDays,
			Map<Long, Map<LocalDate, Integer>> records, Set<Long> exempted, boolean exemptionPeriod) {
	}

	// 확정 결과가 있으면 그것, 없으면 판정 제외·면제 주인지(첫 참가 주·면제 기간·개인 면제)를 본다. 아니면 null
	private TeamBoardStatus fixedStatus(Subject subject, WeeklyJudgment judgment, LocalDate weekStart,
			boolean exemptionPeriod, boolean personalExemption) {
		if (judgment != null) {
			return MemberWeekCalculator.statusOf(judgment.getStatus());
		}
		JudgmentInput input = new JudgmentInput(weekStart, subject.teamIds(), subject.firstTeamJoinedAt(),
				exemptionPeriod, personalExemption, null, Map.of());
		return JudgmentCalculator.skipReason(input) == null ? null : TeamBoardStatus.EXCLUDED;
	}

	private Map<Long, Map<LocalDate, Integer>> recordsByMember(Collection<Long> memberIds, LocalDate weekStart) {
		Map<Long, Map<LocalDate, Integer>> byMember = new HashMap<>();
		for (RecordDayCount row : repository.countRecordPostsByDay(memberIds, weekStart, weekStart.plusDays(6))) {
			byMember.computeIfAbsent(row.memberId(), id -> new HashMap<>()).put(row.day(), (int) row.count());
		}
		return byMember;
	}

}
