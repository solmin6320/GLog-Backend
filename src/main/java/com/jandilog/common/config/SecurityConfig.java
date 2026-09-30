package com.jandilog.common.config;

import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
// 권한은 메서드 단위 @PreAuthorize (기능명세서 10장)
@EnableMethodSecurity
public class SecurityConfig {

	// 임시 골격: 헬스체크만 허용하고 나머지는 차단. 인증 로직은 이후 작업에서 교체
	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http.authorizeHttpRequests(auth -> auth
				.requestMatchers(EndpointRequest.to(HealthEndpoint.class)).permitAll()
				.anyRequest().denyAll());
		return http.build();
	}

}
