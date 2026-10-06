package com.jandilog.common.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// MongoDB 타임아웃 설정. MONGODB_URI에 같은 옵션이 있으면 URI 값이 우선한다
@ConfigurationProperties(prefix = "jandilog.mongo")
public record MongoTimeoutProperties(
		@DefaultValue("5s") Duration connectTimeout,
		@DefaultValue("30s") Duration socketTimeout,
		@DefaultValue("10s") Duration serverSelectionTimeout,
		@DefaultValue("10s") Duration poolWaitTimeout) {

	public MongoTimeoutProperties {
		requirePositive("connect-timeout", connectTimeout);
		requirePositive("socket-timeout", socketTimeout);
		requirePositive("server-selection-timeout", serverSelectionTimeout);
		requirePositive("pool-wait-timeout", poolWaitTimeout);
	}

	private static void requirePositive(String name, Duration value) {
		if (value == null || value.isNegative() || value.isZero()) {
			throw new IllegalArgumentException("jandilog.mongo." + name + "은 0보다 커야 해요");
		}
	}

}
