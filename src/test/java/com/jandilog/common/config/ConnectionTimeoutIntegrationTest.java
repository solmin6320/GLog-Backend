package com.jandilog.common.config;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.time.Duration;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.Shutdown;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.test.context.ActiveProfiles;

import com.mongodb.client.MongoClient;
import com.mongodb.client.internal.MongoClientImpl;
import com.zaxxer.hikari.HikariDataSource;

// 타임아웃·graceful 설정이 실제 DB·Redis·Mongo 클라이언트에 반영됐는지 확인 (로컬 docker-compose 사용)
@SpringBootTest
@ActiveProfiles("test")
class ConnectionTimeoutIntegrationTest {

	@Autowired
	private DataSource dataSource;
	@Autowired
	private RedisConnectionFactory redisConnectionFactory;
	@Autowired
	private MongoClient mongoClient;
	@Autowired
	private ServerProperties serverProperties;

	@Test
	void Hikari_풀_대기와_MariaDB_연결_응답_상한이_적용된다() throws Exception {
		HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
		assertThat(hikari.getConnectionTimeout()).isEqualTo(10_000);

		// 드라이버가 socketTimeout을 받았는지는 연결의 네트워크 타임아웃으로 확인한다
		try (Connection connection = dataSource.getConnection()) {
			assertThat(connection.getNetworkTimeout()).isEqualTo(60_000);
		}
	}

	@Test
	void Redis_명령과_연결_타임아웃이_적용된다() {
		LettuceConnectionFactory factory = (LettuceConnectionFactory) redisConnectionFactory;

		assertThat(factory.getClientConfiguration().getCommandTimeout()).isEqualTo(Duration.ofSeconds(5));
		assertThat(factory.getClientConfiguration().getClientOptions().orElseThrow().getSocketOptions()
				.getConnectTimeout()).isEqualTo(Duration.ofSeconds(5));
	}

	@Test
	void Mongo_클라이언트_타임아웃이_적용된다() {
		var settings = ((MongoClientImpl) mongoClient).getSettings();

		assertThat(settings.getSocketSettings().getConnectTimeout(MILLISECONDS)).isEqualTo(5_000);
		assertThat(settings.getSocketSettings().getReadTimeout(MILLISECONDS)).isEqualTo(30_000);
		assertThat(settings.getClusterSettings().getServerSelectionTimeout(MILLISECONDS)).isEqualTo(10_000);
		assertThat(settings.getConnectionPoolSettings().getMaxWaitTime(MILLISECONDS)).isEqualTo(10_000);
	}

	@Test
	void 웹_서버는_graceful_shutdown이다() {
		assertThat(serverProperties.getShutdown()).isEqualTo(Shutdown.GRACEFUL);
	}

}
