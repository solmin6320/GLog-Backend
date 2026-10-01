package com.jandilog.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.jandilog.common.exception.ErrorCode;
import com.jandilog.member.domain.MemberRole;
import com.jandilog.member.domain.MemberStatus;
import com.jandilog.testsupport.auth.AuthIntegrationTest;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;
import com.jandilog.testsupport.auth.TestTokens;

// 계정 상태·역할별 접근 (E-01 승인 대기, E-02 거절, E-03 비로그인, E-09 권한 없음)과
// RootFieldAccessGuard(허용 목록 밖의 루트 필드는 @PreAuthorize가 없어도 ACTIVE 회원만).
// 운영 스키마에는 ACTIVE 전용 필드가 아직 없어서 테스트 전용 probe 필드(AuthProbeController)를 함께 쓴다
class AccountAccessIntegrationTest extends AuthIntegrationTest {

	private enum Kind {
		ANONYMOUS, PENDING, REJECTED, MEMBER, ADMIN
	}

	private static final String ME = "{ me { id githubLogin status role } }";
	private static final String PROBE_OPEN = "{ probeOpen }";
	private static final String PROBE_MEMBER = "{ probeMember }";
	private static final String PROBE_MEMBER_WRITE = "mutation { probeMemberWrite }";
	private static final String ADMIN_LIST = "{ adminMembers { totalCount nextCursor items { id } } }";
	private static final String APPROVE = "mutation($id: ID!) { approveMember(memberId: $id) { id status } }";
	private static final String REJECT = "mutation($id: ID!) { rejectMember(memberId: $id) { id status } }";
	private static final String REVERT = "mutation($id: ID!) { revertMemberToPending(memberId: $id) { id status } }";

	private byte[] secret;
	private long pendingId;
	private long rejectedId;
	private long memberId;
	private long adminId;

	@BeforeEach
	void setUp() {
		secret = authProperties.jwt().secret().getBytes(StandardCharsets.UTF_8);
		pendingId = members.pending();
		rejectedId = members.rejected();
		memberId = members.active();
		adminId = members.admin();
	}

	private String tokenOf(long id) {
		Instant now = Instant.now();
		return TestTokens.hs256(secret,
				TestTokens.claims(Long.toString(id), authProperties.jwt().issuer(), now, now.plus(Duration.ofHours(1))));
	}

	private String tokenOf(Kind kind) {
		return switch (kind) {
			case ANONYMOUS -> null;
			case PENDING -> tokenOf(pendingId);
			case REJECTED -> tokenOf(rejectedId);
			case MEMBER -> tokenOf(memberId);
			case ADMIN -> tokenOf(adminId);
		};
	}

	private void assertDenied(GraphQlResponse response, ErrorCode expected) {
		// 필드 단위 오류는 HTTP 200에 GraphQL errors로 내려간다
		assertThat(response.status()).isEqualTo(200);
		assertThat(response.errorCode()).isEqualTo(expected.name());
		assertThat(response.errorMessage()).isEqualTo(expected.message());
		assertThat(response.errorClassification()).isEqualTo(expected.graphQlType().name());
		assertThat(response.dataIsNull()).isTrue();
	}

	// 필드가 nullable이라 data 전체가 null이 되지 않는 경우(__type): 오류는 같고 값만 null이다
	private void assertDeniedNullable(GraphQlResponse response, ErrorCode expected) {
		assertThat(response.status()).isEqualTo(200);
		assertThat(response.errorCode()).isEqualTo(expected.name());
		assertThat(response.errorMessage()).isEqualTo(expected.message());
		assertThat(response.data().path("__type").isNull()).isTrue();
		assertThat(response.rawBody()).doesNotContain("\"name\":\"Query\"");
	}

	private void assertAllowed(GraphQlResponse response) {
		assertThat(response.status()).isEqualTo(200);
		assertThat(response.hasErrors()).as(response.rawBody()).isFalse();
	}

	private static Stream<Arguments> deniedByStatus() {
		return Stream.of(
				Arguments.of(Kind.ANONYMOUS, ErrorCode.UNAUTHENTICATED),
				Arguments.of(Kind.PENDING, ErrorCode.ACCOUNT_PENDING),
				Arguments.of(Kind.REJECTED, ErrorCode.ACCOUNT_REJECTED));
	}

	// ----- me: 로그인한 모든 계정(승인 대기·거절 포함)만 볼 수 있다 -----

	@ParameterizedTest
	@MethodSource("meAccess")
	void me는_로그인한_모든_계정이_볼_수_있고_비로그인만_거부된다(Kind kind, ErrorCode deniedWith) {
		GraphQlResponse response = graphQl.post(tokenOf(kind), ME);

		if (deniedWith == null) {
			assertAllowed(response);
		} else {
			assertDenied(response, deniedWith);
		}
	}

	private static Stream<Arguments> meAccess() {
		return Stream.of(
				Arguments.of(Kind.ANONYMOUS, ErrorCode.UNAUTHENTICATED),
				Arguments.of(Kind.PENDING, null),
				Arguments.of(Kind.REJECTED, null),
				Arguments.of(Kind.MEMBER, null),
				Arguments.of(Kind.ADMIN, null));
	}

	@Test
	void 승인_대기와_거절_계정은_me에서_자기_상태를_본다() {
		var pending = graphQl.post(tokenOf(Kind.PENDING), ME).data().path("me");
		var rejected = graphQl.post(tokenOf(Kind.REJECTED), ME).data().path("me");

		assertThat(pending.path("status").asText()).isEqualTo("PENDING");
		assertThat(pending.path("id").asText()).isEqualTo(Long.toString(pendingId));
		assertThat(rejected.path("status").asText()).isEqualTo("REJECTED");
	}

	// ----- ACTIVE 회원 이상만 (hasRole MEMBER) -----

	@ParameterizedTest
	@MethodSource("deniedByStatus")
	void ACTIVE_전용_조회는_비로그인_승인대기_거절을_각각_다른_오류로_막는다(Kind kind, ErrorCode expected) {
		assertDenied(graphQl.post(tokenOf(kind), PROBE_MEMBER), expected);
	}

	@ParameterizedTest
	@MethodSource("deniedByStatus")
	void ACTIVE_전용_변경도_같은_오류로_막는다(Kind kind, ErrorCode expected) {
		assertDenied(graphQl.post(tokenOf(kind), PROBE_MEMBER_WRITE), expected);
	}

	@Test
	void 활성_회원과_관리자는_ACTIVE_전용_필드를_쓸_수_있다() {
		assertAllowed(graphQl.post(tokenOf(Kind.MEMBER), PROBE_MEMBER));
		assertAllowed(graphQl.post(tokenOf(Kind.ADMIN), PROBE_MEMBER));
		assertAllowed(graphQl.post(tokenOf(Kind.MEMBER), PROBE_MEMBER_WRITE));
		assertAllowed(graphQl.post(tokenOf(Kind.ADMIN), PROBE_MEMBER_WRITE));
	}

	// ----- 관리자 전용 (hasRole ADMIN) -----

	@ParameterizedTest
	@MethodSource("deniedByStatus")
	void 관리자_조회는_비로그인_승인대기_거절을_각각_다른_오류로_막는다(Kind kind, ErrorCode expected) {
		assertDenied(graphQl.post(tokenOf(kind), ADMIN_LIST), expected);
	}

	@Test
	void 일반_회원이_관리자_조회를_부르면_E09다() {
		assertDenied(graphQl.post(tokenOf(Kind.MEMBER), ADMIN_LIST), ErrorCode.FORBIDDEN);
	}

	@Test
	void 관리자는_관리자_조회를_부를_수_있다() {
		assertAllowed(graphQl.post(tokenOf(Kind.ADMIN), ADMIN_LIST));
	}

	@Test
	void 관리자가_아닌_계정은_승인할_수_없고_데이터는_그대로다() {
		assertAdminMutationDenied(APPROVE, pendingId);
	}

	@Test
	void 관리자가_아닌_계정은_거절할_수_없고_로그도_남지_않는다() {
		assertAdminMutationDenied(REJECT, pendingId);
	}

	@Test
	void 관리자가_아닌_계정은_거절_회원을_되돌릴_수_없다() {
		assertAdminMutationDenied(REVERT, rejectedId);
	}

	private void assertAdminMutationDenied(String mutation, long target) {
		MemberStatus before = members.find(target).orElseThrow().status();
		Map<String, Object> variables = Map.of("id", Long.toString(target));

		assertDenied(graphQl.post(null, mutation, variables), ErrorCode.UNAUTHENTICATED);
		assertDenied(graphQl.post(tokenOf(Kind.PENDING), mutation, variables), ErrorCode.ACCOUNT_PENDING);
		assertDenied(graphQl.post(tokenOf(Kind.REJECTED), mutation, variables), ErrorCode.ACCOUNT_REJECTED);
		assertDenied(graphQl.post(tokenOf(Kind.MEMBER), mutation, variables), ErrorCode.FORBIDDEN);

		assertThat(members.find(target).orElseThrow().status()).isEqualTo(before);
		assertThat(members.countActionLogs(target)).isZero();
	}

	// ----- 기본 거부: RootFieldAccessGuard -----

	@Test
	void PreAuthorize가_없는_필드도_비로그인은_막는다() {
		assertDenied(graphQl.post(null, PROBE_OPEN), ErrorCode.UNAUTHENTICATED);
	}

	@ParameterizedTest
	@MethodSource("deniedByStatus")
	void PreAuthorize가_없는_조회는_비로그인_승인대기_거절을_각각_다른_오류로_막는다(Kind kind, ErrorCode expected) {
		assertDenied(graphQl.post(tokenOf(kind), PROBE_OPEN), expected);
	}

	@Test
	void PreAuthorize가_없는_필드는_ACTIVE_회원과_관리자만_쓸_수_있다() {
		assertAllowed(graphQl.post(tokenOf(Kind.MEMBER), PROBE_OPEN));
		assertAllowed(graphQl.post(tokenOf(Kind.ADMIN), PROBE_OPEN));
	}

	@Test
	void 승인_대기_계정이_me와_다른_필드를_함께_부르면_다른_필드는_거부된다() {
		String token = tokenOf(Kind.PENDING);
		assertAllowed(graphQl.post(token, ME));

		assertDenied(graphQl.post(token, "{ me { id } probeOpen }"), ErrorCode.ACCOUNT_PENDING);
	}

	@Test
	void 승인_대기와_거절_계정은_루트_메타_필드도_쓸_수_없다() {
		assertDenied(graphQl.post(tokenOf(Kind.PENDING), "{ __typename }"), ErrorCode.ACCOUNT_PENDING);
		assertDenied(graphQl.post(tokenOf(Kind.REJECTED), "{ __typename }"), ErrorCode.ACCOUNT_REJECTED);
		assertDenied(graphQl.post(tokenOf(Kind.PENDING), "{ __schema { queryType { name } } }"),
				ErrorCode.ACCOUNT_PENDING);
		assertDeniedNullable(graphQl.post(tokenOf(Kind.REJECTED), "{ __type(name: \"Query\") { name } }"),
				ErrorCode.ACCOUNT_REJECTED);
		assertDenied(graphQl.post(tokenOf(Kind.PENDING), "mutation { __typename }"), ErrorCode.ACCOUNT_PENDING);
	}

	@Test
	void 비로그인은_루트_메타_필드도_쓸_수_없다() {
		assertDenied(graphQl.post(null, "{ __typename }"), ErrorCode.UNAUTHENTICATED);
		assertDeniedNullable(graphQl.post(null, "{ __type(name: \"Query\") { name } }"), ErrorCode.UNAUTHENTICATED);
		assertDenied(graphQl.post(null, "mutation { __typename }"), ErrorCode.UNAUTHENTICATED);
	}

	@Test
	void ACTIVE_회원은_루트_메타_필드를_쓸_수_있다() {
		GraphQlResponse typename = graphQl.post(tokenOf(Kind.MEMBER), "{ __typename }");
		GraphQlResponse schema = graphQl.post(tokenOf(Kind.ADMIN), "{ __schema { queryType { name } } }");

		assertAllowed(typename);
		assertThat(typename.data().path("__typename").asText()).isEqualTo("Query");
		assertAllowed(schema);
		assertThat(schema.data().path("__schema").path("queryType").path("name").asText()).isEqualTo("Query");
	}

	@Test
	void 허용_목록은_루트_타입까지_맞춰서_Mutation의_me는_로그인만으로_열리지_않는다() {
		// 테스트 스키마의 Mutation.me는 Query.me와 이름만 같다
		assertAllowed(graphQl.post(tokenOf(Kind.PENDING), ME));
		assertDenied(graphQl.post(tokenOf(Kind.PENDING), "mutation { me }"), ErrorCode.ACCOUNT_PENDING);
		assertDenied(graphQl.post(tokenOf(Kind.REJECTED), "mutation { me }"), ErrorCode.ACCOUNT_REJECTED);
		assertDenied(graphQl.post(null, "mutation { me }"), ErrorCode.UNAUTHENTICATED);
		assertAllowed(graphQl.post(tokenOf(Kind.MEMBER), "mutation { me }"));
	}

	@Test
	void 별칭을_붙여도_비로그인은_막힌다() {
		GraphQlResponse response = graphQl.post(null, "{ innocent: me { id } }");

		assertDenied(response, ErrorCode.UNAUTHENTICATED);
	}

	@Test
	void 프래그먼트로_감싸도_비로그인은_막힌다() {
		GraphQlResponse response = graphQl.post(null, "query { ...F } fragment F on Query { me { id } }");

		assertDenied(response, ErrorCode.UNAUTHENTICATED);
	}

	@Test
	void 한_요청에_허용_필드를_같이_넣어도_막힌_필드는_실행되지_않는다() {
		GraphQlResponse response = graphQl.post(null, """
				mutation($id: ID!) {
				  approveMember(memberId: $id) { id }
				  exchangeAuthCode(code: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA") { accessToken }
				}
				""", Map.of("id", Long.toString(pendingId)));

		assertDenied(response, ErrorCode.UNAUTHENTICATED);
		assertThat(members.find(pendingId).orElseThrow().status()).isEqualTo(MemberStatus.PENDING);
	}

	@Test
	void 비로그인은_인트로스펙션으로_스키마를_볼_수_없다() {
		assertDenied(graphQl.post(null, "{ __schema { queryType { name } } }"), ErrorCode.UNAUTHENTICATED);
	}

	@Test
	void 비로그인도_일회용_코드_교환은_부를_수_있다() {
		GraphQlResponse response = graphQl.post(null,
				"mutation { exchangeAuthCode(code: \"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\") { accessToken } }");

		// 인증 오류가 아니라 코드 오류로 처리된다 (가드를 통과했다는 뜻)
		assertThat(response.errorCode()).isEqualTo("AUTH_CODE_INVALID");
	}

	// ----- 상태·역할은 토큰이 아니라 DB로 판정한다 -----

	@Test
	void 승인되면_같은_토큰으로_바로_ACTIVE_전용_필드를_쓸_수_있다() {
		String token = tokenOf(Kind.PENDING);
		assertDenied(graphQl.post(token, PROBE_MEMBER), ErrorCode.ACCOUNT_PENDING);

		// 관리자가 API로 승인
		GraphQlResponse approve = graphQl.post(tokenOf(Kind.ADMIN), APPROVE, Map.of("id", Long.toString(pendingId)));
		assertAllowed(approve);

		assertAllowed(graphQl.post(token, PROBE_MEMBER));
		assertThat(graphQl.post(token, ME).data().path("me").path("status").asText()).isEqualTo("ACTIVE");
	}

	@Test
	void 거절되면_같은_토큰이_바로_거절_상태로_막힌다() {
		String token = tokenOf(Kind.PENDING);

		assertAllowed(graphQl.post(tokenOf(Kind.ADMIN), REJECT, Map.of("id", Long.toString(pendingId))));

		assertDenied(graphQl.post(token, PROBE_MEMBER), ErrorCode.ACCOUNT_REJECTED);
		assertThat(graphQl.post(token, ME).data().path("me").path("status").asText()).isEqualTo("REJECTED");
	}

	@Test
	void 활성_회원이_거절_상태로_바뀌면_같은_토큰이_바로_막힌다() {
		String token = tokenOf(Kind.MEMBER);
		assertAllowed(graphQl.post(token, PROBE_MEMBER));

		members.setStatus(memberId, MemberStatus.REJECTED);

		assertDenied(graphQl.post(token, PROBE_MEMBER), ErrorCode.ACCOUNT_REJECTED);
	}

	@Test
	void 관리자_역할이_바뀌면_같은_토큰의_관리자_기능이_바로_달라진다() {
		String token = tokenOf(Kind.MEMBER);
		assertDenied(graphQl.post(token, ADMIN_LIST), ErrorCode.FORBIDDEN);

		members.setRole(memberId, MemberRole.ADMIN);
		assertAllowed(graphQl.post(token, ADMIN_LIST));

		members.setRole(memberId, MemberRole.MEMBER);
		assertDenied(graphQl.post(token, ADMIN_LIST), ErrorCode.FORBIDDEN);
	}

	@Test
	void 승인_대기_계정은_관리자_역할이어도_관리자_기능을_쓸_수_없다() {
		members.setRole(pendingId, MemberRole.ADMIN);

		assertDenied(graphQl.post(tokenOf(Kind.PENDING), ADMIN_LIST), ErrorCode.ACCOUNT_PENDING);
	}

	@Test
	void 토큰에_status와_role을_담아도_DB_값만_따른다() {
		Instant now = Instant.now();
		Map<String, Object> claims = TestTokens.claims(Long.toString(pendingId), authProperties.jwt().issuer(), now,
				now.plus(Duration.ofHours(1)));
		claims.put("status", "ACTIVE");
		claims.put("role", "ADMIN");
		claims.put("roles", List.of("ROLE_ADMIN", "ROLE_MEMBER"));
		String token = TestTokens.hs256(secret, claims);

		assertDenied(graphQl.post(token, PROBE_MEMBER), ErrorCode.ACCOUNT_PENDING);
		assertDenied(graphQl.post(token, ADMIN_LIST), ErrorCode.ACCOUNT_PENDING);
		assertThat(graphQl.post(token, ME).data().path("me").path("status").asText()).isEqualTo("PENDING");
	}

	// ----- 예상하지 못한 오류 (E-52) -----

	@Test
	void 예상하지_못한_예외는_내부_정보_없이_E52_문구만_내린다() {
		GraphQlResponse response = graphQl.post(tokenOf(Kind.MEMBER), "{ probeFail }");

		assertThat(response.status()).isEqualTo(200);
		assertThat(response.errorCode()).isEqualTo("INTERNAL_ERROR");
		assertThat(response.errorMessage()).isEqualTo("잠시 문제가 생겼어요. 조금 뒤에 다시 시도해 주세요.");
		assertThat(response.errorClassification()).isEqualTo("INTERNAL_ERROR");
		assertThat(response.rawBody()).doesNotContain("do-not-leak", "jdbc", "IllegalStateException", "secret-host",
				"at com.jandilog");
	}

}
