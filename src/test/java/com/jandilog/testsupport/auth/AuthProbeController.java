package com.jandilog.testsupport.auth;

import java.util.Map;

import org.springframework.boot.test.context.TestComponent;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

// 접근 제어 시험용 리졸버. @TestComponent라서 컴포넌트 스캔에는 잡히지 않고 @Import한 컨텍스트에만 뜬다
@TestComponent
@Controller
public class AuthProbeController {

	// 일부러 @PreAuthorize를 빼서 RootFieldAccessGuard만으로 막히는지 본다
	@QueryMapping
	public String probeOpen() {
		return "open";
	}

	@QueryMapping
	@PreAuthorize("hasRole('MEMBER')")
	public String probeMember() {
		return "member";
	}

	// child는 비어 있어 중첩 쿼리도 null로 끝난다. 깊이·복잡도는 실행 전에 쿼리 모양만으로 잰다
	@QueryMapping
	@PreAuthorize("hasRole('MEMBER')")
	public Map<String, Object> probeTree() {
		return Map.of("id", "root");
	}

	// Query.me와 이름만 같은 Mutation 필드. 허용 목록이 루트 타입까지 보는지 시험한다
	@MutationMapping
	@PreAuthorize("isAuthenticated()")
	public String me() {
		return "mutation-me";
	}

	@MutationMapping
	@PreAuthorize("hasRole('MEMBER')")
	public String probeMemberWrite() {
		return "member-write";
	}

	@QueryMapping
	@PreAuthorize("isAuthenticated()")
	public String probeFail() {
		throw new IllegalStateException("db password=do-not-leak jdbc:mariadb://secret-host/jandilog");
	}

}
