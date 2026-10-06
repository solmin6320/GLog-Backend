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
	// 기획서에 없는 문구(임시): 쿼리 깊이·복잡도 한도 초과
	QUERY_TOO_COMPLEX(ErrorType.BAD_REQUEST, 400, "요청이 너무 복잡해요. 조건을 줄여서 다시 시도해 주세요."),
	// E-31, EX-BD03-02, EX-BD04-02: 삭제된 글은 404 화면이 아니라 삭제 안내
	POST_DELETED(ErrorType.NOT_FOUND, 410, "삭제된 글이에요."),
	// E-29
	INVALID_COMMIT_URL(ErrorType.BAD_REQUEST, 400, "GitHub 커밋 주소 형식이 아니에요."),
	// E-30
	TAG_LIMIT_EXCEEDED(ErrorType.BAD_REQUEST, 400, "태그는 5개까지 달 수 있어요."),
	// 기획서에 없는 문구(임시): 태그 한 개가 20자 초과 (E-30은 개수 문구만 있음)
	TAG_TOO_LONG(ErrorType.BAD_REQUEST, 400, "태그는 20자까지 쓸 수 있어요."),
	// 필수 항목 5000자 초과 (사용자 확정 2026-10-06). 문구는 5-2-8 입력 검증 표준형 "{항목}은 {N}자까지 쓸 수 있어요."
	POST_PROBLEM_TOO_LONG(ErrorType.BAD_REQUEST, 400, "문제는 5000자까지 쓸 수 있어요."),
	POST_CAUSE_TOO_LONG(ErrorType.BAD_REQUEST, 400, "원인은 5000자까지 쓸 수 있어요."),
	POST_SOLUTION_TOO_LONG(ErrorType.BAD_REQUEST, 400, "해결은 5000자까지 쓸 수 있어요."),
	POST_DID_TOO_LONG(ErrorType.BAD_REQUEST, 400, "한 일은 5000자까지 쓸 수 있어요."),
	POST_LEARNED_TOO_LONG(ErrorType.BAD_REQUEST, 400, "배운 점은 5000자까지 쓸 수 있어요."),
	// 관련 커밋 링크 5개 초과 (사용자 확정 2026-10-06). E-30의 태그 개수 문구와 같은 꼴
	POST_COMMIT_URLS_LIMIT_EXCEEDED(ErrorType.BAD_REQUEST, 400, "관련 커밋 링크는 5개까지 달 수 있어요."),
	// EX-BD04-01
	TITLE_REQUIRED(ErrorType.BAD_REQUEST, 400, "제목을 입력해 주세요."),
	// E-61, EX-BD01-01
	SEARCH_TOO_SHORT(ErrorType.BAD_REQUEST, 400, "2자 이상 입력해 주세요."),
	// E-63
	SEARCH_TOO_LONG(ErrorType.BAD_REQUEST, 400, "검색어는 50자까지 쓸 수 있어요."),
	// EX-AD01-02: 잔디 인정 규칙 공지는 서버가 삭제를 거부
	SYSTEM_NOTICE_UNDELETABLE(ErrorType.FORBIDDEN, 403, "이 공지는 판정 기준 안내라 삭제할 수 없어요."),
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
