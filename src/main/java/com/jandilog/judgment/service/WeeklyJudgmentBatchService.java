package com.jandilog.judgment.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.jandilog.judgment.domain.HoldReason;
import com.jandilog.judgment.domain.JudgmentStatus;
import com.jandilog.judgment.domain.JudgmentWeek;
import com.jandilog.judgment.domain.WeeklyJudgment;
import com.jandilog.judgment.repository.JudgmentSourceRepository;
import com.jandilog.judgment.repository.JudgmentSourceRepository.MemberRef;
import com.jandilog.judgment.repository.WeeklyJudgmentRepository;
import com.jandilog.judgment.service.JudgmentRecordService.Outcome;

// 스케줄러가 부르는 일괄 작업 (기능명세서 5장): 새벽 잔디 갱신, 월요일 판정, 보류 자동 재시도.
// 회원 한 명의 실패가 다른 회원을 막지 않도록 회원마다 따로 처리하고 결과만 센다
@Service
public class WeeklyJudgmentBatchService {

	private static final Logger log = LoggerFactory.getLogger(WeeklyJudgmentBatchService.class);
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	// total 중 새로 저장 created, 보류를 갱신 updated, 이미 확정이라 건너뜀 skippedConfirmed, 이번에 보류로 남은 hold, 예외 failed
	public record JudgmentSummary(int total, int created, int updated, int skippedConfirmed, int hold, int failed) {
	}

	public record GrassRefreshSummary(int total, int refreshed, int failed) {
	}

	private final JudgmentSourceRepository sourceRepository;
	private final WeeklyJudgmentRepository judgmentRepository;
	private final MemberJudgmentService memberJudgmentService;
	private final GrassCacheService grassCacheService;
	private final Clock clock;

	public WeeklyJudgmentBatchService(JudgmentSourceRepository sourceRepository,
			WeeklyJudgmentRepository judgmentRepository, MemberJudgmentService memberJudgmentService,
			GrassCacheService grassCacheService, Clock clock) {
		this.sourceRepository = sourceRepository;
		this.judgmentRepository = judgmentRepository;
		this.memberJudgmentService = memberJudgmentService;
		this.grassCacheService = grassCacheService;
		this.clock = clock;
	}

	// 지금 기준 직전 주(월요일). 월요일 오전 판정의 대상이다
	public LocalDate lastWeekStart() {
		return JudgmentWeek.mondayOf(LocalDate.now(clock.withZone(KST))).minusWeeks(1);
	}

	// 모든 승인 회원의 잔디를 GitHub에서 다시 불러 오늘 캐시를 덮어쓴다 (회원당 하루 1회)
	public GrassRefreshSummary refreshGrass() {
		List<MemberRef> members = sourceRepository.findActiveMembers();
		int refreshed = 0;
		int failed = 0;
		for (MemberRef member : members) {
			try {
				grassCacheService.refresh(member.id(), member.githubLogin());
				refreshed++;
			}
			catch (GrassFetchException e) {
				// 조회 실패는 판정 때 그 회원만 보류된다. 여기서는 세고 넘어간다
				failed++;
				log.warn("잔디 갱신 실패 memberId={} reason={}", member.id(), e.getReason());
			}
			catch (RuntimeException e) {
				failed++;
				log.error("잔디 갱신 중 예기치 못한 오류 memberId={}", member.id(), e);
			}
		}
		log.info("잔디 갱신 완료 total={} refreshed={} failed={}", members.size(), refreshed, failed);
		return new GrassRefreshSummary(members.size(), refreshed, failed);
	}

	public JudgmentSummary judgeLastWeek() {
		return judgeWeek(lastWeekStart());
	}

	// 승인 회원 전원의 weekStart 주를 판정한다. 확정된 회원은 건너뛰고 보류 회원은 다시 돈다 (E-35)
	public JudgmentSummary judgeWeek(LocalDate weekStart) {
		return judgeMembers(sourceRepository.findActiveMembers(), weekStart);
	}

	// 승인 회원 중 weekStart 주의 판정 행이 없는 회원만 판정한다. 행이 있는 확정·보류 회원은 건드리지 않는다 (E-34, E-35)
	public JudgmentSummary judgeMissingMembers(LocalDate weekStart) {
		List<MemberRef> missing = sourceRepository.findActiveMembers().stream()
				.filter(member -> judgmentRepository.findByMemberIdAndWeekStart(member.id(), weekStart).isEmpty())
				.toList();
		return judgeMembers(missing, weekStart);
	}

	// 주어진 회원들의 weekStart 주를 회원마다 따로 판정하고 결과를 센다
	private JudgmentSummary judgeMembers(List<MemberRef> members, LocalDate weekStart) {
		Counter counter = new Counter(members.size());
		for (MemberRef member : members) {
			try {
				counter.add(memberJudgmentService.judge(member.id(), weekStart, false));
			}
			catch (RuntimeException e) {
				counter.failed++;
				log.error("판정 중 오류 memberId={} weekStart={}", member.id(), weekStart, e);
			}
		}
		JudgmentSummary summary = counter.toSummary();
		log.info("주간 판정 완료 weekStart={} {}", weekStart, summary);
		return summary;
	}

	// 보류 자동 재시도 한 번. API 오류 사유이고 횟수가 남은 건만 다시 돌고, 아이디 불일치는 재시도하지 않는다 (Q-07, Q-09)
	public JudgmentSummary retryHolds() {
		List<WeeklyJudgment> targets = judgmentRepository
				.findByStatusAndHoldReasonAndRetryCountLessThanOrderByWeekStartAscMemberIdAsc(JudgmentStatus.HOLD,
						HoldReason.API_ERROR, WeeklyJudgment.MAX_AUTO_RETRIES);
		Counter counter = new Counter(targets.size());
		for (WeeklyJudgment target : targets) {
			try {
				counter.add(memberJudgmentService.judge(target.getMemberId(), target.getWeekStart(), true));
			}
			catch (RuntimeException e) {
				counter.failed++;
				log.error("보류 재시도 중 오류 memberId={} weekStart={}", target.getMemberId(), target.getWeekStart(), e);
			}
		}
		if (!targets.isEmpty()) {
			log.info("보류 자동 재시도 완료 {}", counter.toSummary());
		}
		return counter.toSummary();
	}

	private static final class Counter {

		private final int total;
		private int created;
		private int updated;
		private int skippedConfirmed;
		private int hold;
		private int failed;

		Counter(int total) {
			this.total = total;
		}

		void add(Outcome outcome) {
			switch (outcome.type()) {
				case CREATED -> created++;
				case UPDATED -> updated++;
				case SKIPPED_CONFIRMED -> skippedConfirmed++;
			}
			if (outcome.judgment().getStatus() == JudgmentStatus.HOLD) {
				hold++;
			}
		}

		JudgmentSummary toSummary() {
			return new JudgmentSummary(total, created, updated, skippedConfirmed, hold, failed);
		}

	}

}
