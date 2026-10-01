package com.jandilog.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.jandilog.common.exception.ErrorCode;
import com.jandilog.member.domain.MemberStatus;
import com.jandilog.testsupport.auth.AuthIntegrationTest;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;

// 쿼리 깊이 10·복잡도 50 한도. 한도를 넘으면 리졸버를 실행하기 전에 막고, 내부 수치 없이 해요체 오류만 내린다
class GraphQlLimitIntegrationTest extends AuthIntegrationTest {

	private static final String CODE = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

	private long memberId;
	private long adminId;
	private long pendingId;

	@BeforeEach
	void setUp() {
		memberId = members.active();
		adminId = members.admin();
		pendingId = members.pending();
	}

	// probeTree { child { child ... id } }: 필드 depth개를 이어 붙인 쿼리
	// probeTree가 1단계, child가 2~(depth-1)단계, 마지막 id가 depth단계
	private static String chain(int depth) {
		return "{ probeTree " + "{ child ".repeat(depth - 2) + "{ id }" + " }".repeat(depth - 2) + " }";
	}

	// 같은 필드를 별칭만 바꿔 count번 부른다
	private static String aliased(int count, String selection) {
		return "{ " + IntStream.range(0, count).mapToObj(i -> "a" + i + ": " + selection)
				.collect(Collectors.joining(" ")) + " }";
	}

	private void assertLimitExceeded(GraphQlResponse response) {
		assertThat(response.status()).isEqualTo(200);
		assertThat(response.errorCode()).isEqualTo(ErrorCode.QUERY_TOO_COMPLEX.name());
		assertThat(response.errorMessage()).isEqualTo("요청이 너무 복잡해요. 조건을 줄여서 다시 시도해 주세요.");
		assertThat(response.errorClassification()).isEqualTo("BAD_REQUEST");
		assertThat(response.dataIsNull()).isTrue();
		// 한도 수치나 라이브러리 문구를 내보내지 않는다
		assertThat(response.rawBody()).doesNotContainIgnoringCase("maximum").doesNotContainIgnoringCase("exceeded")
				.doesNotContainIgnoringCase("depth").doesNotContainIgnoringCase("complexity")
				.doesNotContain("> 10").doesNotContain("> 50").doesNotContain("graphql.");
	}

	private void assertExecuted(GraphQlResponse response) {
		assertThat(response.status()).isEqualTo(200);
		assertThat(response.hasErrors()).as(response.rawBody()).isFalse();
	}

	// ----- 깊이 -----

	@Test
	void 쿼리_모양_도우미가_의도한_깊이를_만든다() {
		assertThat(chain(3)).isEqualTo("{ probeTree { child { id } } }");
		assertThat(chain(2)).isEqualTo("{ probeTree { id } }");
	}

	@ParameterizedTest
	@ValueSource(ints = {2, 5, 10})
	void 깊이_10_이하는_통과한다(int depth) {
		assertExecuted(graphQl.post(bearerFor(memberId), chain(depth)));
	}

	@ParameterizedTest
	@ValueSource(ints = {11, 12, 30, 100})
	void 깊이_10을_넘으면_실행_전에_막는다(int depth) {
		assertLimitExceeded(graphQl.post(bearerFor(memberId), chain(depth)));
	}

	@Test
	void 조각으로_깊이를_숨겨도_막는다() {
		String query = """
				{ probeTree { ...A } }
				fragment A on ProbeNode { child { ...B } }
				fragment B on ProbeNode { child { ...C } }
				fragment C on ProbeNode { child { ...D } }
				fragment D on ProbeNode { child { ...E } }
				fragment E on ProbeNode { child { ...F } }
				fragment F on ProbeNode { child { ...G } }
				fragment G on ProbeNode { child { ...H } }
				fragment H on ProbeNode { child { ...I } }
				fragment I on ProbeNode { child { id } }
				""";

		// probeTree(1) + child 9번(2~10) + id(11)
		assertLimitExceeded(graphQl.post(bearerFor(memberId), query));
	}

	// ----- 복잡도(별칭 남용 포함) -----

	@Test
	void 복잡도_50이면_통과하고_51부터_막는다() {
		// me { id }는 필드 2개라 별칭 25개가 정확히 50
		assertExecuted(graphQl.post(bearerFor(memberId), aliased(25, "me { id }")));
		assertLimitExceeded(graphQl.post(bearerFor(memberId), aliased(26, "me { id }")));
	}

	@ParameterizedTest
	@ValueSource(ints = {100, 500, 2000})
	void 별칭으로_같은_필드를_수백_번_불러도_막는다(int count) {
		assertLimitExceeded(graphQl.post(bearerFor(memberId), aliased(count, "me { id }")));
	}

	@Test
	void 하위_필드가_없는_필드도_별칭으로_반복하면_복잡도에_잡힌다() {
		assertExecuted(graphQl.post(bearerFor(memberId), aliased(50, "probeMember")));
		assertLimitExceeded(graphQl.post(bearerFor(memberId), aliased(51, "probeMember")));
		assertLimitExceeded(graphQl.post(bearerFor(memberId), aliased(1000, "probeMember")));
	}

	@Test
	void 한도를_넘긴_요청은_관리자라도_막는다() {
		assertLimitExceeded(graphQl.post(bearerFor(adminId), aliased(100, "adminMembers { totalCount }")));
	}

	@Test
	void 조각을_거쳐_별칭으로_반복해도_막는다() {
		String query = "{ " + IntStream.range(0, 100).mapToObj(i -> "a" + i + ": me { ...F }")
				.collect(Collectors.joining(" ")) + " } fragment F on Me { id nickname }";

		assertLimitExceeded(graphQl.post(bearerFor(memberId), query));
	}

	@Test
	void 아주_깊은_쿼리는_파서가_먼저_막아서_실행되지_않는다() {
		GraphQlResponse response = graphQl.post(bearerFor(memberId), chain(400));

		assertThat(response.hasErrors()).isTrue();
		assertThat(response.dataIsNull()).isTrue();
		assertThat(response.rawBody()).doesNotContain("at com.jandilog", "Exception");
	}

	@Test
	void 운영_쿼리_모양은_한도_안에_충분히_들어온다() {
		assertExecuted(graphQl.post(bearerFor(adminId), """
				{ me { id githubLogin nickname profileImageUrl status role }
				  adminMembers(status: ALL) { totalCount nextCursor items { id githubLogin nickname status createdAt } } }
				"""));
	}

	// ----- 막힌 요청은 리졸버를 실행하지 않는다 -----

	@Test
	void 한도를_넘긴_변경_요청은_하나도_실행되지_않는다() {
		String mutation = "mutation($id: ID!) { " + IntStream.range(0, 40)
				.mapToObj(i -> "a" + i + ": approveMember(memberId: $id) { id status }")
				.collect(Collectors.joining(" ")) + " }";

		GraphQlResponse response = graphQl.post(bearerFor(adminId), mutation, Map.of("id", Long.toString(pendingId)));

		assertLimitExceeded(response);
		assertThat(members.find(pendingId).orElseThrow().status()).isEqualTo(MemberStatus.PENDING);
		assertThat(members.countActionLogs(pendingId)).isZero();
	}

	@Test
	void 비로그인의_일회용_코드_교환_별칭_남용도_한도에서_막는다() {
		String abuse = "mutation { " + IntStream.range(0, 200)
				.mapToObj(i -> "a" + i + ": exchangeAuthCode(code: \"" + CODE + "\") { accessToken }")
				.collect(Collectors.joining(" ")) + " }";

		assertLimitExceeded(graphQl.post(null, abuse));
	}

	@Test
	void 한도_안의_일회용_코드_교환은_그대로_코드_오류로_처리한다() {
		String ok = "mutation { " + IntStream.range(0, 25)
				.mapToObj(i -> "a" + i + ": exchangeAuthCode(code: \"" + CODE + "\") { accessToken }")
				.collect(Collectors.joining(" ")) + " }";

		GraphQlResponse response = graphQl.post(null, ok);

		assertThat(response.errorCode()).isEqualTo("AUTH_CODE_INVALID");
	}

	@Test
	void 한도_검사_뒤에도_가드가_그대로_동작한다() {
		// 한도 안의 요청: 승인 대기 계정은 여전히 me만 가능
		assertThat(graphQl.post(bearerFor(pendingId), aliased(5, "me { id }")).hasErrors()).isFalse();
		GraphQlResponse denied = graphQl.post(bearerFor(pendingId), aliased(5, "probeOpen"));
		assertThat(denied.errorCode()).isEqualTo(ErrorCode.ACCOUNT_PENDING.name());
	}

}
