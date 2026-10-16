package com.jandilog.common.config;

import java.util.concurrent.TimeUnit;

import org.springframework.boot.autoconfigure.mongo.MongoClientSettingsBuilderCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

// MongoDB 클라이언트 타임아웃 적용. AppConfig는 건드리지 않고 따로 등록한다
@Configuration
@EnableConfigurationProperties(MongoTimeoutProperties.class)
public class MongoTimeoutConfig {

	// 가장 먼저 적용한다. 뒤에 오는 URI 설정(applyConnectionString)이 URI에 적힌 옵션만 덮어쓰므로
	// MONGODB_URI의 connectTimeoutMS 같은 옵션은 그대로 살고, 적히지 않은 항목만 이 값을 쓴다
	@Bean
	@Order(Ordered.HIGHEST_PRECEDENCE)
	MongoClientSettingsBuilderCustomizer mongoTimeoutCustomizer(MongoTimeoutProperties timeouts) {
		return builder -> {
			builder.applyToSocketSettings(socket -> socket
					.connectTimeout(timeouts.connectTimeout().toMillis(), TimeUnit.MILLISECONDS)
					.readTimeout(timeouts.socketTimeout().toMillis(), TimeUnit.MILLISECONDS));
			builder.applyToClusterSettings(cluster -> cluster
					.serverSelectionTimeout(timeouts.serverSelectionTimeout().toMillis(), TimeUnit.MILLISECONDS));
			builder.applyToConnectionPoolSettings(pool -> pool
					.maxWaitTime(timeouts.poolWaitTimeout().toMillis(), TimeUnit.MILLISECONDS));
		};
	}

}
