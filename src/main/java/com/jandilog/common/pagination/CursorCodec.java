package com.jandilog.common.pagination;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;

// 커서 기반 페이지네이션의 불투명 커서 (Q-06). 값 하나(id 등)를 Base64url 문자열로 감싼다
public final class CursorCodec {

	public static final int PAGE_SIZE = 20;

	private CursorCodec() {
	}

	public static String encode(String value) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}

	public static String encode(long value) {
		return encode(Long.toString(value));
	}

	// 커서가 없으면 null, 형식이 틀리면 잘못된 입력
	public static String decode(String cursor) {
		if (cursor == null || cursor.isEmpty()) {
			return null;
		}
		try {
			return new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
		} catch (IllegalArgumentException e) {
			throw new ApiException(ErrorCode.INVALID_INPUT);
		}
	}

	// 숫자 커서. 없으면 defaultValue
	public static long decodeLong(String cursor, long defaultValue) {
		String raw = decode(cursor);
		if (raw == null) {
			return defaultValue;
		}
		try {
			return Long.parseLong(raw);
		} catch (NumberFormatException e) {
			throw new ApiException(ErrorCode.INVALID_INPUT);
		}
	}

}
