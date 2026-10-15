package com.jandilog.team.board.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
		List<Long> memberIds = subjects.stream().map(Subject::memberId).distinct().toList();

		Map<Long, WeeklyJudgment> judgments = repository.findConfirmedJudgments(memberIds, weekStart).stream()
				.collect(Collectors.toMap(WeeklyJudgment::getMemberId, j -> j));
		Map<Long, List<JudgmentDay>> storedDays = repository
				.findJudgmentDays(judgments.values().stream().map(WeeklyJudgment::getId).toList()).stream()
				.collect(Collectors.groupingBy(JudgmentDay::getJudgmentId));
		Map<Long, Map<LocalDate, Integer>> records = recordsByMember(memberIds, weekStart);
		Set<Long> exempted = repository.findMembersWithApprovedExemption(memberIds, weekStart);
		boolean exemptionPeriod = sourceRepository.isExemptionPeriod(weekStart);

		Map<Long, MemberWeek> result = new HashMap<>();
		for (Subject subject : subjects) {
			long memberId = subject.memberId();
			WeeklyJudgment judgment = judgments.get(memberId);
			if (judgment != null) {
				MemberWeek stored = MemberWeekCalculator.confirmed(judgment,
						storedDays.getOrDefault(judgment.getId(), List.of()));
				if (stored != null) {
					result.put(memberId, stored);
					continue;
				}
			}
			GrassSnapshot snapshot = grassCacheService.findLatest(memberId).orElse(null);
			MemberWeek live = MemberWeekCalculator.live(weekStart, snapshot, records.getOrDefault(memberId, Map.of()));
			TeamBoardStatus fixed = fixedStatus(subject, judgment, weekStart, exemptionPeriod,
					exempted.contains(memberId));
			result.put(memberId, fixed == null ? live : live.withStatus(fixed));
		}
		return result;
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
