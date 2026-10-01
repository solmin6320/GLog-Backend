package com.jandilog.common.security;

import java.util.Set;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import graphql.execution.ExecutionStepInfo;
import graphql.execution.instrumentation.InstrumentationState;
import graphql.execution.instrumentation.SimplePerformantInstrumentation;
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters;
import graphql.schema.DataFetcher;

// /graphql은 URL 단계에서 열려 있으므로(권한은 메서드 단위), 루트 필드 접근을 여기서 기본 거부로 막는다.
// 허용 목록에 없는 필드는 ACTIVE 회원(ROLE_MEMBER)만 부를 수 있어서, 새 리졸버에 @PreAuthorize를 빠뜨려도
// 비로그인·승인 대기·거절 계정은 막힌다
@Component
public class RootFieldAccessGuard extends SimplePerformantInstrumentation {

	// 비로그인도 부를 수 있는 루트 필드. 늘릴 때는 사용자 승인 필요
	private static final Set<String> PUBLIC_ROOT_FIELDS = Set.of("Mutation.exchangeAuthCode");
	// 로그인만 하면(승인 대기·거절 포함) 부를 수 있는 루트 필드. 늘릴 때는 사용자 승인 필요
	private static final Set<String> LOGIN_ONLY_ROOT_FIELDS = Set.of("Query.me");

	@Override
	public DataFetcher<?> instrumentDataFetcher(DataFetcher<?> dataFetcher,
			InstrumentationFieldFetchParameters parameters, InstrumentationState state) {
		ExecutionStepInfo step = parameters.getExecutionStepInfo();
		if (step.getPath().getLevel() != 1) {
			return dataFetcher;
		}
		// 루트 타입까지 붙여서 비교한다. Query와 Mutation에 같은 이름이 생겨도 허용이 번지지 않는다
		String field = step.getObjectType().getName() + "." + step.getFieldDefinition().getName();
		if (PUBLIC_ROOT_FIELDS.contains(field)) {
			return dataFetcher;
		}
		boolean loginOnly = LOGIN_ONLY_ROOT_FIELDS.contains(field);
		return environment -> {
			Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
			if (!isLoggedIn(authentication)) {
				throw new AuthenticationCredentialsNotFoundException("로그인 필요");
			}
			if (!loginOnly && !hasMemberAuthority(authentication)) {
				// 상태별 오류(E-01·E-02·E-09)는 예외 변환기가 계정 상태를 보고 정한다
				throw new AccessDeniedException("ACTIVE 회원만 가능");
			}
			return dataFetcher.get(environment);
		};
	}

	private static boolean isLoggedIn(Authentication authentication) {
		return authentication != null && !(authentication instanceof AnonymousAuthenticationToken)
				&& authentication.isAuthenticated();
	}

	private static boolean hasMemberAuthority(Authentication authentication) {
		return authentication.getAuthorities().stream().anyMatch(a -> Authorities.MEMBER.equals(a.getAuthority()));
	}

}
