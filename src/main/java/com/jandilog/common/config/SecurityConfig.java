package com.jandilog.common.config;

import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

import com.jandilog.common.security.ClientAwareAuthorizationRequestRepository;
import com.jandilog.common.security.OAuthLoginFailureHandler;
import com.jandilog.common.security.OAuthLoginSuccessHandler;

@Configuration
@EnableWebSecurity
// 권한은 메서드 단위 @PreAuthorize (기능명세서 10장)
@EnableMethodSecurity
public class SecurityConfig {

	// GitHub OAuth 시작·콜백 경로. 인증 요청을 잇는 동안만 세션을 쓴다
	@Bean
	@Order(1)
	SecurityFilterChain oauthLoginFilterChain(HttpSecurity http, OAuthLoginSuccessHandler successHandler,
			OAuthLoginFailureHandler failureHandler) throws Exception {
		http.securityMatcher("/oauth2/**", "/login/oauth2/**")
				.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
				.oauth2Login(oauth -> oauth
						.authorizationEndpoint(endpoint -> endpoint
								.authorizationRequestRepository(new ClientAwareAuthorizationRequestRepository()))
						.successHandler(successHandler)
						.failureHandler(failureHandler));
		return http.build();
	}

	// 임시 골격: 헬스체크만 허용하고 나머지는 차단. JWT 검증 체인은 다음 커밋에서 교체
	@Bean
	@Order(2)
	SecurityFilterChain apiFilterChain(HttpSecurity http) throws Exception {
		http.authorizeHttpRequests(auth -> auth
				.requestMatchers(EndpointRequest.to(HealthEndpoint.class)).permitAll()
				.anyRequest().denyAll());
		return http.build();
	}

}
