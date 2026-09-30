package com.jandilog.common.exception;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.validation.BindException;

import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;

// 리졸버 예외를 사용자 문구 + extensions.code로 바꾼다. 500은 내부 정보 없이 E-52 문구만 (E-52)
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GraphQlExceptionResolver extends DataFetcherExceptionResolverAdapter {

	private static final Logger log = LoggerFactory.getLogger(GraphQlExceptionResolver.class);

	@Override
	protected GraphQLError resolveToSingleError(Throwable ex, DataFetchingEnvironment env) {
		ErrorCode code = classify(ex);
		if (code == ErrorCode.INTERNAL_ERROR) {
			log.error("GraphQL 처리 중 예상하지 못한 오류 (field={})", env.getField().getName(), ex);
		}
		return GraphqlErrorBuilder.newError(env)
				.errorType(code.graphQlType())
				.message(code.message())
				.extensions(Map.of("code", code.name()))
				.build();
	}

	private ErrorCode classify(Throwable ex) {
		if (ex instanceof ApiException apiException) {
			return apiException.getErrorCode();
		}
		if (ex instanceof AuthenticationCredentialsNotFoundException) {
			return ErrorCode.UNAUTHENTICATED;
		}
		if (ex instanceof AccessDeniedException) {
			return ErrorCode.forAccessDenied(SecurityContextHolder.getContext().getAuthentication());
		}
		if (ex instanceof AuthenticationException) {
			return ErrorCode.UNAUTHENTICATED;
		}
		if (ex instanceof BindException) {
			return ErrorCode.INVALID_INPUT;
		}
		return ErrorCode.INTERNAL_ERROR;
	}

}
