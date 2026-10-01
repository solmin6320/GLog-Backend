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
	// EX-BD04-01
	TITLE_REQUIRED(ErrorType.BAD_REQUEST, 400, "제목을 입력해 주세요."),
	// E-61, EX-BD01-01
	SEARCH_TOO_SHORT(ErrorType.BAD_REQUEST, 400, "2자 이상 입력해 주세요."),
	// E-63
	SEARCH_TOO_LONG(ErrorType.BAD_REQUEST, 400, "검색어는 50자까지 쓸 수 있어요."),
	// EX-AD01-02: 잔디 인정 규칙 공지는 서버가 삭제를 거부
	SYSTEM_NOTICE_UNDELETABLE(ErrorType.FORBIDDEN, 403, "이 공지는 판정 기준 안내라 삭제할 수 없어요."),
	// E-47
	EXEMPTION_REQUEST_DUPLICATE(ErrorType.BAD_REQUEST, 409, "이미 요청한 주간이에요."),
	// E-48
	REJECT_REASON_REQUIRED(ErrorType.BAD_REQUEST, 400, "거절 사유를 입력해 주세요."),
	// 기획서에 없는 문구(임시): 사유 입력 누락 (면제 기간·면제 요청·판정 정정)
	REASON_REQUIRED(ErrorType.BAD_REQUEST, 400, "사유를 입력해 주세요."),
	// 기획서에 없는 문구(임시): 같은 주에 면제 기간이 이미 있음
	EXEMPTION_PERIOD_DUPLICATE(ErrorType.BAD_REQUEST, 409, "이미 면제 기간으로 등록된 주예요."),
	// 기획서에 없는 문구(임시): 보류·정정 불가 상태의 판정
	JUDGMENT_NOT_CORRECTABLE(ErrorType.BAD_REQUEST, 400, "이 판정은 그 결과로 정정할 수 없어요."),
	// 기획서에 없는 문구(임시): 미리보기 뒤 다른 관리자가 먼저 바꿈 (화면설계서 AD-01 ⑩ 반영 직전 재검증)
	JUDGMENT_CHANGED(ErrorType.BAD_REQUEST, 409, "그 사이 판정이 바뀌었어요. 목록을 새로고침해 주세요."),
	// 기획서에 없는 문구(임시): 이미 다른 관리자가 처리한 요청·경고
	ALREADY_HANDLED(ErrorType.BAD_REQUEST, 409, "이미 처리된 항목이에요. 목록을 새로고침해 주세요."),
	// 기획서에 없는 문구(임시): 벌칙 대상이 아닌 회원에게 이행 체크
	NOT_PENALTY_TARGET(ErrorType.BAD_REQUEST, 409, "벌칙 대상이 아니에요. 목록을 새로고침해 주세요."),
	// E-55
	PROFILE_IMAGE_UPLOAD_FAILED(ErrorType.INTERNAL_ERROR, 502, "사진을 올리지 못했어요. 다시 시도해 주세요."),
	// E-56
	PROFILE_IMAGE_TOO_LARGE(ErrorType.BAD_REQUEST, 413, "5MB 이하 이미지만 올릴 수 있어요."),
	// E-56
	PROFILE_IMAGE_TYPE_NOT_ALLOWED(ErrorType.BAD_REQUEST, 415, "jpg, png, webp만 올릴 수 있어요."),
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
