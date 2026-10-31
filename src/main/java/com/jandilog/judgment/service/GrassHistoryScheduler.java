package com.jandilog.judgment.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.jandilog.judgment.repository.JudgmentSourceRepository;
import com.jandilog.judgment.repository.JudgmentSourceRepository.MemberRef;

// 활성 회원의 프로필 잔디 전체 이력을 판정용 잔디 갱신과 같은 시각(새벽 6시)에 다시 불러온다.
// 한 회원이 실패해도 나머지는 이어서 하고, 실패한 회원의 프로필은 판정용 2주 캐시로 보인다
@Component
public class GrassHistoryScheduler {

	private static final Logger log = LoggerFactory.getLogger(GrassHistoryScheduler.class);

	private final JudgmentSourceRepository sourceRepository;
	private final GrassHistoryCacheService historyCacheService;

	public GrassHistoryScheduler(JudgmentSourceRepository sourceRepository,
			GrassHistoryCacheService historyCacheService) {
		this.sourceRepository = sourceRepository;
		this.historyCacheService = historyCacheService;
	}

	public record Summary(int total, int refreshed, int failed) {
	}

	@Scheduled(cron = "${jandilog.judgment.grass-refresh-cron:0 0 6 * * *}", zone = "Asia/Seoul")
	public void scheduledRefresh() {
		try {
			refreshAll();
		}
		catch (RuntimeException e) {
			log.error("잔디 전체 이력 갱신 작업이 실패했어요", e);
		}
	}

	public Summary refreshAll() {
		List<MemberRef> members = sourceRepository.findActiveMembers();
		int refreshed = 0;
		int failed = 0;
		for (MemberRef member : members) {
			try {
				historyCacheService.refresh(member.id(), member.githubId(), member.githubLogin());
				refreshed++;
			}
			catch (GrassFetchException e) {
				failed++;
				log.warn("잔디 전체 이력 갱신 실패 memberId={} reason={}", member.id(), e.getReason());
			}
			catch (RuntimeException e) {
				failed++;
				log.error("잔디 전체 이력 갱신 중 예기치 못한 오류 memberId={}", member.id(), e);
			}
		}
		log.info("잔디 전체 이력 갱신 완료 total={} refreshed={} failed={}", members.size(), refreshed, failed);
		return new Summary(members.size(), refreshed, failed);
	}

}
