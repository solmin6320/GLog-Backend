package com.jandilog.team.service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;

// 팀원·초대 목록의 커서 기반 20개 페이징 (Q-06). 커서는 마지막 행의 (시각, id)를 Base64url로 감싼 불투명 문자열이다
record TeamCursor(LocalDateTime time, long id) {

	static final int PAGE_SIZE = 20;

	// 다음 페이지가 있는지 알기 위해 한 건 더 읽는다
	static Pageable fetchSize() {
		return PageRequest.of(0, PAGE_SIZE + 1);
	}

	static <R> List<R> trim(List<R> rows) {
		return rows.size() > PAGE_SIZE ? rows.subList(0, PAGE_SIZE) : rows;
	}

	// 한 건 더 읽혔을 때만 마지막 행에서 다음 커서를 만든다. 없으면 null (마지막 페이지)
	static <R> String nextOf(List<R> rows, Function<R, TeamCursor> cursorOf) {
		return rows.size() > PAGE_SIZE ? cursorOf.apply(rows.get(PAGE_SIZE - 1)).encode() : null;
	}

	// 비었으면 첫 페이지라 null, 읽을 수 없는 값이면 입력 오류
	static TeamCursor parse(String cursor) {
		if (cursor == null || cursor.isEmpty()) {
			return null;
		}
		try {
			String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
			String[] parts = raw.split("\\|", -1);
			if (parts.length == 2) {
				long id = Long.parseLong(parts[1]);
				if (id > 0) {
					return new TeamCursor(LocalDateTime.parse(parts[0], DateTimeFormatter.ISO_LOCAL_DATE_TIME), id);
				}
			}
		} catch (IllegalArgumentException | DateTimeParseException e) {
			// 아래에서 같은 오류로 처리
		}
		throw new ApiException(ErrorCode.INVALID_INPUT);
	}

	String encode() {
		String raw = DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(time) + "|" + id;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

}
