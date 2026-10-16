package com.jandilog.common.config;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.internal.MongoClientImpl;

// MongoDB 타임아웃이 실제 클라이언트 설정에 어떻게 반영되는지 Boot 자동 설정과 함께 확인한다 (서버 연결 없음)
class MongoTimeoutConfigTest {

	private static final String LOCAL_URI = "spring.data.mongodb.uri=mongodb://localhost:27017/jandilog";

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(MongoAutoConfiguration.class))
			.withUserConfiguration(MongoTimeoutConfig.class);

	private static MongoClientSettings settings(AssertableApplicationContext context) {
		return ((MongoClientImpl) context.getBean(MongoClient.class)).getSettings();
	}

	@Test
	void 설정이_없으면_보수적인_기본값을_쓴다() {
		runner.withPropertyValues(LOCAL_URI).run(context -> {
			MongoClientSettings settings = settings(context);

			assertThat(settings.getSocketSettings().getConnectTimeout(MILLISECONDS)).isEqualTo(5_000);
			assertThat(settings.getSocketSettings().getReadTimeout(MILLISECONDS)).isEqualTo(30_000);
			assertThat(settings.getClusterSettings().getServerSelectionTimeout(MILLISECONDS)).isEqualTo(10_000);
			assertThat(settings.getConnectionPoolSettings().getMaxWaitTime(MILLISECONDS)).isEqualTo(10_000);
		});
	}

	@Test
	void 설정_값을_바꾸면_그_값을_쓴다() {
		runner.withPropertyValues(LOCAL_URI, "jandilog.mongo.connect-timeout=2s", "jandilog.mongo.socket-timeout=45s",
				"jandilog.mongo.server-selection-timeout=3s", "jandilog.mongo.pool-wait-timeout=4s").run(context -> {
					MongoClientSettings settings = settings(context);

					assertThat(settings.getSocketSettings().getConnectTimeout(MILLISECONDS)).isEqualTo(2_000);
					assertThat(settings.getSocketSettings().getReadTimeout(MILLISECONDS)).isEqualTo(45_000);
					assertThat(settings.getClusterSettings().getServerSelectionTimeout(MILLISECONDS)).isEqualTo(3_000);
					assertThat(settings.getConnectionPoolSettings().getMaxWaitTime(MILLISECONDS)).isEqualTo(4_000);
				});
	}

	@Test
	void URI에_적힌_옵션은_그대로_두고_없는_항목만_채운다() {
		runner.withPropertyValues(
				"spring.data.mongodb.uri=mongodb://localhost:27017/jandilog?connectTimeoutMS=1234&socketTimeoutMS=4567")
				.run(context -> {
					MongoClientSettings settings = settings(context);

					// URI 옵션이 우선
					assertThat(settings.getSocketSettings().getConnectTimeout(MILLISECONDS)).isEqualTo(1_234);
					assertThat(settings.getSocketSettings().getReadTimeout(MILLISECONDS)).isEqualTo(4_567);
					// URI에 없는 항목은 이 설정 값
					assertThat(settings.getClusterSettings().getServerSelectionTimeout(MILLISECONDS)).isEqualTo(10_000);
					assertThat(settings.getConnectionPoolSettings().getMaxWaitTime(MILLISECONDS)).isEqualTo(10_000);
				});
	}

	@Test
	void URI의_서버_선택과_풀_대기_옵션도_우선한다() {
		runner.withPropertyValues(
				"spring.data.mongodb.uri=mongodb://localhost:27017/jandilog?serverSelectionTimeoutMS=7000&waitQueueTimeoutMS=8000")
				.run(context -> {
					MongoClientSettings settings = settings(context);

					assertThat(settings.getClusterSettings().getServerSelectionTimeout(MILLISECONDS)).isEqualTo(7_000);
					assertThat(settings.getConnectionPoolSettings().getMaxWaitTime(MILLISECONDS)).isEqualTo(8_000);
					assertThat(settings.getSocketSettings().getConnectTimeout(MILLISECONDS)).isEqualTo(5_000);
				});
	}

	@Test
	void 값이_0이하면_기동을_막는다() {
		runner.withPropertyValues(LOCAL_URI, "jandilog.mongo.socket-timeout=0s").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class);
		});
	}

}
