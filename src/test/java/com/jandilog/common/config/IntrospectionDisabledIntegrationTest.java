package com.jandilog.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import com.jandilog.testsupport.auth.AuthIntegrationTest;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;

import graphql.introspection.Introspection;

// 운영 프로필이 켜는 spring.graphql.schema.introspection.enabled=false가 실제로 스키마 조회를 막는지 확인한다
@TestPropertySource(properties = "spring.graphql.schema.introspection.enabled=false")
class IntrospectionDisabledIntegrationTest extends AuthIntegrationTest {

	// Spring Boot가 introspection을 JVM 전체 플래그로 끈다. 다른 테스트 클래스가 영향받지 않게 되돌린다
	@AfterAll
	static void restoreIntrospection() {
		Introspection.enabledJvmWide(true);
	}

	private long memberId;
	private long adminId;

	@BeforeEach
	void setUp() {
		memberId = members.active();
		adminId = members.admin();
	}

	private void assertSchemaHidden(GraphQlResponse response) {
		assertThat(response.hasErrors()).isTrue();
		assertThat(response.dataIsNull()).isTrue();
		// 스키마 내용(타입·필드 이름)이 응답에 실리지 않는다
		assertThat(response.rawBody()).doesNotContain("adminMembers", "approveMember", "exchangeAuthCode", "AuthPayload");
	}

	@Test
	void ACTIVE_회원과_관리자도_introspection으로_스키마를_볼_수_없다() {
		assertSchemaHidden(graphQl.post(bearerFor(memberId), "{ __schema { types { name } } }"));
		assertSchemaHidden(graphQl.post(bearerFor(adminId), "{ __schema { queryType { fields { name } } } }"));
		assertSchemaHidden(graphQl.post(bearerFor(adminId), "{ __type(name: \"Query\") { fields { name } } }"));
	}

	@Test
	void 비로그인도_스키마를_볼_수_없다() {
		assertSchemaHidden(graphQl.post(null, "{ __schema { types { name } } }"));
	}

	@Test
	void introspection을_꺼도_일반_조회는_그대로_동작한다() {
		GraphQlResponse response = graphQl.post(bearerFor(memberId), "{ me { id status } }");

		assertThat(response.status()).isEqualTo(200);
		assertThat(response.hasErrors()).isFalse();
		assertThat(response.data().path("me").path("id").asText()).isEqualTo(Long.toString(memberId));
	}

	@Test
	void introspection을_꺼도_typename은_쓸_수_있다() {
		GraphQlResponse response = graphQl.post(bearerFor(memberId), "{ __typename }");

		assertThat(response.hasErrors()).isFalse();
		assertThat(response.data().path("__typename").asText()).isEqualTo("Query");
	}

}
