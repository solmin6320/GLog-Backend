package com.jandilog.member.graphql;

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;

import com.jandilog.common.security.AuthenticatedMember;
import com.jandilog.member.dto.AuthPayload;
import com.jandilog.member.dto.MeResponse;
import com.jandilog.member.service.AuthService;

@Controller
public class AuthController {

	private final AuthService authService;

	public AuthController(AuthService authService) {
		this.authService = authService;
	}

	// 로그인 전 호출이라 비로그인 허용 (RootFieldAccessGuard의 허용 목록과 함께 관리)
	@MutationMapping
	@PreAuthorize("permitAll()")
	public AuthPayload exchangeAuthCode(@Argument String code) {
		return authService.exchange(code);
	}

	// 승인 대기·거절 계정도 볼 수 있는 유일한 조회 (AU-03, E-01, E-02). RootFieldAccessGuard의 로그인 전용 목록과 함께 관리
	@QueryMapping
	@PreAuthorize("isAuthenticated()")
	public MeResponse me(@AuthenticationPrincipal AuthenticatedMember member) {
		return authService.me(member.id());
	}

}
