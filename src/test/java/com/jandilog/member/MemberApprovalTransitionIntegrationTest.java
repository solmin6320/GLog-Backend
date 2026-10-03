package com.jandilog.member;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.util.UriComponentsBuilder;

import com.jandilog.common.exception.ErrorCode;
import com.jandilog.common.security.OAuthLoginSuccessHandler;
import com.jandilog.member.domain.MemberRole;
import com.jandilog.member.domain.MemberStatus;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;
import com.jandilog.testsupport.auth.MemberFixture.MemberRow;

// 가입 승인 상태 전환: 승인(PENDING→ACTIVE), 거절(PENDING→REJECTED, 로그 1건), 되돌리기(REJECTED→PENDING) (기능명세서 1·9장)
class MemberApprovalTransitionIntegrationTest extends ApprovalIntegrationTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final String CONFLICT_MESSAGE = ErrorCode.CONFLICT.message();

	@Autowired
	private OAuthLoginSuccessHandler loginHandler;

	private List<Map<String, Object>> logsOf(long targetMemberId) {
		return jdbc.queryForList("select * from admin_action_log where target_type = 'MEMBER' and target_id = ?",
				Long.toString(targetMemberId));
	}

	private int logsOfAdmin() {
		Integer count = jdbc.queryForObject("select count(*) from admin_action_log where admin_id = ?", Integer.class,
				adminId);
		return count == null ? 0 : count;
	}

	private void assertError(GraphQlResponse response, ErrorCode code) {
		assertThat(response.status()).isEqualTo(200);
		assertThat(response.errorCode()).isEqualTo(code.name());
		assertThat(response.errorMessage()).isEqualTo(code.message());
		assertThat(response.dataIsNull()).isTrue();
	}

	// ----- 승인 -----

	@Test
	void 승인하면_ACTIVE가_되고_승인_시각이_기록된다() {
		Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		clock.fixAt(now);
		long id = members.pending();

		GraphQlResponse response = approve(adminToken, id);

		assertThat(response.hasErrors()).isFalse();
		var result = response.data().path("approveMember");
		assertThat(result.path("id").asText()).isEqualTo(Long.toString(id));
		assertThat(result.path("status").asText()).isEqualTo("ACTIVE");
		MemberRow row = members.find(id).orElseThrow();
		assertThat(row.status()).isEqualTo(MemberStatus.ACTIVE);
		assertThat(row.approvedAt()).isEqualTo(LocalDateTime.ofInstant(now, KST));
		assertThat(logsOf(id)).isEmpty();
	}

	@Test
	void 승인된_회원은_승인_대기_목록에서_빠진다() {
		long id = members.pending();
		assertThat(page("PENDING", members.tag(), null).ids()).contains(id);

		approve(adminToken, id);

		assertThat(page("ALL", members.tag(), null).ids()).doesNotContain(id);
	}

	@Test
	void 이미_승인된_회원을_다시_승인하면_CONFLICT이고_승인_시각은_그대로다() {
		Instant first = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		clock.fixAt(first);
		long id = members.pending();
		assertThat(approve(adminToken, id).hasErrors()).isFalse();
		clock.fixAt(first.plusSeconds(3600));

		GraphQlResponse second = approve(adminToken, id);

		assertError(second, ErrorCode.CONFLICT);
		assertThat(second.errorMessage()).isEqualTo(CONFLICT_MESSAGE);
		MemberRow row = members.find(id).orElseThrow();
		assertThat(row.status()).isEqualTo(MemberStatus.ACTIVE);
		assertThat(row.approvedAt()).isEqualTo(LocalDateTime.ofInstant(first, KST));
	}

	@Test
	void 거절된_회원을_바로_승인할_수_없다() {
		long id = members.rejected();

		assertError(approve(adminToken, id), ErrorCode.CONFLICT);

		MemberRow row = members.find(id).orElseThrow();
		assertThat(row.status()).isEqualTo(MemberStatus.REJECTED);
		assertThat(row.approvedAt()).isNull();
	}

	// ----- 거절 -----

	@Test
	void 거절하면_REJECTED가_되고_관리자_행동_로그가_정확히_1건_남는다() {
		Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		clock.fixAt(now);
		long id = members.pending();

		GraphQlResponse response = reject(adminToken, id);

		assertThat(response.hasErrors()).isFalse();
		assertThat(response.data().path("rejectMember").path("status").asText()).isEqualTo("REJECTED");
		MemberRow row = members.find(id).orElseThrow();
		assertThat(row.status()).isEqualTo(MemberStatus.REJECTED);
		assertThat(row.approvedAt()).isNull();
		List<Map<String, Object>> logs = logsOf(id);
		assertThat(logs).hasSize(1);
		Map<String, Object> log = logs.get(0);
		assertThat(log.get("admin_id")).isEqualTo(adminId);
		assertThat(log.get("action")).isEqualTo("REJECT_MEMBER");
		assertThat(log.get("target_type")).isEqualTo("MEMBER");
		assertThat(log.get("target_id")).isEqualTo(Long.toString(id));
		assertThat(log.get("reason")).isNull();
		assertThat(((Timestamp) log.get("created_at")).toLocalDateTime()).isEqualTo(LocalDateTime.ofInstant(now, KST));
	}

	@Test
	void 이미_거절된_회원을_다시_거절하면_CONFLICT이고_로그는_늘지_않는다() {
		long id = members.pending();
		assertThat(reject(adminToken, id).hasErrors()).isFalse();

		assertError(reject(adminToken, id), ErrorCode.CONFLICT);
		assertError(reject(adminToken, id), ErrorCode.CONFLICT);

		assertThat(logsOf(id)).hasSize(1);
		assertThat(members.find(id).orElseThrow().status()).isEqualTo(MemberStatus.REJECTED);
	}

	@Test
	void 승인된_회원은_거절할_수_없고_로그도_남지_않는다() {
		long id = members.active();

		assertError(reject(adminToken, id), ErrorCode.CONFLICT);

		assertThat(members.find(id).orElseThrow().status()).isEqualTo(MemberStatus.ACTIVE);
		assertThat(logsOf(id)).isEmpty();
	}

	@Test
	void 거절한_관리자_id가_로그에_남는다() {
		long otherAdmin = members.admin();
		long first = members.pending();
		long second = members.pending();

		reject(adminToken, first);
		reject(tokenOf(otherAdmin), second);

		assertThat(logsOf(first).get(0).get("admin_id")).isEqualTo(adminId);
		assertThat(logsOf(second).get(0).get("admin_id")).isEqualTo(otherAdmin);
	}

	// ----- 되돌리기 -----

	@Test
	void 거절된_회원을_승인_대기로_되돌린다() {
		long id = members.pending();
		reject(adminToken, id);

		GraphQlResponse response = revert(adminToken, id);

		assertThat(response.hasErrors()).isFalse();
		assertThat(response.data().path("revertMemberToPending").path("status").asText()).isEqualTo("PENDING");
		MemberRow row = members.find(id).orElseThrow();
		assertThat(row.status()).isEqualTo(MemberStatus.PENDING);
		assertThat(row.approvedAt()).isNull();
		// 되돌리기는 로그 대상이 아니다. 앞선 거절 로그 1건만 있다
		assertThat(logsOf(id)).hasSize(1);
	}

	@Test
	void 승인_대기나_활성_회원은_되돌릴_수_없다() {
		long pending = members.pending();
		long active = members.active();

		assertError(revert(adminToken, pending), ErrorCode.CONFLICT);
		assertError(revert(adminToken, active), ErrorCode.CONFLICT);

		assertThat(members.find(pending).orElseThrow().status()).isEqualTo(MemberStatus.PENDING);
		assertThat(members.find(active).orElseThrow().status()).isEqualTo(MemberStatus.ACTIVE);
	}

	@Test
	void 거절_뒤_되돌리고_다시_승인할_수_있다() {
		Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		clock.fixAt(now);
		long id = members.pending();

		assertThat(reject(adminToken, id).hasErrors()).isFalse();
		assertThat(revert(adminToken, id).hasErrors()).isFalse();
		assertThat(approve(adminToken, id).hasErrors()).isFalse();

		MemberRow row = members.find(id).orElseThrow();
		assertThat(row.status()).isEqualTo(MemberStatus.ACTIVE);
		assertThat(row.approvedAt()).isEqualTo(LocalDateTime.ofInstant(now, KST));
		assertThat(logsOf(id)).hasSize(1);
	}

	@Test
	void 거절된_회원은_로그인해도_코드를_받지_못하고_되돌리면_다시_받는다() {
		long id = members.pending();
		long githubId = members.find(id).orElseThrow().githubId();
		reject(adminToken, id);

		String whileRejected = loginAndGetLocation(githubId);
		revert(adminToken, id);
		String afterRevert = loginAndGetLocation(githubId);

		assertThat(whileRejected).contains("error=rejected").doesNotContain("code=");
		assertThat(afterRevert).contains("code=").doesNotContain("error=");
	}

	// ----- 없는 회원·잘못된 id -----

	@Test
	void 없는_회원은_세_동작_모두_NOT_FOUND이고_로그가_남지_않는다() {
		long missing = 9_000_000_000_000_000_000L;

		assertError(approve(adminToken, missing), ErrorCode.NOT_FOUND);
		assertError(reject(adminToken, missing), ErrorCode.NOT_FOUND);
		assertError(revert(adminToken, missing), ErrorCode.NOT_FOUND);

		assertThat(logsOfAdmin()).isZero();
	}

	@ParameterizedTest
	@ValueSource(strings = {"abc", "", " ", "0", "-5", "1.5", "1e3", " 5", "99999999999999999999", "0x10"})
	void 회원_id_형식이_틀리면_INVALID_INPUT이다(String memberId) {
		assertError(approve(adminToken, memberId), ErrorCode.INVALID_INPUT);
		assertError(reject(adminToken, memberId), ErrorCode.INVALID_INPUT);
		assertError(revert(adminToken, memberId), ErrorCode.INVALID_INPUT);
		assertThat(logsOfAdmin()).isZero();
	}

	@Test
	void memberId를_빼면_요청이_거부된다() {
		GraphQlResponse response = graphQl.post(adminToken, "mutation { approveMember { id } }");

		assertThat(response.hasErrors()).isTrue();
		assertThat(response.dataIsNull()).isTrue();
	}

	@Test
	void 거절_응답에_회원_정보가_담긴다() {
		long id = members.insert(MemberStatus.PENDING, MemberRole.MEMBER, "octo-" + members.tag(),
				"홍길동");

		var result = reject(adminToken, id).data().path("rejectMember");

		assertThat(result.path("githubLogin").asText()).isEqualTo("octo-" + members.tag());
		assertThat(result.path("nickname").asText()).isEqualTo("홍길동");
		assertThat(result.path("createdAt").asText()).isEqualTo("2026-01-02T03:04:05");
	}

	private String loginAndGetLocation(long githubId) {
		Map<String, Object> attributes = new HashMap<>();
		attributes.put("id", githubId);
		attributes.put("login", "relogin-" + members.tag());
		MockHttpServletRequest request = new MockHttpServletRequest();
		MockHttpServletResponse response = new MockHttpServletResponse();
		OAuth2User user = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_USER")), attributes, "id");
		try {
			loginHandler.onAuthenticationSuccess(request, response,
					new OAuth2AuthenticationToken(user, user.getAuthorities(), "github"));
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		return UriComponentsBuilder.fromUriString(response.getHeader("Location")).build().toUriString();
	}

}
