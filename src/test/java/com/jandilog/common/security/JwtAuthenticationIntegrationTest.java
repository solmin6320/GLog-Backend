package com.jandilog.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import com.jandilog.common.exception.ErrorCode;
import com.jandilog.member.service.AuthCodeService;
import com.jandilog.testsupport.auth.AuthIntegrationTest;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;
import com.jandilog.testsupport.auth.TestTokens;

// JWT 검증을 실제 HTTP 보안 필터 체인으로 확인한다. 정상 토큰만 통과하고 나머지는 사유 없이 401 (E-03)
class JwtAuthenticationIntegrationTest extends AuthIntegrationTest {

	private static final String ME = "{ me { id status role } }";

	@Autowired
	private AuthCodeService authCodeService;

	private byte[] secret;
	private String issuer;
	private long memberId;

	@BeforeEach
	void setUp() {
		secret = authProperties.jwt().secret().getBytes(StandardCharsets.UTF_8);
		issuer = authProperties.jwt().issuer();
		memberId = members.active();
	}

	private Map<String, Object> claims(String sub) {
		Instant now = Instant.now();
		return TestTokens.claims(sub, issuer, now, now.plus(Duration.ofHours(1)));
	}

	private void assertUnauthenticated(GraphQlResponse response) {
		assertThat(response.status()).isEqualTo(401);
		assertThat(response.errorCode()).isEqualTo("UNAUTHENTICATED");
		assertThat(response.errorMessage()).isEqualTo(ErrorCode.UNAUTHENTICATED.message());
		assertThat(response.errorClassification()).isEqualTo("UNAUTHORIZED");
		assertThat(response.header("WWW-Authenticate")).isEqualTo("Bearer");
		assertThat(response.header("Cache-Control")).isEqualTo("no-store");
		assertThat(response.dataIsNull()).isTrue();
		// 만료인지 위조인지 같은 사유를 알려주지 않는다
		assertThat(response.rawBody()).doesNotContainIgnoringCase("expired").doesNotContainIgnoringCase("signature")
				.doesNotContainIgnoringCase("jwt").doesNotContainIgnoringCase("algorithm");
	}

	@Test
	void 정상_토큰으로_me를_조회한다() {
		String token = TestTokens.hs256(secret, claims(Long.toString(memberId)));

		GraphQlResponse response = graphQl.post(token, ME);

		assertThat(response.status()).isEqualTo(200);
		assertThat(response.hasErrors()).isFalse();
		assertThat(response.data().path("me").path("id").asText()).isEqualTo(Long.toString(memberId));
		assertThat(response.data().path("me").path("status").asText()).isEqualTo("ACTIVE");
	}

	@Test
	void 만료된_토큰은_401이다() {
		Instant now = Instant.now();
		String token = TestTokens.hs256(secret, TestTokens.claims(Long.toString(memberId), issuer,
				now.minus(Duration.ofHours(25)), now.minus(Duration.ofMinutes(5))));

		assertUnauthenticated(graphQl.post(token, ME));
	}

	@Test
	void 서비스가_발급했어도_시계가_지나_만료되면_401이다() {
		// 설정된 만료(기본 12시간)보다 6시간 더 지난 시각으로 발급해 만료가 이미 지난 토큰을 만든다
		clock.fixAt(Instant.now().minus(Duration.ofMinutes(authProperties.jwt().expiresMinutes()))
				.minus(Duration.ofHours(6)));
		String code = authCodeService.issue(memberId);
		String token = graphQl.post(null,
				"mutation($c: String!) { exchangeAuthCode(code: $c) { accessToken } }", Map.of("c", code))
				.data().path("exchangeAuthCode").path("accessToken").asText();
		clock.reset();

		assertThat(token).isNotBlank();
		assertUnauthenticated(graphQl.post(token, ME));
	}

	@Test
	void 다른_키로_서명한_토큰은_401이다() {
		byte[] otherKey = "a-completely-different-secret-key-0123456789-xyz".getBytes(StandardCharsets.UTF_8);

		assertUnauthenticated(graphQl.post(TestTokens.hs256(otherKey, claims(Long.toString(memberId))), ME));
	}

	@Test
	void alg_none_토큰은_401이다() {
		assertUnauthenticated(graphQl.post(TestTokens.none(claims(Long.toString(memberId))), ME));
	}

	@ParameterizedTest
	@ValueSource(strings = {"HS384", "HS512"})
	void 같은_키라도_다른_HMAC_알고리즘이면_401이다(String algorithm) {
		assertUnauthenticated(graphQl.post(TestTokens.hmac(algorithm, secret, claims(Long.toString(memberId))), ME));
	}

	@Test
	void RSA로_서명한_토큰은_401이다() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		KeyPair keys = generator.generateKeyPair();

		assertUnauthenticated(graphQl.post(TestTokens.rs256(keys.getPrivate(), claims(Long.toString(memberId))), ME));
	}

	@Test
	void 발급자가_다르거나_없으면_401이다() {
		Instant now = Instant.now();
		String wrongIssuer = TestTokens.hs256(secret,
				TestTokens.claims(Long.toString(memberId), "someone-else", now, now.plus(Duration.ofHours(1))));
		String noIssuer = TestTokens.hs256(secret,
				TestTokens.claims(Long.toString(memberId), null, now, now.plus(Duration.ofHours(1))));

		assertUnauthenticated(graphQl.post(wrongIssuer, ME));
		assertUnauthenticated(graphQl.post(noIssuer, ME));
	}

	@Test
	void sub가_없는_토큰은_401이다() {
		Instant now = Instant.now();

		assertUnauthenticated(graphQl.post(
				TestTokens.hs256(secret, TestTokens.claims(null, issuer, now, now.plus(Duration.ofHours(1)))), ME));
	}

	@Test
	void exp가_없는_토큰은_401이다() {
		assertUnauthenticated(graphQl.post(
				TestTokens.hs256(secret, TestTokens.claims(Long.toString(memberId), issuer, Instant.now(), null)), ME));
	}

	@ParameterizedTest
	@ValueSource(strings = {"abc", "", " ", " 1", "1.5", "1e3", "-", "99999999999999999999"})
	void sub가_회원_id_형식이_아니면_401이다(String sub) {
		assertUnauthenticated(graphQl.post(TestTokens.hs256(secret, claims(sub)), ME));
	}

	@Test
	void 서명은_맞지만_없는_회원_id면_401이다() {
		assertUnauthenticated(graphQl.post(TestTokens.hs256(secret, claims("9223372036854775807")), ME));
		assertUnauthenticated(graphQl.post(TestTokens.hs256(secret, claims("0")), ME));
		assertUnauthenticated(graphQl.post(TestTokens.hs256(secret, claims("-1")), ME));
	}

	@Test
	void 토큰을_받은_뒤_회원이_삭제되면_401이다() {
		long id = members.pending();
		String token = TestTokens.hs256(secret, claims(Long.toString(id)));
		assertThat(graphQl.post(token, ME).status()).isEqualTo(200);

		members.delete(id);

		assertUnauthenticated(graphQl.post(token, ME));
	}

	@Test
	void 서명_뒤에_본문의_sub를_다른_회원으로_바꾸면_401이다() {
		long admin = members.admin();
		String token = TestTokens.hs256(secret, claims(Long.toString(memberId)));

		String tampered = TestTokens.withPayload(token, claims(Long.toString(admin)));

		assertUnauthenticated(graphQl.post(tampered, ME));
	}

	@ParameterizedTest
	@ValueSource(strings = {"abc", "a.b.c", "....", "null", "undefined"})
	void 토큰_모양이_아니면_401이다(String garbage) {
		assertUnauthenticated(graphQl.post(garbage, ME));
	}

	@Test
	void Bearer_뒤가_비어_있으면_401이다() {
		assertUnauthenticated(graphQl.send("Bearer ", ME, Map.of()));
		assertUnauthenticated(graphQl.send("Bearer", ME, Map.of()));
	}

	@Test
	void 토큰_없이_부르면_GraphQL_단계에서_UNAUTHENTICATED다() {
		GraphQlResponse response = graphQl.post(null, ME);

		assertThat(response.status()).isEqualTo(200);
		assertThat(response.errorCode()).isEqualTo("UNAUTHENTICATED");
		assertThat(response.errorMessage()).isEqualTo(ErrorCode.UNAUTHENTICATED.message());
		assertThat(response.dataIsNull()).isTrue();
	}

	@Test
	void Bearer가_아닌_스킴은_토큰으로_보지_않는다() {
		String token = TestTokens.hs256(secret, claims(Long.toString(memberId)));

		GraphQlResponse response = graphQl.send("Basic " + token, ME, Map.of());

		assertThat(response.errorCode()).isEqualTo("UNAUTHENTICATED");
		assertThat(response.dataIsNull()).isTrue();
	}

	@Test
	void GraphQL_밖의_경로는_토큰이_없으면_401이다() {
		GraphQlResponse response = graphQl.get("/anything-else", null);

		assertThat(response.status()).isEqualTo(401);
		assertThat(response.errorCode()).isEqualTo("UNAUTHENTICATED");
	}

	@Test
	void 헬스체크는_토큰_없이_열려_있다() {
		GraphQlResponse response = graphQl.get("/actuator/health", null);

		assertThat(response.status()).isNotIn(401, 403, 404);
	}

	@ParameterizedTest
	@ValueSource(strings = {"/actuator/env", "/actuator/beans", "/actuator/heapdump", "/actuator/loggers",
			"/actuator/configprops"})
	void 헬스체크_외_actuator는_노출되지_않는다(String path) {
		String adminToken = TestTokens.hs256(secret, claims(Long.toString(members.admin())));

		assertThat(graphQl.get(path, null).status()).isEqualTo(401);
		assertThat(graphQl.get(path, adminToken).status()).isEqualTo(404);
	}

}
