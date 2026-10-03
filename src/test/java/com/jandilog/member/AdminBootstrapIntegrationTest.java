package com.jandilog.member;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import com.jandilog.common.exception.ErrorCode;
import com.jandilog.common.security.LoginClient;
import com.jandilog.common.security.OAuthLoginSuccessHandler;
import com.jandilog.member.domain.MemberRole;
import com.jandilog.member.domain.MemberStatus;
import com.jandilog.testsupport.auth.AuthIntegrationTest;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;
import com.jandilog.testsupport.auth.MemberFixture.MemberRow;
import com.jandilog.testsupport.auth.OAuthLoginDriver;
import com.jandilog.testsupport.auth.OAuthLoginDriver.LoginResult;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

// 첫 관리자 지정: ADMIN_GITHUB_IDS(GitHub 숫자 id)에 있는 계정은 로그인할 때 활성 관리자가 된다.
// 목록은 두 id(공백이 섞인 값)로 띄우고, 목록에 없는 계정은 기존 동작을 그대로 따른다
@TestPropertySource(properties = "ADMIN_GITHUB_IDS=7100000000001, 7100000000002")
class AdminBootstrapIntegrationTest extends AuthIntegrationTest {

	// 실제 GitHub id와 겹치지 않는 큰 값. MemberFixture의 무작위 구간(8조대)과도 겹치지 않는다
	private static final long LISTED = 7_100_000_000_001L;
	private static final long LISTED_SECOND = 7_100_000_000_002L;
	private static final Instant NOW = Instant.parse("2026-03-01T01:02:03Z");
	private static final LocalDateTime NOW_KST = LocalDateTime.of(2026, 3, 1, 10, 2, 3);
	private static final LocalDateTime EARLIER = LocalDateTime.of(2026, 1, 2, 3, 4, 5);
	private static final LocalDateTime APPROVED_EARLIER = LocalDateTime.of(2026, 1, 3, 4, 5, 6);
	private static final String ADMIN_LIST = "{ adminMembers { totalCount } }";

	@Autowired
	private OAuthLoginSuccessHandler successHandler;

	private OAuthLoginDriver driver;
	private Logger appLogger;
	private ListAppender<ILoggingEvent> logs;

	@BeforeEach
	void setUp() {
		driver = new OAuthLoginDriver(successHandler);
		resetListed();
		clock.fixAt(NOW);
		appLogger = (Logger) LoggerFactory.getLogger("com.jandilog");
		logs = new ListAppender<>();
		logs.start();
		appLogger.addAppender(logs);
	}

	@AfterEach
	void detachLogs() {
		appLogger.detachAppender(logs);
		logs.stop();
	}

	// 목록 id의 이전 흔적(이전 실행·이전 시험)을 지우고 다시 추적한다
	private void resetListed() {
		members.trackGithubId(LISTED);
		members.trackGithubId(LISTED_SECOND);
		members.cleanup();
		members.trackGithubId(LISTED);
		members.trackGithubId(LISTED_SECOND);
	}

	private long insertListed(MemberStatus status, MemberRole role, LocalDateTime approvedAt) {
		return members.insertWithGithubId(LISTED, status, role, "before-login", "이전 이름", EARLIER, approvedAt);
	}

	private LoginResult loginListed() {
		return driver.login(LISTED, "octocat", "홍길동");
	}

	private MemberRow listedRow() {
		return members.findByGithubId(LISTED).orElseThrow();
	}

	// 승격·관리자 생성 로그만 센다
	private List<String> adminLogs() {
		return logs.list.stream().map(ILoggingEvent::getFormattedMessage)
				.filter(message -> message.contains("관리자 목록")).toList();
	}

	private void assertActiveAdmin(MemberRow row, LocalDateTime approvedAt) {
		assertThat(row.status()).isEqualTo(MemberStatus.ACTIVE);
		assertThat(row.role()).isEqualTo(MemberRole.ADMIN);
		assertThat(row.approvedAt()).isEqualTo(approvedAt);
	}

	// ----- 목록에 있는 계정 -----

	@Test
	void 목록의_새_회원은_첫_로그인부터_활성_관리자이고_코드를_받는다() {
		LoginResult result = loginListed();

		MemberRow row = listedRow();
		assertActiveAdmin(row, NOW_KST);
		assertThat(row.createdAt()).isEqualTo(NOW_KST);
		assertThat(row.githubLogin()).isEqualTo("octocat");
		assertThat(row.nickname()).isEqualTo("홍길동");
		assertThat(result.error()).isNull();
		assertThat(result.location()).startsWith(authProperties.webBaseUrl() + "/auth/callback?code=");
		assertThat(redis.opsForValue().get("authcode:" + result.code())).isEqualTo(Long.toString(row.id()));
	}

	@Test
	void 목록의_두_번째_id도_관리자가_된다() {
		long githubId = LISTED_SECOND;

		LoginResult result = driver.login(githubId, "second", "둘째");

		assertActiveAdmin(members.findByGithubId(githubId).orElseThrow(), NOW_KST);
		assertThat(result.code()).isNotNull();
	}

	@ParameterizedTest
	@CsvSource({"PENDING, MEMBER", "REJECTED, MEMBER", "PENDING, ADMIN", "REJECTED, ADMIN"})
	void 목록의_기존_회원은_승인_대기_거절_상태에서_활성_관리자로_승격된다(MemberStatus status, MemberRole role) {
		long id = insertListed(status, role, null);

		LoginResult result = loginListed();

		MemberRow row = members.find(id).orElseThrow();
		assertActiveAdmin(row, NOW_KST);
		assertThat(row.createdAt()).isEqualTo(EARLIER);
		assertThat(members.countByGithubId(LISTED)).isEqualTo(1);
		// 거절 계정도 승격되어 코드를 받는다
		assertThat(result.error()).isNull();
		assertThat(redis.opsForValue().get("authcode:" + result.code())).isEqualTo(Long.toString(id));
	}

	@Test
	void 이미_승인된_일반_회원이_승격돼도_원래_승인_시각은_지킨다() {
		long id = insertListed(MemberStatus.ACTIVE, MemberRole.MEMBER, APPROVED_EARLIER);

		LoginResult result = loginListed();

		assertActiveAdmin(members.find(id).orElseThrow(), APPROVED_EARLIER);
		assertThat(result.error()).isNull();
	}

	@Test
	void 승인_시각이_비어_있던_활성_회원이_승격되면_지금_시각을_넣는다() {
		long id = insertListed(MemberStatus.ACTIVE, MemberRole.MEMBER, null);

		loginListed();

		assertActiveAdmin(members.find(id).orElseThrow(), NOW_KST);
	}

	@Test
	void 이미_활성_관리자면_아무것도_바꾸지_않는다() {
		long id = insertListed(MemberStatus.ACTIVE, MemberRole.ADMIN, APPROVED_EARLIER);
		clock.fixAt(NOW.plusSeconds(3600));

		LoginResult result = loginListed();

		MemberRow row = members.find(id).orElseThrow();
		assertActiveAdmin(row, APPROVED_EARLIER);
		assertThat(row.createdAt()).isEqualTo(EARLIER);
		// GitHub 최신값 동기화만 일어난다
		assertThat(row.githubLogin()).isEqualTo("octocat");
		assertThat(result.error()).isNull();
		assertThat(adminLogs()).isEmpty();
	}

	@Test
	void 앱에서_로그인해도_승격되고_딥링크로_코드를_받는다() {
		long id = insertListed(MemberStatus.REJECTED, MemberRole.MEMBER, null);

		LoginResult result = driver.login(LISTED, "octocat", "홍길동", LoginClient.APP);

		assertActiveAdmin(members.find(id).orElseThrow(), NOW_KST);
		assertThat(result.location()).startsWith(authProperties.appDeepLink() + "?code=");
	}

	// ----- 목록에 없는 계정은 기존 동작 그대로 -----

	@Test
	void 목록에_없는_새_회원은_승인_대기로_만들어진다() {
		long githubId = members.nextGithubId();

		LoginResult result = driver.login(githubId, "outsider", "외부인");

		MemberRow row = members.findByGithubId(githubId).orElseThrow();
		assertThat(row.status()).isEqualTo(MemberStatus.PENDING);
		assertThat(row.role()).isEqualTo(MemberRole.MEMBER);
		assertThat(row.approvedAt()).isNull();
		assertThat(result.code()).isNotNull();
		assertThat(adminLogs()).isEmpty();
	}

	@ParameterizedTest
	@CsvSource({"PENDING, MEMBER", "ACTIVE, MEMBER", "ACTIVE, ADMIN"})
	void 목록에_없는_기존_회원은_상태와_역할이_그대로다(MemberStatus status, MemberRole role) {
		LocalDateTime approvedAt = status == MemberStatus.ACTIVE ? APPROVED_EARLIER : null;
		long id = members.insert(status, role, members.uniqueLogin(), "원래 이름", EARLIER, approvedAt);
		long githubId = members.find(id).orElseThrow().githubId();

		driver.login(githubId, "renamed", "새 이름");

		MemberRow row = members.find(id).orElseThrow();
		assertThat(row.status()).isEqualTo(status);
		assertThat(row.role()).isEqualTo(role);
		assertThat(row.approvedAt()).isEqualTo(approvedAt);
		assertThat(row.githubLogin()).isEqualTo("renamed");
		assertThat(adminLogs()).isEmpty();
	}

	@Test
	void 목록에_없는_거절_계정은_승격되지_않고_코드도_받지_못한다() {
		long id = members.rejected();
		long githubId = members.find(id).orElseThrow().githubId();

		LoginResult result = driver.login(githubId, "rejected-login", "거절 회원");

		assertThat(result.code()).isNull();
		assertThat(result.error()).isEqualTo("rejected");
		MemberRow row = members.find(id).orElseThrow();
		assertThat(row.status()).isEqualTo(MemberStatus.REJECTED);
		assertThat(row.role()).isEqualTo(MemberRole.MEMBER);
	}

	@Test
	void 목록_id와_가까운_숫자는_승격되지_않는다() {
		for (long githubId : new long[] {LISTED - 1, LISTED_SECOND + 1, LISTED * 10, LISTED / 1000}) {
			members.trackGithubId(githubId);
			driver.login(githubId, "near-miss", "비슷한 id");

			MemberRow row = members.findByGithubId(githubId).orElseThrow();
			assertThat(row.status()).as("id=%d", githubId).isEqualTo(MemberStatus.PENDING);
			assertThat(row.role()).isEqualTo(MemberRole.MEMBER);
		}
	}

	@Test
	void GitHub_아이디_문자열이_같아도_숫자_id가_다르면_승격되지_않는다() {
		// 목록 id 계정이 쓰던 login을 다른 사람이 다시 쓰는 경우
		driver.login(LISTED, "reused-login", "원래 주인");
		long outsider = members.nextGithubId();

		driver.login(outsider, "reused-login", "새 사용자");

		assertActiveAdmin(listedRow(), NOW_KST);
		MemberRow row = members.findByGithubId(outsider).orElseThrow();
		assertThat(row.status()).isEqualTo(MemberStatus.PENDING);
		assertThat(row.role()).isEqualTo(MemberRole.MEMBER);
	}

	@Test
	void 목록에_없는_관리자도_강등되지_않는다() {
		long id = members.admin();
		long githubId = members.find(id).orElseThrow().githubId();

		driver.login(githubId, "old-admin", "기존 관리자");

		MemberRow row = members.find(id).orElseThrow();
		assertThat(row.role()).isEqualTo(MemberRole.ADMIN);
		assertThat(row.status()).isEqualTo(MemberStatus.ACTIVE);
	}

	// ----- 한 번만 반영 -----

	@ParameterizedTest
	@EnumSource(value = MemberStatus.class, names = {"PENDING", "REJECTED"})
	void 같은_계정이_동시에_로그인해도_승격은_한_번만_반영되고_모두_코드를_받는다(MemberStatus status) throws Exception {
		int threads = 4;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			for (int round = 0; round < 5; round++) {
				resetListed();
				long id = insertListed(status, MemberRole.MEMBER, null);
				logs.list.clear();

				List<LoginResult> results = runConcurrently(pool, threads);

				for (LoginResult result : results) {
					// 한 로그인이 먼저 승격해도 나머지가 옛 상태로 읽어 rejected를 받으면 안 된다
					assertThat(result.error()).as("round %d: %s", round, result.location()).isNull();
					assertThat(result.code()).isNotNull();
				}
				assertThat(members.countByGithubId(LISTED)).isEqualTo(1);
				assertActiveAdmin(members.find(id).orElseThrow(), NOW_KST);
				assertThat(adminLogs()).as("round %d", round).hasSize(1);
			}
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void 목록의_새_회원이_동시에_첫_로그인해도_관리자_회원은_한_명이고_한_번만_만들어진다() throws Exception {
		int threads = 4;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			for (int round = 0; round < 5; round++) {
				resetListed();
				logs.list.clear();

				List<LoginResult> results = runConcurrently(pool, threads);

				for (LoginResult result : results) {
					assertThat(result.error()).as("round %d", round).isNull();
					assertThat(result.code()).isNotNull();
				}
				assertThat(members.countByGithubId(LISTED)).isEqualTo(1);
				assertActiveAdmin(listedRow(), NOW_KST);
				assertThat(adminLogs()).as("round %d", round).hasSize(1);
			}
		} finally {
			pool.shutdownNow();
		}
	}

	private List<LoginResult> runConcurrently(ExecutorService pool, int threads) throws Exception {
		CyclicBarrier barrier = new CyclicBarrier(threads);
		List<Future<LoginResult>> futures = new ArrayList<>();
		for (int i = 0; i < threads; i++) {
			futures.add(pool.submit(() -> {
				barrier.await(5, TimeUnit.SECONDS);
				return loginListed();
			}));
		}
		List<LoginResult> results = new ArrayList<>();
		for (Future<LoginResult> future : futures) {
			results.add(future.get(30, TimeUnit.SECONDS));
		}
		return results;
	}

	@Test
	void 승격이_끝난_뒤_다시_로그인해도_승인_시각이_바뀌지_않고_로그도_더_남지_않는다() {
		loginListed();
		clock.fixAt(NOW.plusSeconds(7200));
		logs.list.clear();

		loginListed();
		loginListed();

		assertActiveAdmin(listedRow(), NOW_KST);
		assertThat(adminLogs()).isEmpty();
	}

	// ----- 상태·역할은 DB 기준 -----

	@ParameterizedTest
	@CsvSource({"PENDING, ACCOUNT_PENDING", "REJECTED, ACCOUNT_REJECTED"})
	void 승격되면_같은_토큰으로_바로_관리자_기능을_부를_수_있다(MemberStatus status, ErrorCode before) {
		long id = insertListed(status, MemberRole.MEMBER, null);
		String token = bearerFor(id);
		GraphQlResponse denied = graphQl.post(token, ADMIN_LIST);
		assertThat(denied.errorCode()).isEqualTo(before.name());

		LoginResult login = loginListed();
		assertThat(login.error()).isNull();

		GraphQlResponse allowed = graphQl.post(token, ADMIN_LIST);
		assertThat(allowed.hasErrors()).as(allowed.rawBody()).isFalse();
		assertThat(allowed.data().path("adminMembers").path("totalCount").isNumber()).isTrue();
		GraphQlResponse me = graphQl.post(token, "{ me { status role } }");
		assertThat(me.data().path("me").path("status").asText()).isEqualTo("ACTIVE");
		assertThat(me.data().path("me").path("role").asText()).isEqualTo("ADMIN");
	}

	@Test
	void 승격된_관리자는_가입_승인_변경도_할_수_있다() {
		long id = insertListed(MemberStatus.PENDING, MemberRole.MEMBER, null);
		String token = bearerFor(id);
		loginListed();
		long applicant = members.pending();

		GraphQlResponse approve = graphQl.post(token, "mutation($id: ID!) { approveMember(memberId: $id) { id status } }",
				Map.of("id", Long.toString(applicant)));

		assertThat(approve.hasErrors()).as(approve.rawBody()).isFalse();
		assertThat(members.find(applicant).orElseThrow().status()).isEqualTo(MemberStatus.ACTIVE);
	}

	@Test
	void 목록에_없는_승인_대기_회원은_로그인해도_관리자_기능을_부를_수_없다() {
		long id = members.pending();
		long githubId = members.find(id).orElseThrow().githubId();
		String token = bearerFor(id);

		driver.login(githubId, "outsider", "외부인");

		assertThat(graphQl.post(token, ADMIN_LIST).errorCode()).isEqualTo(ErrorCode.ACCOUNT_PENDING.name());
	}

	// ----- 기록 -----

	@Test
	void admin_action_log에는_남기지_않는다() {
		long id = insertListed(MemberStatus.PENDING, MemberRole.MEMBER, null);
		int before = totalActionLogs();

		loginListed();

		assertThat(members.countActionLogs(id)).isZero();
		assertThat(totalActionLogs()).isEqualTo(before);
	}

	private int totalActionLogs() {
		Integer count = jdbc.queryForObject("select count(*) from admin_action_log", Integer.class);
		return count == null ? 0 : count;
	}

	@Test
	void 승격_로그에는_회원_번호만_남고_코드와_토큰은_없다() {
		long id = insertListed(MemberStatus.REJECTED, MemberRole.MEMBER, null);

		LoginResult result = loginListed();

		assertThat(adminLogs()).hasSize(1);
		String message = adminLogs().get(0);
		assertThat(message).contains("memberId=" + id).contains("승격");
		for (ILoggingEvent event : logs.list) {
			assertThat(event.getFormattedMessage()).doesNotContain(result.code()).doesNotContainIgnoringCase("Bearer")
					.doesNotContainIgnoringCase("accessToken");
			assertThat(event.getThrowableProxy()).isNull();
		}
	}

	@Test
	void 새_관리자_생성_로그에도_코드가_없다() {
		LoginResult result = loginListed();

		assertThat(adminLogs()).hasSize(1);
		assertThat(adminLogs().get(0)).contains("memberId=" + listedRow().id()).doesNotContain(result.code());
	}

}
