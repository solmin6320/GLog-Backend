package com.jandilog.testsupport.admin;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.jandilog.testsupport.auth.AuthIntegrationTest;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;

// 관리자·프로필 통합 테스트 공통: 관리자 토큰, 판정·경고 픽스처, 가짜 잔디 클라이언트.
// 서브클래스 정리가 먼저 돌아야 회원 행을 지울 수 있으므로 AfterEach는 이 클래스에 둔다
@Import(AdminTestConfig.class)
public abstract class AdminIntegrationTest extends AuthIntegrationTest {

	@Autowired
	protected MongoTemplate mongo;
	@Autowired
	protected StubGrassClient grassClient;

	protected AdminFixture data;
	protected long adminId;
	protected String adminToken;

	@BeforeEach
	protected void setUpAdmin() {
		data = new AdminFixture(jdbc, redis, mongo);
		grassClient.clear();
		adminId = data.track(members.admin());
		adminToken = bearerFor(adminId);
	}

	@AfterEach
	protected void tearDownAdmin() {
		data.cleanup();
	}

	protected long activeMember() {
		return data.track(members.active());
	}

	protected long pendingMember() {
		return data.track(members.pending());
	}

	protected GraphQlResponse asAdmin(String query) {
		return graphQl.post(adminToken, query, Map.of());
	}

	protected GraphQlResponse asAdmin(String query, Map<String, Object> variables) {
		return graphQl.post(adminToken, query, variables);
	}

	protected GraphQlResponse asMember(long memberId, String query, Map<String, Object> variables) {
		return graphQl.post(bearerFor(memberId), query, variables);
	}

	// 오류 없이 끝난 응답의 data를 돌려준다. 오류가 있으면 본문을 담아 실패시킨다
	protected static JsonNode dataOf(GraphQlResponse response) {
		if (response.hasErrors()) {
			throw new AssertionError("GraphQL 오류: " + response.rawBody());
		}
		return response.data();
	}

}
