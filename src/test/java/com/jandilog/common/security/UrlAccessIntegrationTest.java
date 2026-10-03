package com.jandilog.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.jandilog.common.exception.ErrorCode;
import com.jandilog.testsupport.auth.AuthIntegrationTest;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;

// URL 체인의 기본 거부: 허용 목록 밖의 경로는 @PreAuthorize가 없어도 ACTIVE 회원(ROLE_MEMBER)만 통과한다.
// 테스트 전용 REST 엔드포인트(/probe-rest, 권한 애너테이션 없음)로 확인한다
class UrlAccessIntegrationTest extends AuthIntegrationTest {

	private long pendingId;
	private long rejectedId;
	private long memberId;
	private long adminId;

	@BeforeEach
	void setUp() {
		pendingId = members.pending();
		rejectedId = members.rejected();
		memberId = members.active();
		adminId = members.admin();
	}

	private void assertDenied(GraphQlResponse response, ErrorCode expected) {
		assertThat(response.status()).isEqualTo(expected.httpStatus());
		assertThat(response.errorCode()).isEqualTo(expected.name());
		assertThat(response.errorMessage()).isEqualTo(expected.message());
	}

	@Test
	void 비로그인은_권한_애너테이션이_없는_REST도_401이다() {
		GraphQlResponse response = graphQl.get("/probe-rest", null);

		assertDenied(response, ErrorCode.UNAUTHENTICATED);
		assertThat(response.header("WWW-Authenticate")).isEqualTo("Bearer");
	}

	@Test
	void 승인_대기_계정은_권한_애너테이션이_없는_REST를_E01로_막는다() {
		assertDenied(graphQl.get("/probe-rest", bearerFor(pendingId)), ErrorCode.ACCOUNT_PENDING);
	}

	@Test
	void 거절_계정은_권한_애너테이션이_없는_REST를_E02로_막는다() {
		assertDenied(graphQl.get("/probe-rest", bearerFor(rejectedId)), ErrorCode.ACCOUNT_REJECTED);
	}

	@Test
	void ACTIVE_회원과_관리자는_권한_애너테이션이_없는_REST를_쓸_수_있다() {
		GraphQlResponse member = graphQl.get("/probe-rest", bearerFor(memberId));
		GraphQlResponse admin = graphQl.get("/probe-rest", bearerFor(adminId));

		assertThat(member.status()).isEqualTo(200);
		assertThat(member.rawBody()).isEqualTo("open");
		assertThat(admin.status()).isEqualTo(200);
	}

	@Test
	void 없는_경로도_승인_대기_계정에는_404가_아니라_권한_오류가_먼저다() {
		assertDenied(graphQl.get("/not-exists", bearerFor(pendingId)), ErrorCode.ACCOUNT_PENDING);
		assertDenied(graphQl.get("/not-exists", bearerFor(rejectedId)), ErrorCode.ACCOUNT_REJECTED);
		assertThat(graphQl.get("/not-exists", bearerFor(memberId)).status()).isEqualTo(404);
	}

	@ParameterizedTest
	@ValueSource(strings = {"/actuator/env", "/actuator/beans", "/actuator/loggers"})
	void 노출되지_않은_actuator는_승인_대기_계정에도_권한_오류다(String path) {
		assertDenied(graphQl.get(path, bearerFor(pendingId)), ErrorCode.ACCOUNT_PENDING);
		assertThat(graphQl.get(path, bearerFor(memberId)).status()).isEqualTo(404);
	}

	@Test
	void 헬스체크는_승인_대기_계정과_비로그인에도_열려_있다() {
		assertThat(graphQl.get("/actuator/health", null).status()).isNotIn(401, 403, 404);
		assertThat(graphQl.get("/actuator/health", bearerFor(pendingId)).status()).isNotIn(401, 403, 404);
	}

	@Test
	void 헬스체크_응답에_상세_정보가_없다() {
		GraphQlResponse response = graphQl.get("/actuator/health", null);

		assertThat(response.body().fieldNames()).toIterable().containsExactly("status");
	}

}
