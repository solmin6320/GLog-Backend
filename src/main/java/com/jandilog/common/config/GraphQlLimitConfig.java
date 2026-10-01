package com.jandilog.common.config;

import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.jandilog.common.exception.ErrorCode;

import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.analysis.MaxQueryComplexityInstrumentation;
import graphql.analysis.MaxQueryDepthInstrumentation;
import graphql.execution.AbortExecutionException;

// 깊이·복잡도 한도. 초과하면 리졸버를 실행하지 않고 내부 수치 없이 해요체 오류만 내린다.
// 복잡도는 필드 하나당 1이라 별칭으로 같은 필드를 반복해도 그만큼 쌓인다
@Configuration
public class GraphQlLimitConfig {

	static final int MAX_DEPTH = 10;
	static final int MAX_COMPLEXITY = 50;

	@Bean
	MaxQueryDepthInstrumentation maxQueryDepthInstrumentation() {
		return new MaxQueryDepthInstrumentation(MAX_DEPTH) {
			@Override
			protected AbortExecutionException mkAbortException(int depth, int maxDepth) {
				return limitExceeded();
			}
		};
	}

	@Bean
	MaxQueryComplexityInstrumentation maxQueryComplexityInstrumentation() {
		return new MaxQueryComplexityInstrumentation(MAX_COMPLEXITY) {
			@Override
			protected AbortExecutionException mkAbortException(int totalComplexity, int maxComplexity) {
				return limitExceeded();
			}
		};
	}

	private static AbortExecutionException limitExceeded() {
		ErrorCode code = ErrorCode.QUERY_TOO_COMPLEX;
		GraphQLError error = GraphqlErrorBuilder.newError()
				.errorType(code.graphQlType())
				.message(code.message())
				.extensions(Map.of("code", code.name()))
				.build();
		return new AbortExecutionException(List.of(error));
	}

}
