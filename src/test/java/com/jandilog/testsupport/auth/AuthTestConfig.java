package com.jandilog.testsupport.auth;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

// 앱의 Clock 대신 쓰는 조절 가능한 시계. 서비스·JWT 발급이 모두 이 시계를 따른다
@TestConfiguration(proxyBeanMethods = false)
public class AuthTestConfig {

	@Bean
	@Primary
	MutableClock testClock() {
		return new MutableClock();
	}

}
