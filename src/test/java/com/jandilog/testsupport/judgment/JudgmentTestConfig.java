package com.jandilog.testsupport.judgment;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.jandilog.testsupport.auth.MutableClock;

// 판정 통합 테스트용 빈. 시계는 조절 가능한 것으로, 잔디 조회는 GitHub 대신 가짜로 바꾼다
@TestConfiguration(proxyBeanMethods = false)
public class JudgmentTestConfig {

	@Bean
	@Primary
	MutableClock judgmentTestClock() {
		return new MutableClock();
	}

	@Bean
	@Primary
	FakeGrassClient fakeGrassClient() {
		return new FakeGrassClient();
	}

}
