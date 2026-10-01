package com.jandilog.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import com.jandilog.testsupport.auth.TestTokens;

// JWT 검증 규칙: 정상 한 가지만 통과하고, 만료·다른 키·다른 알고리즘·alg=none·다른 발급자·필수 클레임 누락은 모두 거부 (E-03)
class JwtDecoderTest {

	private static final byte[] SECRET = ("jwt-decoder-unit-test-secret-key-0123456789-abcdefghijklmnopqrstuvwxyz")
			.getBytes(StandardCharsets.UTF_8);
	private static final byte[] OTHER_SECRET = ("another-secret-key-for-unit-test-0123456789-abcdefghijklmnopqrstuvwxyz")
			.getBytes(StandardCharsets.UTF_8);
	private static final String ISSUER = "jandilog";

	private static KeyPair rsaKeys;

	private final JwtDecoder decoder = new JwtConfig().jwtDecoder(new AuthProperties(
			"https://jandi.example.com", "jandilog://auth",
			new AuthProperties.Jwt(new String(SECRET, StandardCharsets.UTF_8), ISSUER, 60)));

	@BeforeAll
	static void generateRsaKeys() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		rsaKeys = generator.generateKeyPair();
	}

	private static Map<String, Object> validClaims() {
		Instant now = Instant.now();
		return TestTokens.claims("42", ISSUER, now, now.plus(Duration.ofHours(1)));
	}

	private void assertRejected(String token) {
		assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
	}

	@Test
	void 올바른_키와_HS256과_발급자와_만료_전이면_통과한다() {
		Jwt jwt = decoder.decode(TestTokens.hs256(SECRET, validClaims()));

		assertThat(jwt.getSubject()).isEqualTo("42");
		assertThat(jwt.getClaimAsString("iss")).isEqualTo(ISSUER);
	}

	@Test
	void 만료된_토큰은_거부한다() {
		Instant now = Instant.now();

		assertRejected(TestTokens.hs256(SECRET,
				TestTokens.claims("42", ISSUER, now.minus(Duration.ofHours(2)), now.minus(Duration.ofMinutes(5)))));
	}

	@Test
	void 아직_유효하지_않은_토큰은_거부한다() {
		Instant now = Instant.now();
		Map<String, Object> claims = TestTokens.claims("42", ISSUER, now, now.plus(Duration.ofHours(2)));
		claims.put("nbf", now.plus(Duration.ofHours(1)).getEpochSecond());

		assertRejected(TestTokens.hs256(SECRET, claims));
	}

	@Test
	void 다른_키로_서명한_토큰은_거부한다() {
		assertRejected(TestTokens.hs256(OTHER_SECRET, validClaims()));
	}

	@Test
	void 서명이_빈_alg_none_토큰은_거부한다() {
		assertRejected(TestTokens.none(validClaims()));
	}

	@ParameterizedTest
	@ValueSource(strings = {"HS384", "HS512"})
	void 같은_키라도_HS256이_아닌_알고리즘은_거부한다(String algorithm) {
		assertRejected(TestTokens.hmac(algorithm, SECRET, validClaims()));
	}

	@Test
	void RSA_알고리즘으로_서명한_토큰은_거부한다() {
		assertRejected(TestTokens.rs256(rsaKeys.getPrivate(), validClaims()));
	}

	@Test
	void 발급자가_다르면_거부한다() {
		Instant now = Instant.now();

		assertRejected(TestTokens.hs256(SECRET,
				TestTokens.claims("42", "someone-else", now, now.plus(Duration.ofHours(1)))));
	}

	@Test
	void 발급자가_없으면_거부한다() {
		Instant now = Instant.now();

		assertRejected(TestTokens.hs256(SECRET, TestTokens.claims("42", null, now, now.plus(Duration.ofHours(1)))));
	}

	@Test
	void sub가_없으면_거부한다() {
		Instant now = Instant.now();

		assertRejected(TestTokens.hs256(SECRET, TestTokens.claims(null, ISSUER, now, now.plus(Duration.ofHours(1)))));
	}

	@Test
	void exp가_없으면_영원히_유효한_토큰이므로_거부한다() {
		assertRejected(TestTokens.hs256(SECRET, TestTokens.claims("42", ISSUER, Instant.now(), null)));
	}

	@Test
	void 서명_뒤에_본문을_바꾼_토큰은_거부한다() {
		String token = TestTokens.hs256(SECRET, validClaims());
		Instant now = Instant.now();

		String tampered = TestTokens.withPayload(token,
				TestTokens.claims("1", ISSUER, now, now.plus(Duration.ofDays(365))));

		assertRejected(tampered);
	}

	@Test
	void 서명을_떼어낸_토큰은_거부한다() {
		String token = TestTokens.hs256(SECRET, validClaims());

		assertRejected(token.substring(0, token.lastIndexOf('.') + 1));
		assertRejected(token.substring(0, token.lastIndexOf('.')));
	}

	@ParameterizedTest
	@ValueSource(strings = {"", " ", "abc", "a.b", "a.b.c", "....", "Bearer abc.def.ghi", "null"})
	void 토큰_모양이_아닌_문자열은_거부한다(String token) {
		assertRejected(token);
	}

}
