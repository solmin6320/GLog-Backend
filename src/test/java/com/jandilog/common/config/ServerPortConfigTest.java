package com.jandilog.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

// SERVER_PORT 변수로 서버 포트를 바꿀 수 있는지 application.yml 값으로 확인한다
class ServerPortConfigTest {

	private static MockEnvironment environment(String serverPort) {
		MockEnvironment env = new MockEnvironment();
		if (serverPort != null) {
			env.setProperty("SERVER_PORT", serverPort);
		}
		try {
			for (PropertySource<?> source : new YamlPropertySourceLoader().load("base", new ClassPathResource("application.yml"))) {
				env.getPropertySources().addLast(source);
			}
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		return env;
	}

	@Test
	@DisplayName("SERVER_PORT가 없으면 8080")
	void defaultsTo8080() {
		assertThat(environment(null).getProperty("server.port")).isEqualTo("8080");
	}

	@Test
	@DisplayName("SERVER_PORT를 주면 그 값")
	void usesServerPortVariable() {
		assertThat(environment("8081").getProperty("server.port")).isEqualTo("8081");
	}

}
