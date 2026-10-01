package com.jandilog.common.config;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.jandilog.common.security.ClientAwareAuthorizationRequestRepository;
import com.jandilog.common.security.MemberJwtAuthenticationConverter;
import com.jandilog.common.security.OAuthLoginFailureHandler;
import com.jandilog.common.security.OAuthLoginSuccessHandler;
import com.jandilog.common.security.SecurityErrorResponder;

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

	// 그 밖의 모든 요청: 세션 없이 Bearer JWT만 검사한다. 허용 목록 밖은 ACTIVE 회원(ROLE_MEMBER)만 통과.
	// /graphql은 일회용 코드 교환이 비로그인이라 URL에서는 열고, 권한은 @PreAuthorize와 RootFieldAccessGuard가 맡는다
	@Bean
	@Order(2)
	SecurityFilterChain apiFilterChain(HttpSecurity http, JwtDecoder jwtDecoder,
			MemberJwtAuthenticationConverter jwtConverter, SecurityErrorResponder errorResponder) throws Exception {
		http.cors(Customizer.withDefaults())
				// 쿠키 인증을 쓰지 않는 Bearer 전용 API
				.csrf(AbstractHttpConfigurer::disable)
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers(EndpointRequest.to(HealthEndpoint.class)).permitAll()
						.requestMatchers("/graphql", "/error").permitAll()
						.anyRequest().hasRole("MEMBER"))
				.oauth2ResourceServer(resourceServer -> resourceServer
						.jwt(jwt -> jwt.decoder(jwtDecoder).jwtAuthenticationConverter(jwtConverter))
						.authenticationEntryPoint(errorResponder)
						.accessDeniedHandler(errorResponder))
				.exceptionHandling(handling -> handling
						.authenticationEntryPoint(errorResponder)
						.accessDeniedHandler(errorResponder));
		return http.build();
	}

	// CORS는 웹 도메인 하나만 허용한다. 앱은 출처 개념이 없어 해당 없음 (기능명세서 10장)
	@Bean
	CorsConfigurationSource corsConfigurationSource(AuthProperties properties) {
		URI web = URI.create(properties.webBaseUrl());
		String origin = web.getScheme() + "://" + web.getAuthority();

		CorsConfiguration cors = new CorsConfiguration();
		cors.setAllowedOrigins(List.of(origin));
		cors.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
		cors.setAllowedHeaders(List.of("Authorization", "Content-Type"));
		cors.setAllowCredentials(false);
		cors.setMaxAge(Duration.ofHours(1));

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", cors);
		return source;
	}

}
