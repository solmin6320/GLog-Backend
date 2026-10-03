package com.jandilog.testsupport.admin;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

// 관리자 통합 테스트용 빈. GitHub 잔디 클라이언트를 가짜로 바꿔 외부 호출을 막는다 (GitHubGrassClient보다 우선)
@TestConfiguration(proxyBeanMethods = false)
public class AdminTestConfig {

	@Bean
	@Primary
	StubGrassClient stubGrassClient() {
		return new StubGrassClient();
	}

}
