package com.jandilog.member;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import com.jandilog.common.exception.ErrorCode;
import com.jandilog.member.domain.MemberRole;
import com.jandilog.member.domain.MemberStatus;
import com.jandilog.member.service.AuthCodeService;
import com.jandilog.testsupport.auth.AuthIntegrationTest;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;

// 일회용 코드 → JWT 교환 (기능명세서 1장 토큰 교환, E-02·E-05·E-06). 실제 HTTP /graphql로 호출한다
class AuthCodeExchangeIntegrationTest extends AuthIntegrationTest {

	private static final String EXCHANGE = """
			mutation($code: String!) {
			  exchangeAuthCode(code: $code) {
			    accessToken
			    expiresIn
			    me { id githubLogin nickname profileImageUrl status role }
			  }
			}
			""";

	@Autowired
	private AuthCodeService authCodeService;
	@Autowired
	private JwtDecoder jwtDecoder;

	private GraphQlResponse exchange(String code) {
		return graphQl.post(null, EXCHANGE, Map.of("code", code));
	}

	private void assertCodeRejected(GraphQlResponse response) {
		assertThat(response.status()).isEqualTo(200);
		assertThat(response.errorCode()).isEqualTo("AUTH_CODE_INVALID");
		assertThat(response.errorMessage()).isEqualTo("로그인 정보가 만료됐어요. 처음부터 다시 해주세요.");
		assertThat(response.data().isNull() || response.data().isMissingNode()).isTrue();
		assertThat(response.rawBody()).doesNotContain("accessToken\":\"");
	}

	@Test
	void 승인_대기_회원도_코드를_토큰으로_교환할_수_있다() {
		long id = members.pending();
		String code = authCodeService.issue(id);

		GraphQlResponse response = exchange(code);

		assertThat(response.hasErrors()).isFalse();
		var payload = response.data().path("exchangeAuthCode");
		assertThat(payload.path("accessToken").asText()).isNotBlank();
		assertThat(payload.path("expiresIn").asLong()).isEqualTo(authProperties.jwt().expiresMinutes() * 60);
		var me = payload.path("me");
		assertThat(me.path("id").asText()).isEqualTo(Long.toString(id));
		assertThat(me.path("status").asText()).isEqualTo("PENDING");
		assertThat(me.path("role").asText()).isEqualTo("MEMBER");
		assertThat(me.path("profileImageUrl").isNull()).isTrue();
	}

	@Test
	void 활성_회원과_관리자의_상태와_역할이_me에_담긴다() {
		long active = members.active();
		long admin = members.admin();

		var activeMe = exchange(authCodeService.issue(active)).data().path("exchangeAuthCode").path("me");
		var adminMe = exchange(authCodeService.issue(admin)).data().path("exchangeAuthCode").path("me");

		assertThat(activeMe.path("status").asText()).isEqualTo(MemberStatus.ACTIVE.name());
		assertThat(activeMe.path("role").asText()).isEqualTo(MemberRole.MEMBER.name());
		assertThat(adminMe.path("status").asText()).isEqualTo(MemberStatus.ACTIVE.name());
		assertThat(adminMe.path("role").asText()).isEqualTo(MemberRole.ADMIN.name());
	}

	@Test
	void 발급된_토큰은_회원_id를_sub로_갖고_상태와_역할은_담지_않는다() {
		long id = members.admin();
		String token = exchange(authCodeService.issue(id)).data().path("exchangeAuthCode").path("accessToken").asText();

		Jwt jwt = jwtDecoder.decode(token);

		assertThat(jwt.getSubject()).isEqualTo(Long.toString(id));
		assertThat(jwt.getClaimAsString("iss")).isEqualTo(authProperties.jwt().issuer());
		assertThat(jwt.getClaims().keySet()).containsExactlyInAnyOrder("iss", "sub", "iat", "exp");
		assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()))
				.isEqualTo(Duration.ofMinutes(authProperties.jwt().expiresMinutes()));
	}

	@Test
	void 교환한_토큰으로_me를_조회할_수_있다() {
		long id = members.pending();
		String token = exchange(authCodeService.issue(id)).data().path("exchangeAuthCode").path("accessToken").asText();

		GraphQlResponse me = graphQl.post(token, "{ me { id githubLogin status role } }");

		assertThat(me.hasErrors()).isFalse();
		assertThat(me.data().path("me").path("id").asText()).isEqualTo(Long.toString(id));
		assertThat(me.data().path("me").path("status").asText()).isEqualTo("PENDING");
	}

	@Test
	void 코드는_교환하는_즉시_Redis에서_지워진다() {
		long id = members.pending();
		String code = authCodeService.issue(id);
		assertThat(redis.hasKey("authcode:" + code)).isTrue();

		exchange(code);

		assertThat(redis.hasKey("authcode:" + code)).isFalse();
	}

	@Test
	void 같은_코드를_두_번째로_쓰면_거부한다_E05() {
		long id = members.pending();
		String code = authCodeService.issue(id);

		GraphQlResponse first = exchange(code);
		GraphQlResponse second = exchange(code);

		assertThat(first.hasErrors()).isFalse();
		assertCodeRejected(second);
		assertCodeRejected(exchange(code));
	}

	@Test
	void 만료된_코드는_거부한다_E06() throws Exception {
		long id = members.pending();
		String code = authCodeService.issue(id);
		// TTL 60초를 기다리는 대신 남은 시간을 줄여서 만료를 만든다
		redis.expire("authcode:" + code, Duration.ofMillis(100));
		long deadline = System.currentTimeMillis() + 5_000;
		while (Boolean.TRUE.equals(redis.hasKey("authcode:" + code)) && System.currentTimeMillis() < deadline) {
			Thread.sleep(50);
		}
		assertThat(redis.hasKey("authcode:" + code)).isFalse();

		assertCodeRejected(exchange(code));
	}

	@Test
	void 형식은_맞지만_발급한_적_없는_코드는_거부한다() {
		assertCodeRejected(exchange("A".repeat(43)));
		assertCodeRejected(exchange("abcdefghijklmnopqrstuvwxyz0123456789_-ABCDE"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"", " ", "short", "*", "authcode:*", "../../etc", "가나다",
			"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
	void 형식이_틀린_코드는_거부한다(String code) {
		assertCodeRejected(exchange(code));
	}

	@Test
	void 다른_회원이_발급받은_코드로는_그_회원의_토큰만_나온다() {
		long first = members.pending();
		long second = members.active();
		String firstCode = authCodeService.issue(first);
		String secondCode = authCodeService.issue(second);

		var secondMe = exchange(secondCode).data().path("exchangeAuthCode").path("me");
		var firstMe = exchange(firstCode).data().path("exchangeAuthCode").path("me");

		assertThat(firstMe.path("id").asText()).isEqualTo(Long.toString(first));
		assertThat(secondMe.path("id").asText()).isEqualTo(Long.toString(second));
	}

	@Test
	void 코드를_받은_뒤_거절된_회원은_토큰을_받지_못하고_코드는_소진된다_E02() {
		long id = members.pending();
		String code = authCodeService.issue(id);
		members.setStatus(id, MemberStatus.REJECTED);

		GraphQlResponse response = exchange(code);

		assertThat(response.errorCode()).isEqualTo("ACCOUNT_REJECTED");
		assertThat(response.errorMessage()).isEqualTo(ErrorCode.ACCOUNT_REJECTED.message());
		assertThat(response.rawBody()).doesNotContain("accessToken\":\"");
		assertCodeRejected(exchange(code));
	}

	@Test
	void 코드를_받은_뒤_회원이_사라지면_거부한다() {
		long id = members.pending();
		String code = authCodeService.issue(id);
		members.delete(id);

		assertCodeRejected(exchange(code));
	}

	@Test
	void 같은_코드를_동시에_교환해도_성공은_한_번뿐이다() throws Exception {
		int threads = 8;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			for (int round = 0; round < 3; round++) {
				String code = authCodeService.issue(members.pending());
				CyclicBarrier barrier = new CyclicBarrier(threads);
				List<Future<GraphQlResponse>> futures = new ArrayList<>();
				for (int i = 0; i < threads; i++) {
					futures.add(pool.submit(() -> {
						barrier.await(5, TimeUnit.SECONDS);
						return exchange(code);
					}));
				}
				int success = 0;
				int rejected = 0;
				for (Future<GraphQlResponse> future : futures) {
					GraphQlResponse response = future.get(30, TimeUnit.SECONDS);
					if (!response.hasErrors()) {
						success++;
					} else if ("AUTH_CODE_INVALID".equals(response.errorCode())) {
						rejected++;
					}
				}
				assertThat(success).isEqualTo(1);
				assertThat(rejected).isEqualTo(threads - 1);
			}
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void 코드_변수를_빼면_요청이_거부되고_토큰은_나오지_않는다() {
		GraphQlResponse response = graphQl.post(null, EXCHANGE, Map.of());

		assertThat(response.hasErrors()).isTrue();
		assertThat(response.rawBody()).doesNotContain("accessToken\":\"");
	}

}
