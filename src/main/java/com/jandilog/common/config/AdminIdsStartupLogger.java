package com.jandilog.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

// 기동이 끝나면 관리자 지정 id 개수를 INFO 한 줄로 남긴다. .env 형식 실수로 목록이 비어도 오류 없이 뜨기 때문에
// 운영자가 개수로 알아챌 수 있게 한다. id 값은 남기지 않는다
@Component
public class AdminIdsStartupLogger {

	private static final Logger log = LoggerFactory.getLogger(AdminIdsStartupLogger.class);

	private final AdminProperties adminProperties;

	public AdminIdsStartupLogger(AdminProperties adminProperties) {
		this.adminProperties = adminProperties;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void onApplicationReady() {
		int count = adminProperties.githubIdSet().size();
		if (count == 0) {
			log.info("관리자 지정 id가 0개예요. 로그인으로 관리자가 되는 계정이 없어요 (ADMIN_GITHUB_IDS)");
			return;
		}
		log.info("관리자 지정 id가 {}개 설정돼 있어요 (ADMIN_GITHUB_IDS, 중복 제외)", count);
	}

}
