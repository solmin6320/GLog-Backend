package com.jandilog.notice.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

// 기동할 때 시스템 공지를 보장한다. 공유 DB를 쓰는 테스트가 임시 관리자 회원에 공지를 묶지 않도록 test 프로필에서는 돌지 않는다
@Component
@Profile("!test")
public class SystemNoticeInitializer implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(SystemNoticeInitializer.class);

	private final SystemNoticeService systemNoticeService;

	public SystemNoticeInitializer(SystemNoticeService systemNoticeService) {
		this.systemNoticeService = systemNoticeService;
	}

	@Override
	public void run(ApplicationArguments args) {
		// 공지 하나 때문에 앱 기동이 막히지 않게 한다
		try {
			systemNoticeService.ensureExists();
		} catch (RuntimeException e) {
			log.error("잔디 인정 규칙 공지 초기화에 실패했어요.", e);
		}
	}

}
