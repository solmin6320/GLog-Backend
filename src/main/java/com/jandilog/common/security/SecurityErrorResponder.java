package com.jandilog.common.security;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jandilog.common.exception.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// 필터 단계(GraphQL 실행 전)의 인증·권한 오류를 GraphQL 오류 모양의 JSON으로 내린다.
// 토큰 오류 사유(만료·위조 등)는 구분해 알려주지 않는다
@Component
public class SecurityErrorResponder implements AuthenticationEntryPoint, AccessDeniedHandler {

	private final ObjectMapper objectMapper;

	public SecurityErrorResponder(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException exception) throws IOException {
		write(response, ErrorCode.UNAUTHENTICATED);
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response,
			AccessDeniedException exception) throws IOException {
		write(response, ErrorCode.forAccessDenied(SecurityContextHolder.getContext().getAuthentication()));
	}

	private void write(HttpServletResponse response, ErrorCode code) throws IOException {
		response.setStatus(code.httpStatus());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
		if (code == ErrorCode.UNAUTHENTICATED) {
			// 오류 사유 없이 스킴만 알린다
			response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
		}
		Map<String, Object> error = Map.of(
				"message", code.message(),
				"extensions", Map.of("classification", code.graphQlType().name(), "code", code.name()));
		objectMapper.writeValue(response.getOutputStream(), Map.of("errors", List.of(error)));
	}

}
