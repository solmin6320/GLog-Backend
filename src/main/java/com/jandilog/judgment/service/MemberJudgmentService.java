package com.jandilog.judgment.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.judgment.domain.GrassLookup;
import com.jandilog.judgment.domain.HoldReason;
import com.jandilog.judgment.domain.JudgmentCalculator;
import com.jandilog.judgment.domain.JudgmentInput;
import com.jandilog.judgment.domain.JudgmentResult;
import com.jandilog.judgment.domain.JudgmentTeam;
import com.jandilog.judgment.domain.JudgmentWeek;
import com.jandilog.judgment.domain.WeeklyJudgment;
import com.jandilog.judgment.dto.GrassSnapshot;
import com.jandilog.judgment.repository.JudgmentSourceRepository;
import com.jandilog.judgment.repository.JudgmentTeamRepository;
import com.jandilog.judgment.repository.WeeklyJudgmentRepository;
import com.jandilog.judgment.service.JudgmentRecordService.Outcome;
import com.jandilog.judgment.service.JudgmentRecordService.Type;
import com.jandilog.member.domain.Member;
import com.jandilog.member.repository.MemberRepository;

// 한 회원의 한 주를 판정한다 (FC-02, DB명세서 4-2): 입력 수집 → 잔디 확보 → 계산 → 저장.
// 잔디 조회(외부 호출)는 DB 트랜잭션 밖에서 하고, 회원 락은 저장 단계(JudgmentRecordService)에서만 잡는다.
// 이미 확정된 주는 GitHub를 부르지 않고 건너뛴다. 보류 주는 저장된 소속 스냅샷으로 다시 돈다 (E-35, E-37)
@Service
public class MemberJudgmentService {

	private static final Logger log = LoggerFactory.getLogger(MemberJudgmentService.class);

	private final MemberRepository memberRepository;
	private final WeeklyJudgmentRepository judgmentRepository;
	private final JudgmentTeamRepository teamRepository;
	private final JudgmentSourceRepository sourceRepository;
	private final GrassCacheService grassCacheService;
	private final JudgmentRecordService recordService;
	private final JudgmentCalculator calculator;

	public MemberJudgmentService(MemberRepository memberRepository, WeeklyJudgmentRepository judgmentRepository,
			JudgmentTeamRepository teamRepository, JudgmentSourceRepository sourceRepository,
			GrassCacheService grassCacheService, JudgmentRecordService recordService, Clock clock) {
		this.memberRepository = memberRepository;
		this.judgmentRepository = judgmentRepository;
		this.teamRepository = teamRepository;
		this.sourceRepository = sourceRepository;
		this.grassCacheService = grassCacheService;
		this.recordService = recordService;
		this.calculator = new JudgmentCalculator(clock);
	}

	public Outcome judge(long memberId, LocalDate weekStart) {
		return judge(memberId, weekStart, false);
	}

	// autoRetry: 스케줄러의 자동 재시도일 때 true. 관리자 재실행은 false라 재시도 횟수를 올리지 않는다
	public Outcome judge(long memberId, LocalDate weekStart, boolean autoRetry) {
		WeeklyJudgment existing = judgmentRepository.findByMemberIdAndWeekStart(memberId, weekStart).orElse(null);
		if (existing != null && existing.isConfirmed()) {
			return new Outcome(Type.SKIPPED_CONFIRMED, existing);
		}
		Member member = memberRepository.findById(memberId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));

		// 소속은 주 종료 시각 스냅샷 (Q-04). 보류 재실행은 처음 박아둔 스냅샷을 그대로 쓴다
		Set<Long> teamIds = existing != null
				? teamRepository.findByJudgmentId(existing.getId()).stream().map(JudgmentTeam::getTeamId)
						.collect(Collectors.toSet())
				: sourceRepository.findTeamIdsAt(memberId, JudgmentWeek.endExclusive(weekStart).minusSeconds(1));
		Map<LocalDate, Integer> recordPosts = sourceRepository.countRecordPostsByDay(memberId, weekStart,
				weekStart.plusDays(6));

		JudgmentInput withoutGrass = new JudgmentInput(weekStart, teamIds, member.getFirstTeamJoinedAt(),
				sourceRepository.isExemptionPeriod(weekStart),
				sourceRepository.hasApprovedPersonalExemption(memberId, weekStart), null, recordPosts);
		// 제외·면제 주는 잔디를 조회하지 않는다 (FC-02)
		GrassLookup grass = JudgmentCalculator.skipReason(withoutGrass) == null ? lookupGrass(member, weekStart) : null;
		JudgmentInput input = new JudgmentInput(weekStart, teamIds, member.getFirstTeamJoinedAt(),
				withoutGrass.exemptionPeriod(), withoutGrass.personalExemption(), grass, recordPosts);

		JudgmentResult result = calculator.judge(input);
		return recordService.record(memberId, weekStart, result, teamIds, autoRetry);
	}

	// 잔디 확보: 오늘 캐시를 쓰고 없으면 GitHub를 한 번 부른다. 실패는 그 회원만 보류 사유로 바꾼다 (Q-09)
	private GrassLookup lookupGrass(Member member, LocalDate weekStart) {
		try {
			GrassSnapshot snapshot = grassCacheService.getOrFetchCovering(member.getId(), member.getGithubLogin(),
					weekStart);
			return GrassLookup.available(snapshot.hasGrassBetween(weekStart, weekStart.plusDays(6)));
		}
		catch (GrassFetchException e) {
			log.warn("잔디 조회 실패로 보류 memberId={} weekStart={} reason={}", member.getId(), weekStart, e.getReason());
			return GrassLookup.failed(e.getReason());
		}
		catch (IllegalArgumentException e) {
			// 조회 구간이 GitHub 조회 한도를 넘을 만큼 오래됨: 잔디를 확보하지 못했으니 틀린 판정 대신 보류한다
			log.warn("잔디 확보 실패로 보류 memberId={} weekStart={} type={}", member.getId(), weekStart,
					e.getClass().getSimpleName());
			return GrassLookup.failed(HoldReason.API_ERROR);
		}
	}

}
