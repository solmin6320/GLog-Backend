package com.jandilog.common.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class AppConfig {

	// 앱이 DB에 넣는 시각은 항상 KST (DB명세서 1-0)
	@Bean
	Clock clock() {
		return Clock.system(ZoneId.of("Asia/Seoul"));
	}

}
