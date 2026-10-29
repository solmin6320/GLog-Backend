package com.jandilog.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

// 실제 앱 컨텍스트에서 ADMIN_GITHUB_IDS 환경변수 이름이 개수 로그까지 이어지는지 확인한다.
// 다른 시험과 컨텍스트가 겹치지 않게 이 시험만의 값(중복 포함)으로 띄운다
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = "ADMIN_GITHUB_IDS=7200000000001, 7200000000002, 7200000000001")
class AdminIdsStartupLoggerIntegrationTest {

	@Autowired
	private AdminProperties adminProperties;
	@Autowired
	private AdminIdsStartupLogger startupLogger;

	private Logger logger;
	private ListAppender<ILoggingEvent> logs;

	@BeforeEach
	void attachLogs() {
		logger = (Logger) LoggerFactory.getLogger(AdminIdsStartupLogger.class);
		logs = new ListAppender<>();
		logs.start();
		logger.addAppender(logs);
	}

	@AfterEach
	void detachLogs() {
		logger.detachAppender(logs);
		logs.stop();
	}

	@Test
	void 환경변수_값이_중복을_뺀_개수로_읽힌다() {
		assertThat(adminProperties.githubIdSet()).containsExactlyInAnyOrder(7_200_000_000_001L, 7_200_000_000_002L);
	}

	@Test
	void 앱에_등록된_리스너가_중복을_뺀_개수를_INFO로_남기고_id는_남기지_않는다() {
		startupLogger.onApplicationReady();

		assertThat(logs.list).hasSize(1);
		ILoggingEvent event = logs.list.get(0);
		assertThat(event.getLevel()).isEqualTo(Level.INFO);
		assertThat(event.getFormattedMessage()).contains("관리자 지정 id가 2개").doesNotContain("7200000000001")
				.doesNotContain("7200000000002");
	}

}
