package com.jandilog.team.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.common.pagination.CursorCodec;
import com.jandilog.common.pagination.PageSlice;

// 팀원·초대 목록의 커서 기반 20개 페이징 (Q-06). 커서는 마지막 행의 (시각, id)를 Base64url로 감싼 불투명 문자열이다
record TeamCursor(LocalDateTime time, long id) {

	static final int PAGE_SIZE = CursorCodec.PAGE_SIZE;

	// 다음 페이지가 있는지 알기 위해 한 건 더 읽는다
	static Pageable fetchSize() {
		return PageRequest.of(0, PAGE_SIZE + 1);
	}

	// 한 건 더 읽은 목록을 20개로 자르고, 한 건 더 읽혔을 때만 마지막 행에서 다음 커서를 만든다
	static <R> PageSlice<R> slice(List<R> rows, Function<R, TeamCursor> cursorOf) {
		return PageSlice.of(rows, PAGE_SIZE, row -> cursorOf.apply(row).encode());
	}

	// 비었으면 첫 페이지라 null, 읽을 수 없는 값이면 입력 오류
	static TeamCursor parse(String cursor) {
		String raw = CursorCodec.decode(cursor);
		if (raw == null) {
			return null;
		}
		String[] parts = raw.split("\\|", -1);
		try {
			if (parts.length == 2) {
				long id = Long.parseLong(parts[1]);
				if (id > 0) {
					return new TeamCursor(LocalDateTime.parse(parts[0], DateTimeFormatter.ISO_LOCAL_DATE_TIME), id);
				}
			}
		} catch (NumberFormatException | DateTimeParseException e) {
			// 아래에서 같은 오류로 처리
		}
		throw new ApiException(ErrorCode.INVALID_INPUT);
	}

	String encode() {
		return CursorCodec.encode(DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(time) + "|" + id);
	}

}
