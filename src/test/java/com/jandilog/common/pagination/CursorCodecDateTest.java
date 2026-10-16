package com.jandilog.common.pagination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;

// 날짜 커서(주차 기반 이력 목록): 없으면 기본값, 날짜를 감싼 값은 그 날짜, 그 밖은 잘못된 입력
class CursorCodecDateTest {

	private static final LocalDate START = LocalDate.of(9999, 12, 31);

	@Test
	void 커서가_없으면_기본값이다() {
		assertThat(CursorCodec.decodeDate(null, START)).isEqualTo(START);
		assertThat(CursorCodec.decodeDate("", START)).isEqualTo(START);
	}

	@Test
	void 날짜를_감싼_커서는_그_날짜로_돌아온다() {
		LocalDate week = LocalDate.of(2026, 10, 12);

		assertThat(CursorCodec.decodeDate(CursorCodec.encode(week.toString()), START)).isEqualTo(week);
	}

	@ParameterizedTest
	@ValueSource(strings = { "%%%", "YWJj", "MjAyNi0xMy0wMQ", "MjAyNi0xMC0xMlQwMDowMA" })
	void 날짜가_아니거나_Base64가_아니면_잘못된_입력이다(String cursor) {
		// "abc", "2026-13-01", "2026-10-12T00:00"
		assertThatThrownBy(() -> CursorCodec.decodeDate(cursor, START)).isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
	}

}
