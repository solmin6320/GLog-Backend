package com.jandilog.judgment.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.jandilog.judgment.domain.JudgmentWeek;
import com.jandilog.judgment.repository.JudgmentHistoryRepository;

// 기동 시 미판정 주차 따라잡기 (화면설계서 E-34·FC-02). 월요일 판정 시각에 서버가 꺼져 있었으면 기동할 때 빠진 주차를 판정한다.
// 판정 자체는 스케줄러와 같은 WeeklyJudgmentBatchService.judgeWeek를 쓰고(같은 잔디 캐시·같은 멱등 규칙), 새 GitHub 호출 경로는 없다.
// 같은 주를 다시 돌려도 (회원+주차시작일) 멱등이라 중복 판정이 생기지 않는다 (E-35)
@Service
public class JudgmentCatchUpService {

	private static final Logger log = LoggerFactory.getLogger(JudgmentCatchUpService.class);
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	// 주간 판정 스케줄러 기본 시각(월요일 07:00)과 같은 값. 그 주 다음 월요일 이 시각부터 판정할 때가 된 주다
	static final LocalTime JUDGMENT_TIME = LocalTime.of(7, 0);
	// 한 번의 기동에서 따라잡는 최대 주 수 (구현 기본값). 오래 멈춘 서버나 낡은 DB로 기동해도 경고와 GitHub 호출이 한꺼번에 쏟아지지 않게 한다
	static final int MAX_CATCH_UP_WEEKS = 4;

	// judgedWeeks: 이번 기동에서 판정한 주차(오래된 순), leftWeeks: 한도를 넘어 다음 기동으로 남긴 주차
	public record Result(List<LocalDate> judgedWeeks, List<LocalDate> leftWeeks) {

		public Result {
			judgedWeeks = List.copyOf(judgedWeeks);
			leftWeeks = List.copyOf(leftWeeks);
		}

	}

	private final WeeklyJudgmentBatchService batchService;
	private final JudgmentHistoryRepository historyRepository;
	private final Clock clock;

	public JudgmentCatchUpService(WeeklyJudgmentBatchService batchService, JudgmentHistoryRepository historyRepository,
			Clock clock) {
		this.batchService = batchService;
		this.historyRepository = historyRepository;
		this.clock = clock;
	}

	// 판정 이력이 있는 가장 최근 주차 뒤로 빠진 주차를 오래된 순으로 판정한다. 이력이 하나도 없는 새 DB에서는 아무것도 하지 않는다
	public Result catchUp() {
		Optional<LocalDate> latest = historyRepository.findLatestWeekStart();
		if (latest.isEmpty()) {
			log.info("판정 이력이 없어 기동 시 미판정 주차 확인을 건너뛰어요");
			return new Result(List.of(), List.of());
		}
		List<LocalDate> missing = missingWeeks(latest.get(), LocalDateTime.now(clock.withZone(KST)));
		if (missing.isEmpty()) {
			return new Result(List.of(), List.of());
		}

		List<LocalDate> targets = missing.size() > MAX_CATCH_UP_WEEKS ? missing.subList(0, MAX_CATCH_UP_WEEKS) : missing;
		List<LocalDate> left = missing.subList(targets.size(), missing.size());
		log.info("기동 시 미판정 주차를 판정해요 weeks={} left={}", targets, left);
		List<LocalDate> judged = new ArrayList<>();
		for (LocalDate week : targets) {
			batchService.judgeWeek(week);
			judged.add(week);
		}
		if (!left.isEmpty()) {
			log.warn("미판정 주차가 한도({}주)를 넘어 남겨요 left={}", MAX_CATCH_UP_WEEKS, left);
		}
		return new Result(judged, left);
	}

	// 판정 이력이 있는 가장 최근 주차 다음 주부터, 판정할 때가 지난 마지막 주차까지(오래된 순). 빠진 주가 없으면 빈 목록
	static List<LocalDate> missingWeeks(LocalDate latestJudgedWeek, LocalDateTime now) {
		LocalDate lastDue = lastDueWeek(now);
		List<LocalDate> weeks = new ArrayList<>();
		for (LocalDate week = latestJudgedWeek.plusWeeks(1); !week.isAfter(lastDue); week = week.plusWeeks(1)) {
			weeks.add(week);
		}
		return weeks;
	}

	// 지금 기준 판정 시각(그 주 다음 월요일 07:00)이 이미 지난 가장 최근 주차 월요일.
	// 월요일 07:00 전이면 직전 주는 아직 판정할 때가 아니므로 그 전 주다
	static LocalDate lastDueWeek(LocalDateTime now) {
		LocalDate thisWeek = JudgmentWeek.mondayOf(now.toLocalDate());
		return now.isBefore(thisWeek.atTime(JUDGMENT_TIME)) ? thisWeek.minusWeeks(2) : thisWeek.minusWeeks(1);
	}

}
