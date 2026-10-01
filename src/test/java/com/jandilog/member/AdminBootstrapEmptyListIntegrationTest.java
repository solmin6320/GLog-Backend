package com.jandilog.member;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import com.jandilog.common.config.AdminProperties;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.common.security.OAuthLoginSuccessHandler;
import com.jandilog.member.domain.MemberRole;
import com.jandilog.member.domain.MemberStatus;
import com.jandilog.testsupport.auth.AuthIntegrationTest;
import com.jandilog.testsupport.auth.MemberFixture.MemberRow;
import com.jandilog.testsupport.auth.OAuthLoginDriver;
import com.jandilog.testsupport.auth.OAuthLoginDriver.LoginResult;

// ADMIN_GITHUB_IDS가 비어 있으면(기본값) 아무도 승격되지 않고 로그인은 기존 동작 그대로다.
// 로컬 .env에 값이 있어도 이 시험은 빈 값으로 덮어써서 돌린다
@TestPropertySource(properties = "ADMIN_GITHUB_IDS=")
class AdminBootstrapEmptyListIntegrationTest extends AuthIntegrationTest {

	@Autowired
	private AdminProperties adminProperties;
	@Autowired
	private OAuthLoginSuccessHandler successHandler;

	private OAuthLoginDriver driver;

	@BeforeEach
	void setUp() {
		driver = new OAuthLoginDriver(successHandler);
	}

	@Test
	void 목록이_비어_있다() {
		assertThat(adminProperties.githubIdSet()).isEmpty();
	}

	@Test
	void 새_회원은_누구든_승인_대기로_만들어진다() {
		long githubId = members.nextGithubId();

		LoginResult result = driver.login(githubId, "first-user", "첫 사용자");

		MemberRow row = members.findByGithubId(githubId).orElseThrow();
		assertThat(row.status()).isEqualTo(MemberStatus.PENDING);
		assertThat(row.role()).isEqualTo(MemberRole.MEMBER);
		assertThat(row.approvedAt()).isNull();
		assertThat(result.code()).isNotNull();
	}

	@ParameterizedTest
	@CsvSource({"PENDING, MEMBER", "ACTIVE, MEMBER", "ACTIVE, ADMIN"})
	void 기존_회원의_상태와_역할은_로그인으로_바뀌지_않는다(MemberStatus status, MemberRole role) {
		LocalDateTime approvedAt = status == MemberStatus.ACTIVE ? LocalDateTime.of(2026, 1, 3, 4, 5, 6) : null;
		long id = members.insert(status, role, members.uniqueLogin(), "원래 이름", LocalDateTime.of(2026, 1, 2, 3, 4, 5),
				approvedAt);
		long githubId = members.find(id).orElseThrow().githubId();

		driver.login(githubId, "renamed", "새 이름");

		MemberRow row = members.find(id).orElseThrow();
		assertThat(row.status()).isEqualTo(status);
		assertThat(row.role()).isEqualTo(role);
		assertThat(row.approvedAt()).isEqualTo(approvedAt);
	}

	@Test
	void 거절_계정은_코드를_받지_못하고_거절_상태로_남는다() {
		long id = members.rejected();
		long githubId = members.find(id).orElseThrow().githubId();

		LoginResult result = driver.login(githubId, "rejected-login", "거절 회원");

		assertThat(result.code()).isNull();
		assertThat(result.error()).isEqualTo("rejected");
		assertThat(members.find(id).orElseThrow().status()).isEqualTo(MemberStatus.REJECTED);
	}

	@Test
	void 승인_대기_회원은_로그인해도_관리자_기능을_부를_수_없다() {
		long id = members.pending();
		long githubId = members.find(id).orElseThrow().githubId();
		String token = bearerFor(id);

		driver.login(githubId, "pending-user", "대기자");

		assertThat(graphQl.post(token, "{ adminMembers { totalCount } }").errorCode())
				.isEqualTo(ErrorCode.ACCOUNT_PENDING.name());
	}

}
