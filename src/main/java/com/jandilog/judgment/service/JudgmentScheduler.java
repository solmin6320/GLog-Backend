package com.jandilog.judgment.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 판정 관련 정기 작업 (기능명세서 5장). 시각은 모두 Asia/Seoul이며 기본값은 구현 기본값이다:
// 매일 06:00 잔디 캐시 갱신, 월요일 07:00 직전 주 판정(갱신 이후), 10분마다 보류 자동 재시도(상한 3회).
// 같은 스케줄러 스레드에서 차례로 돌아 갱신과 판정이 겹치지 않는다
@Component
@ConditionalOnProperty(name = "jandilog.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class JudgmentScheduler {

	private static final Logger log = LoggerFactory.getLogger(JudgmentScheduler.class);

	private final WeeklyJudgmentBatchService batchService;

	public JudgmentScheduler(WeeklyJudgmentBatchService batchService) {
		this.batchService = batchService;
	}

	@Scheduled(cron = "${jandilog.judgment.grass-refresh-cron:0 0 6 * * *}", zone = "Asia/Seoul")
	public void refreshGrass() {
		run("잔디 갱신", batchService::refreshGrass);
	}

	@Scheduled(cron = "${jandilog.judgment.weekly-cron:0 0 7 * * MON}", zone = "Asia/Seoul")
	public void judgeLastWeek() {
		run("주간 판정", batchService::judgeLastWeek);
	}

	@Scheduled(cron = "${jandilog.judgment.retry-cron:0 */10 * * * *}", zone = "Asia/Seoul")
	public void retryHolds() {
		run("보류 재시도", batchService::retryHolds);
	}

	// 한 작업이 예외로 끝나도 다음 실행은 이어진다
	private void run(String name, Runnable task) {
		try {
			task.run();
		}
		catch (RuntimeException e) {
			log.error("{} 작업이 실패했어요", name, e);
		}
	}

}
