package com.jandilog.member;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;

import com.fasterxml.jackson.databind.JsonNode;
import com.jandilog.testsupport.auth.AuthIntegrationTest;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;
import com.jandilog.testsupport.auth.TestTokens;

// 가입 승인 통합 테스트 공통: 관리자 토큰과 목록·전환 호출 도우미 (실제 HTTP /graphql)
abstract class ApprovalIntegrationTest extends AuthIntegrationTest {

	static final String LIST = """
			query($status: MemberApprovalFilter, $keyword: String, $after: String) {
			  adminMembers(status: $status, keyword: $keyword, after: $after) {
			    totalCount
			    nextCursor
			    items { id githubLogin nickname status createdAt }
			  }
			}
			""";

	// 한 페이지를 읽은 결과
	record Page(List<Long> ids, String nextCursor, int totalCount, JsonNode items) {
	}

	protected long adminId;
	protected String adminToken;

	@BeforeEach
	protected void setUpApproval() {
		adminId = members.admin();
		adminToken = tokenOf(adminId);
	}

	protected String tokenOf(long memberId) {
		Instant now = Instant.now();
		return TestTokens.hs256(authProperties.jwt().secret().getBytes(StandardCharsets.UTF_8),
				TestTokens.claims(Long.toString(memberId), authProperties.jwt().issuer(), now, now.plus(Duration.ofHours(1))));
	}

	// null이면 변수를 아예 넣지 않는다 (스키마 기본값 적용)
	protected GraphQlResponse list(String status, String keyword, String after) {
		Map<String, Object> variables = new HashMap<>();
		if (status != null) {
			variables.put("status", status);
		}
		if (keyword != null) {
			variables.put("keyword", keyword);
		}
		if (after != null) {
			variables.put("after", after);
		}
		return graphQl.post(adminToken, LIST, variables);
	}

	protected Page page(String status, String keyword, String after) {
		GraphQlResponse response = list(status, keyword, after);
		if (response.hasErrors()) {
			throw new AssertionError("목록 조회 실패: " + response.rawBody());
		}
		return toPage(response);
	}

	protected static Page toPage(GraphQlResponse response) {
		JsonNode node = response.data().path("adminMembers");
		List<Long> ids = new ArrayList<>();
		for (JsonNode item : node.path("items")) {
			ids.add(Long.parseLong(item.path("id").asText()));
		}
		String next = node.path("nextCursor").isNull() ? null : node.path("nextCursor").asText();
		return new Page(ids, next, node.path("totalCount").asInt(), node.path("items"));
	}

	// nextCursor가 null일 때까지 모든 페이지를 읽는다
	protected List<Page> allPages(String status, String keyword) {
		List<Page> pages = new ArrayList<>();
		String cursor = null;
		do {
			Page page = page(status, keyword, cursor);
			pages.add(page);
			cursor = page.nextCursor();
		} while (cursor != null && pages.size() < 50);
		return pages;
	}

	protected GraphQlResponse approve(String token, Object memberId) {
		return graphQl.post(token, "mutation($id: ID!) { approveMember(memberId: $id) { id githubLogin nickname status createdAt } }",
				Map.of("id", memberId.toString()));
	}

	protected GraphQlResponse reject(String token, Object memberId) {
		return graphQl.post(token, "mutation($id: ID!) { rejectMember(memberId: $id) { id githubLogin nickname status createdAt } }",
				Map.of("id", memberId.toString()));
	}

	protected GraphQlResponse revert(String token, Object memberId) {
		return graphQl.post(token,
				"mutation($id: ID!) { revertMemberToPending(memberId: $id) { id githubLogin nickname status createdAt } }",
				Map.of("id", memberId.toString()));
	}

}
