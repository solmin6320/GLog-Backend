package com.jandilog.common.exception;

import org.springframework.graphql.execution.ErrorType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;

import com.jandilog.common.security.Authorities;

// 사용자에게 보이는 문구는 화면설계서 5-4의 해요체 그대로. 내부 정보는 싣지 않는다 (E-52)
public enum ErrorCode {

	// E-03
	UNAUTHENTICATED(ErrorType.UNAUTHORIZED, 401, "로그인이 만료됐어요. 다시 로그인해 주세요."),
	// E-01
	ACCOUNT_PENDING(ErrorType.FORBIDDEN, 403, "가입 승인을 기다리는 중이에요. 승인되면 바로 이용할 수 있어요."),
	// E-02
	ACCOUNT_REJECTED(ErrorType.FORBIDDEN, 403, "가입이 승인되지 않았어요. 동아리 운영진에게 문의해 주세요."),
	// E-09
	FORBIDDEN(ErrorType.FORBIDDEN, 403, "이 페이지에 들어올 권한이 없어요."),
	// E-05, E-06
	AUTH_CODE_INVALID(ErrorType.BAD_REQUEST, 400, "로그인 정보가 만료됐어요. 처음부터 다시 해주세요."),
	// E-53
	NOT_FOUND(ErrorType.NOT_FOUND, 404, "찾는 내용이 없어요. 삭제됐을 수 있어요."),
	// 기획서에 없는 문구(임시): 이미 다른 관리자가 처리한 회원
	CONFLICT(ErrorType.BAD_REQUEST, 409, "이미 처리된 회원이에요. 목록을 새로고침해 주세요."),
	// 기획서에 없는 문구(임시): 잘못된 입력
	INVALID_INPUT(ErrorType.BAD_REQUEST, 400, "요청 내용을 확인해 주세요."),
	// E-52
	INTERNAL_ERROR(ErrorType.INTERNAL_ERROR, 500, "잠시 문제가 생겼어요. 조금 뒤에 다시 시도해 주세요.");

	private final ErrorType graphQlType;
	private final int httpStatus;
	private final String message;

	ErrorCode(ErrorType graphQlType, int httpStatus, String message) {
		this.graphQlType = graphQlType;
		this.httpStatus = httpStatus;
		this.message = message;
	}

	public ErrorType graphQlType() {
		return graphQlType;
	}

	public int httpStatus() {
		return httpStatus;
	}

	public String message() {
		return message;
	}

	// 권한 거부를 계정 상태별로 나눈다: 비로그인 / 승인 대기(E-01) / 거절(E-02) / 그 외(E-09)
	public static ErrorCode forAccessDenied(Authentication authentication) {
		if (authentication == null || authentication instanceof AnonymousAuthenticationToken
				|| !authentication.isAuthenticated()) {
			return UNAUTHENTICATED;
		}
		if (hasAuthority(authentication, Authorities.PENDING)) {
			return ACCOUNT_PENDING;
		}
		if (hasAuthority(authentication, Authorities.REJECTED)) {
			return ACCOUNT_REJECTED;
		}
		return FORBIDDEN;
	}

	private static boolean hasAuthority(Authentication authentication, String authority) {
		return authentication.getAuthorities().stream().anyMatch(a -> authority.equals(a.getAuthority()));
	}

}
