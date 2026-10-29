package com.jandilog.member;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
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

import com.jandilog.common.exception.ErrorCode;
import com.jandilog.member.domain.MemberRole;
import com.jandilog.member.domain.MemberStatus;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;
import com.jandilog.testsupport.auth.MemberFixture.MemberRow;

// 관리자 지정(2026-10-29 사용자 지시): 관리자만 활성 일반 회원을 관리자로 올리고 관리자 행동 로그(PROMOTE_ADMIN)를 남긴다
class MemberPromoteAdminIntegrationTest extends ApprovalIntegrationTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final String PROMOTE = """
			mutation($id: ID!) {
			  promoteMemberToAdmin(memberId: $id) { id githubLogin nickname status role createdAt }
			}
			""";
	private static final String ADMIN_LIST = "{ adminMembers { totalCount } }";

	private GraphQlResponse promote(String token, Object memberId) {
		return graphQl.post(token, PROMOTE, Map.of("id", memberId.toString()));
	}

	private List<Map<String, Object>> logsOf(long targetMemberId) {
		return jdbc.queryForList("select * from admin_action_log where target_type = 'MEMBER' and target_id = ?",
				Long.toString(targetMemberId));
	}

	private void assertError(GraphQlResponse response, ErrorCode code) {
		assertThat(response.status()).isEqualTo(200);
		assertThat(response.errorCode()).as(response.rawBody()).isEqualTo(code.name());
		assertThat(response.errorMessage()).isEqualTo(code.message());
		assertThat(response.dataIsNull()).isTrue();
	}

	@Test
	void 관리자가_활성_회원을_지정하면_관리자가_되고_로그가_한_건_남는다() {
		Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		clock.fixAt(now);
		long id = members.active();
		MemberRow before = members.find(id).orElseThrow();

		GraphQlResponse response = promote(adminToken, id);

		assertThat(response.hasErrors()).as(response.rawBody()).isFalse();
		var result = response.data().path("promoteMemberToAdmin");
		assertThat(result.path("id").asText()).isEqualTo(Long.toString(id));
		assertThat(result.path("githubLogin").asText()).isEqualTo(before.githubLogin());
		assertThat(result.path("status").asText()).isEqualTo("ACTIVE");
		assertThat(result.path("role").asText()).isEqualTo("ADMIN");
		MemberRow row = members.find(id).orElseThrow();
		assertThat(row.status()).isEqualTo(MemberStatus.ACTIVE);
		assertThat(row.role()).isEqualTo(MemberRole.ADMIN);
		// 승인 시각·가입 시각은 그대로다
		assertThat(row.approvedAt()).isEqualTo(before.approvedAt());
		assertThat(row.createdAt()).isEqualTo(before.createdAt());

		List<Map<String, Object>> logs = logsOf(id);
		assertThat(logs).hasSize(1);
		Map<String, Object> log = logs.get(0);
		assertThat(log.get("admin_id")).isEqualTo(adminId);
		assertThat(log.get("action")).isEqualTo("PROMOTE_ADMIN");
		assertThat(log.get("target_type")).isEqualTo("MEMBER");
		assertThat(log.get("target_id")).isEqualTo(Long.toString(id));
		assertThat(log.get("reason")).isNull();
		assertThat(((Timestamp) log.get("created_at")).toLocalDateTime()).isEqualTo(LocalDateTime.ofInstant(now, KST));
	}

	@Test
	void 지정된_회원은_같은_토큰으로_바로_관리자_기능을_쓴다() {
		long id = members.active();
		String token = tokenOf(id);
		assertError(graphQl.post(token, ADMIN_LIST), ErrorCode.FORBIDDEN);

		assertThat(promote(adminToken, id).hasErrors()).isFalse();

		GraphQlResponse allowed = graphQl.post(token, ADMIN_LIST);
		assertThat(allowed.hasErrors()).as(allowed.rawBody()).isFalse();
		GraphQlResponse me = graphQl.post(token, "{ me { status role } }");
		assertThat(me.data().path("me").path("role").asText()).isEqualTo("ADMIN");
	}

	@Test
	void 지정된_관리자도_다른_회원을_관리자로_지정할_수_있다() {
		long second = members.active();
		long third = members.active();
		promote(adminToken, second);

		GraphQlResponse response = promote(tokenOf(second), third);

		assertThat(response.hasErrors()).as(response.rawBody()).isFalse();
		assertThat(members.find(third).orElseThrow().role()).isEqualTo(MemberRole.ADMIN);
		assertThat(logsOf(third).get(0).get("admin_id")).isEqualTo(second);
	}

	@ParameterizedTest
	@ValueSource(strings = {"PENDING", "REJECTED"})
	void 승인_대기와_거절_회원은_지정할_수_없고_그대로다(String status) {
		long id = members.insert(MemberStatus.valueOf(status), MemberRole.MEMBER);

		assertError(promote(adminToken, id), ErrorCode.CONFLICT);

		MemberRow row = members.find(id).orElseThrow();
		assertThat(row.status()).isEqualTo(MemberStatus.valueOf(status));
		assertThat(row.role()).isEqualTo(MemberRole.MEMBER);
		assertThat(logsOf(id)).isEmpty();
	}

	@Test
	void 이미_관리자인_회원과_자기_자신은_지정할_수_없고_로그도_없다() {
		long otherAdmin = members.admin();

		assertError(promote(adminToken, otherAdmin), ErrorCode.CONFLICT);
		assertError(promote(adminToken, adminId), ErrorCode.CONFLICT);

		assertThat(logsOf(otherAdmin)).isEmpty();
		assertThat(logsOf(adminId)).isEmpty();
	}

	@Test
	void 없는_회원은_NOT_FOUND이고_형식이_틀린_id는_잘못된_입력이다() {
		long missing = members.active();
		members.delete(missing);

		assertError(promote(adminToken, missing), ErrorCode.NOT_FOUND);
		for (String bad : List.of("abc", "0", "-1")) {
			assertError(promote(adminToken, bad), ErrorCode.INVALID_INPUT);
		}
	}

	@Test
	void 관리자_둘이_동시에_지정해도_한_번만_반영되고_로그는_한_건이다() throws Exception {
		for (int round = 0; round < 5; round++) {
			long id = members.active();
			String otherAdminToken = tokenOf(members.admin());
			CyclicBarrier barrier = new CyclicBarrier(2);
			ExecutorService pool = Executors.newFixedThreadPool(2);
			try {
				List<Future<GraphQlResponse>> futures = new ArrayList<>();
				for (String token : List.of(adminToken, otherAdminToken)) {
					futures.add(pool.submit(() -> {
						barrier.await(5, TimeUnit.SECONDS);
						return promote(token, id);
					}));
				}
				List<GraphQlResponse> responses = new ArrayList<>();
				for (Future<GraphQlResponse> future : futures) {
					responses.add(future.get(30, TimeUnit.SECONDS));
				}

				assertThat(responses).filteredOn(r -> !r.hasErrors()).hasSize(1);
				assertThat(responses).filteredOn(GraphQlResponse::hasErrors)
						.allSatisfy(r -> assertThat(r.errorCode()).isEqualTo(ErrorCode.CONFLICT.name()));
				assertThat(members.find(id).orElseThrow().role()).isEqualTo(MemberRole.ADMIN);
				assertThat(logsOf(id)).as("round=%d", round).hasSize(1);
			} finally {
				pool.shutdownNow();
			}
		}
	}

}
