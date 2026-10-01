package com.jandilog.judgment.service;

import com.jandilog.judgment.domain.HoldReason;

// 잔디 조회 실패. reason이 곧 판정 보류 사유(hold_reason)다. 메시지에 토큰·응답 본문을 싣지 않는다
public class GrassFetchException extends RuntimeException {

	private final HoldReason reason;

	public GrassFetchException(HoldReason reason, String message) {
		super(message);
		this.reason = reason;
	}

	public GrassFetchException(HoldReason reason, String message, Throwable cause) {
		super(message, cause);
		this.reason = reason;
	}

	public HoldReason getReason() {
		return reason;
	}

	// 재시도로 풀리는 실패인가 (IDENTITY_MISMATCH는 본인 재로그인이 필요해 재시도해도 소용없다)
	public boolean isRetryable() {
		return reason == HoldReason.API_ERROR;
	}

}
