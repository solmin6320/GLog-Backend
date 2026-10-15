package com.jandilog.testsupport.judgment;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.jandilog.testsupport.auth.MutableClock;

// 로컬 compose DB(MariaDB·Redis)를 쓰는 판정 통합 테스트 공통 바탕. 시계는 고정 가능, GitHub는 가짜
@SpringBootTest
@ActiveProfiles("test")
@Import(JudgmentTestConfig.class)
public abstract class JudgmentIntegrationTest {

	@Autowired
	protected JdbcTemplate jdbc;
	@Autowired
	protected StringRedisTemplate redis;
	@Autowired
	protected MutableClock clock;
	@Autowired
	protected FakeGrassClient fakeGrass;

	protected JudgmentFixture fixture;

	@BeforeEach
	protected void setUpJudgmentSupport() {
		fixture = new JudgmentFixture(jdbc, redis);
		fakeGrass.reset();
	}

	@AfterEach
	protected void tearDownJudgmentSupport() {
		clock.reset();
		fixture.cleanup();
	}

}
