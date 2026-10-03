package com.jandilog.common.validation;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Locale;

import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.judgment.domain.JudgmentWeek;

// 면제·정정·관리자 입력의 공통 검증. 규칙을 어기면 ApiException
public final class InputRules {

	static final int KEYWORD_MAX_LENGTH = 50;

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

	// 관리자 검색어(50자 이하). 비었으면 null, 길면 잘못된 입력
	public static String keyword(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String keyword = raw.strip();
		if (keyword.codePointCount(0, keyword.length()) > KEYWORD_MAX_LENGTH) {
			throw new ApiException(ErrorCode.INVALID_INPUT);
		}
		return keyword;
	}

	// keyword(null이면 전체)를 부분 일치 LIKE 패턴으로. 소문자로 맞추고 와일드카드는 글자 그대로 찾는다 (이스케이프 '!')
	public static String likePattern(String keyword) {
		if (keyword == null) {
			return "%";
		}
		String escaped = keyword.toLowerCase(Locale.ROOT)
				.replace("!", "!!")
				.replace("%", "!%")
				.replace("_", "!_");
		return "%" + escaped + "%";
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
