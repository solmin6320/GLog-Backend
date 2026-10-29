package com.jandilog.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

// 기동 완료 때 관리자 지정 id 개수를 INFO 한 줄로 남긴다. 개수는 중복 제거 후, id 값은 로그에 남기지 않는다
class AdminIdsStartupLoggerTest {

	@Configuration
	@EnableConfigurationProperties(AdminProperties.class)
	@Import(AdminIdsStartupLogger.class)
	static class Config {
	}

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

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

	private ILoggingEvent logOnce(String raw) {
		new AdminIdsStartupLogger(new AdminProperties(raw)).onApplicationReady();
		assertThat(logs.list).hasSize(1);
		return logs.list.get(0);
	}

	private static ApplicationReadyEvent readyEvent(AssertableApplicationContext context) {
		return new ApplicationReadyEvent(new SpringApplication(), new String[0], context.getSourceApplicationContext(),
				Duration.ZERO);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "   ", "\t"})
	void 목록이_비어_있으면_0개이고_관리자가_되는_계정이_없다고_남긴다(String raw) {
		ILoggingEvent event = logOnce(raw);

		assertThat(event.getLevel()).isEqualTo(Level.INFO);
		assertThat(event.getFormattedMessage()).contains("관리자 지정 id가 0개").contains("로그인으로 관리자가 되는 계정이 없어요");
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
			"7100000000001 | 1",
			"7100000000001, 7100000000002, 7100000000003 | 3",
			" 7100000000001 , 7100000000002 | 2",
			"7100000000001,7100000000001 | 1",
			"7100000000001,7100000000002,7100000000001,7100000000003,7100000000002 | 3"})
	void 중복을_뺀_개수를_INFO로_남긴다(String raw, int expected) {
		ILoggingEvent event = logOnce(raw);

		assertThat(event.getLevel()).isEqualTo(Level.INFO);
		assertThat(event.getFormattedMessage()).contains("관리자 지정 id가 " + expected + "개")
				.doesNotContain("계정이 없어요");
	}

	@Test
	void id_숫자는_로그에_남기지_않는다() {
		String raw = "7100000000001, 7100000000002, 7100000000003";

		ILoggingEvent event = logOnce(raw);

		for (String id : new String[] {"7100000000001", "7100000000002", "7100000000003"}) {
			assertThat(event.getFormattedMessage()).doesNotContain(id);
			assertThat(event.getMessage()).doesNotContain(id);
			assertThat(event.getArgumentArray()).noneMatch(argument -> String.valueOf(argument).contains(id));
		}
		assertThat(event.getFormattedMessage()).doesNotContain(raw);
	}

	@Test
	void 값을_하나_넣어도_id를_남기지_않는다() {
		ILoggingEvent event = logOnce("7100000000001");

		assertThat(event.getFormattedMessage()).doesNotContain("7100000000001").contains("1개");
	}

	@Test
	void 한_줄로_남고_예외를_싣지_않는다() {
		ILoggingEvent event = logOnce("7100000000001,7100000000002");

		assertThat(event.getFormattedMessage()).doesNotContain("\n").doesNotContain("\r");
		assertThat(event.getThrowableProxy()).isNull();
	}

	// ----- 기동 시 이벤트 연결 -----

	@Test
	void 컨텍스트가_뜰_때가_아니라_기동_완료_이벤트에서_남긴다() {
		runner.withPropertyValues("jandilog.admin.github-ids=7100000000001, 7100000000002").run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(logs.list).isEmpty();

			context.publishEvent(readyEvent(context));

			assertThat(logs.list).hasSize(1);
			assertThat(logs.list.get(0).getLevel()).isEqualTo(Level.INFO);
			assertThat(logs.list.get(0).getFormattedMessage()).contains("관리자 지정 id가 2개")
					.doesNotContain("7100000000001").doesNotContain("7100000000002");
		});
	}

	@Test
	void 설정이_없으면_기동_완료_이벤트에서_0개로_남긴다() {
		runner.run(context -> {
			context.publishEvent(readyEvent(context));

			List<ILoggingEvent> events = logs.list;
			assertThat(events).hasSize(1);
			assertThat(events.get(0).getLevel()).isEqualTo(Level.INFO);
			assertThat(events.get(0).getFormattedMessage()).contains("관리자 지정 id가 0개")
					.contains("로그인으로 관리자가 되는 계정이 없어요");
		});
	}

	@Test
	void 설정_키를_빈_값으로_덮어쓴_경우도_0개로_남긴다() {
		runner.withPropertyValues("jandilog.admin.github-ids=").run(context -> {
			context.publishEvent(readyEvent(context));

			assertThat(logs.list).hasSize(1);
			assertThat(logs.list.get(0).getFormattedMessage()).contains("관리자 지정 id가 0개");
		});
	}

	@Test
	void 잘못된_항목이면_기동이_실패하고_개수_로그는_남기지_않는다() {
		runner.withPropertyValues("jandilog.admin.github-ids=7100000000001,octocat").run(context -> {
			assertThat(context).hasFailed();
			assertThat(logs.list).isEmpty();
		});
	}

}
