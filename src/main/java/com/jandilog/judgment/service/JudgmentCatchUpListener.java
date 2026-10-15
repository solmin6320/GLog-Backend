package com.jandilog.judgment.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

// 기동이 끝나면 미판정 주차 따라잡기를 한 번 부른다 (E-34). 결정은 JudgmentCatchUpService가 한다.
// 통합 테스트 컨텍스트가 뜰 때마다 로컬 DB 회원을 판정하지 않도록 test 프로필과 스케줄러를 끈 환경에서는 등록하지 않는다
@Component
@Profile("!test")
@ConditionalOnProperty(name = "jandilog.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class JudgmentCatchUpListener {

	private static final Logger log = LoggerFactory.getLogger(JudgmentCatchUpListener.class);

	private final JudgmentCatchUpService catchUpService;

	public JudgmentCatchUpListener(JudgmentCatchUpService catchUpService) {
		this.catchUpService = catchUpService;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void onApplicationReady() {
		try {
			catchUpService.catchUp();
		}
		catch (RuntimeException e) {
			// 판정을 못 따라잡아도 서버는 계속 뜬다. 다음 기동이나 월요일 판정이 이어서 처리한다
			log.error("기동 시 미판정 주차 판정에 실패했어요", e);
		}
	}

}
