package com.jandilog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.test.context.ActiveProfiles;

// test 프로필에서 GitHub·JWT 더미 값을 받는다 (환경변수 없이도 기동)
@SpringBootTest
@ActiveProfiles("test")
class JandilogApplicationTests {

	@Autowired
	ConfigurableEnvironment environment;

	// 컨텍스트 로딩 스모크: DB 연결과 Flyway 적용까지 확인
	@Test
	void contextLoads() {
	}

	// 로컬 .env가 있어도 test 프로필은 읽지 않는다 (SESSION_COOKIE_SECURE 등이 테스트를 바꾸지 않게)
	@Test
	void test_프로필은_로컬_env를_설정으로_읽지_않는다() {
		assertThat(environment.getPropertySources().stream().map(PropertySource::getName))
				.noneMatch(name -> name.contains(".env"));
	}

}
