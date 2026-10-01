package com.jandilog.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtException;

import com.jandilog.common.security.JwtTokenService;
import com.jandilog.common.security.JwtTokenService.IssuedToken;

// 액세스 토큰 발급: HS256, sub=회원 id, iss, iat·exp는 Clock 기준 (Refresh Token 없음)
class JwtIssueAndVerifyTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final String SECRET = "jwt-token-service-unit-test-secret-0123456789";

	private final AuthProperties properties = new AuthProperties("https://jandi.example.com", "jandilog://auth",
			new AuthProperties.Jwt(SECRET, "jandilog", 90));
	private final JwtConfig config = new JwtConfig();
	private final JwtEncoder encoder = config.jwtEncoder(properties);
	private final JwtDecoder decoder = config.jwtDecoder(properties);

	private JwtTokenService serviceAt(Instant now) {
		return new JwtTokenService(encoder, properties, Clock.fixed(now, KST));
	}

	@Test
	void 발급한_토큰은_검증을_통과하고_회원_id가_sub다() {
		Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

		IssuedToken token = serviceAt(now).issue(12345L);

		Jwt jwt = decoder.decode(token.value());
		assertThat(jwt.getSubject()).isEqualTo("12345");
		assertThat(jwt.getClaimAsString("iss")).isEqualTo("jandilog");
	}

	@Test
	void iat는_현재_시각이고_exp는_설정한_분_뒤다() {
		Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

		IssuedToken token = serviceAt(now).issue(1L);

		Jwt jwt = decoder.decode(token.value());
		assertThat(jwt.getIssuedAt()).isEqualTo(now);
		assertThat(jwt.getExpiresAt()).isEqualTo(now.plus(Duration.ofMinutes(90)));
		assertThat(token.expiresInSeconds()).isEqualTo(90 * 60);
	}

	@Test
	void 클레임은_iss_sub_iat_exp뿐이고_상태나_역할은_담지_않는다() {
		IssuedToken token = serviceAt(Instant.now()).issue(7L);

		Jwt jwt = decoder.decode(token.value());

		assertThat(jwt.getClaims().keySet()).containsExactlyInAnyOrder("iss", "sub", "iat", "exp");
	}

	@Test
	void 헤더_알고리즘은_HS256이다() {
		IssuedToken token = serviceAt(Instant.now()).issue(7L);

		Jwt jwt = decoder.decode(token.value());

		assertThat(jwt.getHeaders().get("alg")).isEqualTo("HS256");
	}

	@Test
	void 이미_만료된_시각으로_발급하면_검증에서_거부된다() {
		Instant longAgo = Instant.now().minus(Duration.ofDays(2));

		IssuedToken token = serviceAt(longAgo).issue(7L);

		assertThatThrownBy(() -> decoder.decode(token.value())).isInstanceOf(JwtException.class);
	}

	@Test
	void 회원마다_다른_토큰이_나온다() {
		JwtTokenService service = serviceAt(Instant.now());

		assertThat(service.issue(1L).value()).isNotEqualTo(service.issue(2L).value());
	}

	@Test
	void 문자열로_찍어도_토큰_원문은_드러나지_않는다() {
		IssuedToken token = serviceAt(Instant.now()).issue(7L);

		assertThat(token.toString()).doesNotContain(token.value());
		assertThat(token.toString()).contains("expiresInSeconds=" + 90 * 60);
	}

}
