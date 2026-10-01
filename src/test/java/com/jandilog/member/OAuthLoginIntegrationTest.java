package com.jandilog.member;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

import com.jandilog.common.security.ClientAwareAuthorizationRequestRepository;
import com.jandilog.common.security.LoginClient;
import com.jandilog.common.security.OAuthLoginSuccessHandler;
import com.jandilog.member.domain.Member;
import com.jandilog.member.domain.MemberRole;
import com.jandilog.member.domain.MemberStatus;
import com.jandilog.member.repository.MemberRepository;
import com.jandilog.member.service.MemberApprovalService;
import com.jandilog.testsupport.auth.AuthIntegrationTest;
import com.jandilog.testsupport.auth.MemberFixture.MemberRow;

// GitHub 인증 성공 이후 처리: 회원 upsert, 일회용 코드 발급, 복귀 주소 (기능명세서 1장, E-02)
// 실제 GitHub는 부르지 않고, 성공 핸들러에 GitHub 사용자 정보를 직접 넣는다
class OAuthLoginIntegrationTest extends AuthIntegrationTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	@Autowired
	private OAuthLoginSuccessHandler successHandler;
	@Autowired
	private MemberRepository memberRepository;
	@Autowired
	private MemberApprovalService approvalService;
	@Autowired
	private PlatformTransactionManager transactionManager;

	private record LoginResult(String location, String code, String error, MockHttpSession session) {
	}

	private LoginResult login(long githubId, String login, String name, LoginClient client) {
		Map<String, Object> attributes = new HashMap<>();
		attributes.put("id", githubId);
		attributes.put("login", login);
		if (name != null) {
			attributes.put("name", name);
		}
		return loginWith(attributes, client);
	}

	private LoginResult loginWith(Map<String, Object> attributes, LoginClient client) {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setAttribute(ClientAwareAuthorizationRequestRepository.CLIENT_ATTRIBUTE, client.name());
		MockHttpSession session = (MockHttpSession) request.getSession(true);
		MockHttpServletResponse response = new MockHttpServletResponse();
		OAuth2User user = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_USER")), attributes, "id");
		try {
			successHandler.onAuthenticationSuccess(request, response,
					new OAuth2AuthenticationToken(user, user.getAuthorities(), "github"));
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		String location = response.getHeader("Location");
		MultiValueMap<String, String> params = UriComponentsBuilder.fromUriString(location).build().getQueryParams();
		return new LoginResult(location, params.getFirst("code"), params.getFirst("error"), session);
	}

	private LoginResult login(long githubId, String login, String name) {
		return login(githubId, login, name, LoginClient.WEB);
	}

	@Test
	void 첫_로그인은_승인_대기_회원을_만들고_일회용_코드를_발급한다() {
		Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		clock.fixAt(now);
		long githubId = members.nextGithubId();

		LoginResult result = login(githubId, "octocat", "홍길동");

		MemberRow row = members.findByGithubId(githubId).orElseThrow();
		assertThat(row.status()).isEqualTo(MemberStatus.PENDING);
		assertThat(row.role()).isEqualTo(MemberRole.MEMBER);
		assertThat(row.githubLogin()).isEqualTo("octocat");
		assertThat(row.nickname()).isEqualTo("홍길동");
		assertThat(row.profileImageUrl()).isNull();
		assertThat(row.createdAt()).isEqualTo(LocalDateTime.ofInstant(now, KST));
		assertThat(row.approvedAt()).isNull();

		assertThat(result.error()).isNull();
		assertThat(result.location()).startsWith(authProperties.webBaseUrl() + "/auth/callback?code=");
		assertThat(result.code()).matches("^[A-Za-z0-9_-]{43}$");
		assertThat(redis.opsForValue().get("authcode:" + result.code())).isEqualTo(Long.toString(row.id()));
	}

	@Test
	void 일회용_코드의_Redis_TTL은_60초다() {
		long githubId = members.nextGithubId();

		LoginResult result = login(githubId, "octocat", "홍길동");

		Long ttl = redis.getExpire("authcode:" + result.code(), TimeUnit.SECONDS);
		assertThat(ttl).isBetween(55L, 60L);
	}

	@Test
	void 앱에서_시작한_로그인은_딥링크로_코드를_보낸다() {
		long githubId = members.nextGithubId();

		LoginResult result = login(githubId, "octocat", "홍길동", LoginClient.APP);

		assertThat(result.location()).startsWith(authProperties.appDeepLink() + "?code=");
		assertThat(result.code()).isNotNull();
	}

	@Test
	void 인증을_잇던_세션은_로그인_처리_뒤_버려진다() {
		LoginResult result = login(members.nextGithubId(), "octocat", "홍길동");

		assertThat(result.session().isInvalid()).isTrue();
	}

	@Test
	void GitHub_숫자_id가_Integer로_들어와도_처리한다() {
		// 실제 GitHub id와 겹치지 않는 큰 Integer 값. GitHub 응답이 int 범위면 JSON 파서가 Integer로 준다
		int githubId = 1_500_000_000 + ThreadLocalRandom.current().nextInt(500_000_000);
		members.trackGithubId(githubId);
		Map<String, Object> attributes = new HashMap<>();
		attributes.put("id", githubId);
		attributes.put("login", "octocat");
		attributes.put("name", "홍길동");

		LoginResult result = loginWith(attributes, LoginClient.WEB);

		assertThat(result.code()).isNotNull();
		assertThat(members.findByGithubId(githubId)).isPresent();
	}

	@Test
	void 표시_이름이_없으면_닉네임은_GitHub_아이디다() {
		long githubId = members.nextGithubId();

		login(githubId, "octocat", null);

		assertThat(members.findByGithubId(githubId).orElseThrow().nickname()).isEqualTo("octocat");
	}

	@Test
	void 표시_이름이_공백뿐이면_닉네임은_GitHub_아이디다() {
		long githubId = members.nextGithubId();

		login(githubId, "octocat", "   ");

		assertThat(members.findByGithubId(githubId).orElseThrow().nickname()).isEqualTo("octocat");
	}

	@Test
	void 닉네임이_30자를_넘으면_30자로_잘려_저장된다() {
		long githubId = members.nextGithubId();
		String longName = "가나다라마바사아자차카타파하".repeat(3);

		login(githubId, "octocat", longName);

		String nickname = members.findByGithubId(githubId).orElseThrow().nickname();
		assertThat(nickname).hasSize(30).isEqualTo(longName.substring(0, 30));
	}

	@Test
	void GitHub_아이디는_39자까지_그대로_저장된다() {
		long githubId = members.nextGithubId();
		String login = "a".repeat(39);

		login(githubId, login, null);

		MemberRow row = members.findByGithubId(githubId).orElseThrow();
		assertThat(row.githubLogin()).isEqualTo(login);
		// 표시 이름 대신 쓴 닉네임은 30자로 잘린다
		assertThat(row.nickname()).isEqualTo("a".repeat(30));
	}

	@ParameterizedTest
	@EnumSource(value = MemberStatus.class, names = {"PENDING", "ACTIVE"})
	void 재로그인하면_github_login과_nickname을_갱신하고_상태는_유지한다(MemberStatus status) {
		LocalDateTime createdAt = LocalDateTime.of(2026, 1, 2, 3, 4, 5);
		LocalDateTime approvedAt = status == MemberStatus.ACTIVE ? LocalDateTime.of(2026, 1, 3, 4, 5, 6) : null;
		String oldLogin = members.uniqueLogin();
		long id = members.insert(status, MemberRole.MEMBER, oldLogin, "옛 이름", createdAt, approvedAt);
		long githubId = members.find(id).orElseThrow().githubId();

		LoginResult result = login(githubId, "renamed-login", "새 이름");

		MemberRow row = members.find(id).orElseThrow();
		assertThat(members.countByGithubId(githubId)).isEqualTo(1);
		assertThat(row.githubLogin()).isEqualTo("renamed-login");
		assertThat(row.nickname()).isEqualTo("새 이름");
		assertThat(row.status()).isEqualTo(status);
		assertThat(row.role()).isEqualTo(MemberRole.MEMBER);
		assertThat(row.createdAt()).isEqualTo(createdAt);
		assertThat(row.approvedAt()).isEqualTo(approvedAt);
		assertThat(redis.opsForValue().get("authcode:" + result.code())).isEqualTo(Long.toString(id));
	}

	@Test
	void 재로그인해도_관리자_역할은_유지된다() {
		long id = members.admin();
		long githubId = members.find(id).orElseThrow().githubId();

		login(githubId, "admin-renamed", "관리자");

		MemberRow row = members.find(id).orElseThrow();
		assertThat(row.role()).isEqualTo(MemberRole.ADMIN);
		assertThat(row.status()).isEqualTo(MemberStatus.ACTIVE);
	}

	@Test
	void 거절된_계정은_코드를_발급받지_못하고_rejected로_돌아간다() {
		long id = members.rejected();
		long githubId = members.find(id).orElseThrow().githubId();

		LoginResult result = login(githubId, "rejected-login", "거절 회원");

		assertThat(result.code()).isNull();
		assertThat(result.error()).isEqualTo("rejected");
		assertThat(result.location()).isEqualTo(authProperties.webBaseUrl() + "/auth/callback?error=rejected");
		assertThat(members.find(id).orElseThrow().status()).isEqualTo(MemberStatus.REJECTED);
		// 이 회원 앞으로 발급된 코드가 하나도 없어야 한다
		var keys = redis.keys("authcode:*");
		assertThat(keys).isNotNull();
		for (String key : keys) {
			assertThat(redis.opsForValue().get(key)).isNotEqualTo(Long.toString(id));
		}
	}

	@Test
	void 거절된_계정이_앱에서_로그인하면_딥링크로_rejected를_받는다() {
		long id = members.rejected();
		long githubId = members.find(id).orElseThrow().githubId();

		LoginResult result = login(githubId, "rejected-login", "거절 회원", LoginClient.APP);

		assertThat(result.location()).isEqualTo(authProperties.appDeepLink() + "?error=rejected");
	}

	@Test
	void 사용자_정보에_id나_login이_없으면_failed로_돌아가고_코드도_회원도_만들지_않는다() {
		Map<String, Object> withoutLogin = new HashMap<>();
		long githubId = members.nextGithubId();
		withoutLogin.put("id", githubId);
		Map<String, Object> idAsString = new HashMap<>();
		idAsString.put("id", "not-a-number");
		idAsString.put("login", "octocat");

		LoginResult noLogin = loginWith(withoutLogin, LoginClient.WEB);
		LoginResult badId = loginWith(idAsString, LoginClient.WEB);

		assertThat(noLogin.error()).isEqualTo("failed");
		assertThat(noLogin.code()).isNull();
		assertThat(badId.error()).isEqualTo("failed");
		assertThat(badId.code()).isNull();
		assertThat(members.countByGithubId(githubId)).isZero();
	}

	@Test
	void 첫_로그인이_동시에_여러_번_들어와도_회원은_한_명이고_모두_코드를_받는다() throws Exception {
		int threads = 4;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			for (int round = 0; round < 5; round++) {
				long githubId = members.nextGithubId();
				CyclicBarrier barrier = new CyclicBarrier(threads);
				List<Future<LoginResult>> futures = new ArrayList<>();
				for (int i = 0; i < threads; i++) {
					futures.add(pool.submit(() -> {
						barrier.await(5, TimeUnit.SECONDS);
						return login(githubId, "racer", "경합");
					}));
				}
				for (Future<LoginResult> future : futures) {
					LoginResult result = future.get(30, TimeUnit.SECONDS);
					assertThat(result.error()).isNull();
					assertThat(result.code()).isNotNull();
				}
				assertThat(members.countByGithubId(githubId)).isEqualTo(1);
			}
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void 로그인_동기화는_바뀐_컬럼만_UPDATE해서_다른_곳에서_바뀐_상태를_덮어쓰지_않는다() {
		long id = members.pending();
		TransactionTemplate tx = new TransactionTemplate(transactionManager);

		tx.executeWithoutResult(status -> {
			// 승인 대기로 읽은 엔티티를 쥔 채로, 같은 행이 승인(ACTIVE)된다
			Member member = memberRepository.findById(id).orElseThrow();
			jdbc.update("update member set status = 'ACTIVE' where id = ?", id);
			member.syncGithubProfile("synced-login", "동기화");
		});

		MemberRow row = members.find(id).orElseThrow();
		assertThat(row.githubLogin()).isEqualTo("synced-login");
		assertThat(row.nickname()).isEqualTo("동기화");
		assertThat(row.status()).isEqualTo(MemberStatus.ACTIVE);
	}

	@Test
	void 로그인과_승인이_겹쳐도_승인된_상태가_남는다() throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			for (int round = 0; round < 20; round++) {
				long id = members.pending();
				long githubId = members.find(id).orElseThrow().githubId();
				CyclicBarrier barrier = new CyclicBarrier(2);
				Future<LoginResult> loginFuture = pool.submit(() -> {
					barrier.await(5, TimeUnit.SECONDS);
					return login(githubId, "after-race", "경합 뒤");
				});
				Future<?> approveFuture = pool.submit(() -> {
					barrier.await(5, TimeUnit.SECONDS);
					return approvalService.approve(Long.toString(id));
				});

				approveFuture.get(30, TimeUnit.SECONDS);
				assertThat(loginFuture.get(30, TimeUnit.SECONDS).error()).isNull();
				assertThat(members.find(id).orElseThrow().status()).isEqualTo(MemberStatus.ACTIVE);
			}
		} finally {
			pool.shutdownNow();
		}
	}

}
