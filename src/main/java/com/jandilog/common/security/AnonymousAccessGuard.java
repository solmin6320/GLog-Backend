package com.jandilog.common.security;

import java.util.Set;

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

// /graphql은 URL 단계에서 열려 있으므로(권한은 메서드 단위), 로그인 없이 부를 수 있는 루트 필드를 여기서 제한한다.
// 새 리졸버에 @PreAuthorize를 빠뜨려도 비로그인 호출은 막힌다
@Component
public class AnonymousAccessGuard extends SimplePerformantInstrumentation {

	// 비로그인 허용 루트 필드. 늘릴 때는 사용자 승인 필요
	private static final Set<String> PUBLIC_ROOT_FIELDS = Set.of("exchangeAuthCode");

	@Override
	public DataFetcher<?> instrumentDataFetcher(DataFetcher<?> dataFetcher,
			InstrumentationFieldFetchParameters parameters, InstrumentationState state) {
		ExecutionStepInfo step = parameters.getExecutionStepInfo();
		boolean rootField = step.getPath().getLevel() == 1;
		if (!rootField || PUBLIC_ROOT_FIELDS.contains(step.getFieldDefinition().getName())) {
			return dataFetcher;
		}
		return environment -> {
			Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
			if (authentication == null || authentication instanceof AnonymousAuthenticationToken
					|| !authentication.isAuthenticated()) {
				throw new AuthenticationCredentialsNotFoundException("로그인 필요");
			}
			return dataFetcher.get(environment);
		};
	}

}
