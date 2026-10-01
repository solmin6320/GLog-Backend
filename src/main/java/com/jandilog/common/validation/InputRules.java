package com.jandilog.common.validation;

import java.time.DateTimeException;
import java.time.LocalDate;

import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.judgment.domain.JudgmentWeek;

// 면제·정정·관리자 입력의 공통 검증. 규칙을 어기면 ApiException
public final class InputRules {

	private InputRules() {
	}

	// GraphQL ID 문자열을 양수 id로. 숫자가 아니면 없는 대상과 같게 본다 (E-53)
	public static long id(String raw) {
		try {
			long id = Long.parseLong(raw == null ? "" : raw.strip());
			if (id > 0) {
				return id;
			}
		} catch (NumberFormatException e) {
			// 아래에서 같은 오류로 처리
		}
		throw new ApiException(ErrorCode.NOT_FOUND);
	}

	// 주차는 월요일 yyyy-MM-dd (기능명세서 1-0, 주 단위로만 등록)
	public static LocalDate weekStart(String raw) {
		try {
			LocalDate date = LocalDate.parse(raw == null ? "" : raw.strip());
			if (JudgmentWeek.isMonday(date)) {
				return date;
			}
		} catch (DateTimeException e) {
			// 아래에서 같은 오류로 처리
		}
		throw new ApiException(ErrorCode.INVALID_INPUT);
	}

	// 앞뒤 공백을 뺀 사유. 비었으면 requiredError, maxLength(글자 수)를 넘으면 잘못된 입력
	public static String reason(String raw, int maxLength, ErrorCode requiredError) {
		String reason = raw == null ? "" : raw.strip();
		if (reason.isEmpty()) {
			throw new ApiException(requiredError);
		}
		if (reason.codePointCount(0, reason.length()) > maxLength) {
			throw new ApiException(ErrorCode.INVALID_INPUT);
		}
		return reason;
	}

}
