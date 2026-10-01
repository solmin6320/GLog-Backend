package com.jandilog.testsupport.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jandilog.common.config.AuthProperties;

// 로컬 docker-compose DB(MariaDB·Redis)와 실제 HTTP 서버를 쓰는 통합 테스트 공통 바탕
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(AuthTestConfig.class)
public abstract class AuthIntegrationTest {

	@LocalServerPort
	private int port;

	@Autowired
	protected JdbcTemplate jdbc;
	@Autowired
	protected StringRedisTemplate redis;
	@Autowired
	protected ObjectMapper objectMapper;
	@Autowired
	protected MutableClock clock;
	@Autowired
	protected AuthProperties authProperties;

	protected MemberFixture members;
	protected GraphQlHttpClient graphQl;

	@BeforeEach
	protected void setUpSupport() {
		members = new MemberFixture(jdbc, redis);
		graphQl = new GraphQlHttpClient(port, objectMapper);
	}

	@AfterEach
	protected void tearDownSupport() {
		clock.reset();
		members.cleanup();
	}

}
