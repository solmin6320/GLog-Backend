package com.jandilog.common.exception;

// 업무 규칙 위반을 ErrorCode로 던진다. 메시지는 ErrorCode의 사용자 문구만 쓴다
public class ApiException extends RuntimeException {

	private final ErrorCode errorCode;

	public ApiException(ErrorCode errorCode) {
		super(errorCode.name(), null, false, false);
		this.errorCode = errorCode;
	}

	public ErrorCode getErrorCode() {
		return errorCode;
	}

}
