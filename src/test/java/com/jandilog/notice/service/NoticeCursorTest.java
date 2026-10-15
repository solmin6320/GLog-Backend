package com.jandilog.notice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.notice.domain.Notice;
import com.jandilog.notice.service.NoticeService.Cursor;

// 공지 목록 커서: (고정 여부, 작성 시각, id)를 감싼 불투명 문자열 (E-57, Q-06)
class NoticeCursorTest {

	private static String encodeRaw(String text) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
	}

	private static void assertInvalid(String cursor) {
		assertThatThrownBy(() -> Cursor.decode(cursor)).isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
	}

	@Test
	void 고정_공지_커서를_인코딩한_뒤_디코딩하면_같은_값이다() {
		Cursor cursor = new Cursor(true, LocalDateTime.of(2026, 9, 3, 10, 30, 15), 77L);

		assertThat(Cursor.decode(cursor.encode())).isEqualTo(cursor);
	}

	@Test
	void 일반_공지_커서도_같은_값으로_돌아온다() {
		Cursor cursor = new Cursor(false, LocalDateTime.of(2026, 12, 31, 23, 59, 59), Long.MAX_VALUE);

		assertThat(Cursor.decode(cursor.encode())).isEqualTo(cursor);
	}

	@Test
	void 커서는_URL에_그대로_실을_수_있는_문자만_쓴다() {
		for (long id : new long[] {1, 62, 63, 999_999, Long.MAX_VALUE}) {
			assertThat(new Cursor(id % 2 == 0, LocalDateTime.of(2026, 1, 1, 0, 0, 0), id).encode())
					.matches("^[A-Za-z0-9_-]+$");
		}
	}

	@Test
	void 커서는_고정_여부_작성_시각_id를_파이프로_이은_값을_감싼다() {
		Cursor cursor = new Cursor(true, LocalDateTime.of(2026, 9, 3, 10, 30, 15), 77L);

		assertThat(cursor.encode()).isEqualTo(encodeRaw("P|2026-09-03T10:30:15|77"));
		assertThat(new Cursor(false, LocalDateTime.of(2026, 9, 3, 10, 30, 15), 77L).encode())
				.isEqualTo(encodeRaw("N|2026-09-03T10:30:15|77"));
	}

	@Test
	void 공지에서_만든_커서는_그_공지의_위치를_가리킨다() {
		Notice notice = Notice.create("제목", "내용", true, 1L, LocalDateTime.of(2026, 9, 3, 10, 30, 15));
		ReflectionTestUtils.setField(notice, "id", 77L);

		assertThat(Cursor.of(notice)).isEqualTo(new Cursor(true, LocalDateTime.of(2026, 9, 3, 10, 30, 15), 77L));
	}

	@ParameterizedTest
	@ValueSource(strings = {"!!!", "abc", "한글", "====", "a+b/", "not a cursor", "12345"})
	void base64url이_아니거나_형식이_다른_커서는_INVALID_INPUT이다(String cursor) {
		assertInvalid(cursor);
	}

	@Test
	void 구분자_개수나_값이_틀린_커서는_INVALID_INPUT이다() {
		assertInvalid(encodeRaw(""));
		assertInvalid(encodeRaw("P|2026-09-03T10:30:15"));
		assertInvalid(encodeRaw("P|2026-09-03T10:30:15|77|1"));
		assertInvalid(encodeRaw("X|2026-09-03T10:30:15|77"));
		assertInvalid(encodeRaw("p|2026-09-03T10:30:15|77"));
		assertInvalid(encodeRaw("P|not-a-date|77"));
		assertInvalid(encodeRaw("P|2026-09-03|77"));
		assertInvalid(encodeRaw("P|2026-09-03T10:30:15|abc"));
		assertInvalid(encodeRaw("P|2026-09-03T10:30:15|1.5"));
		assertInvalid(encodeRaw("P|2026-09-03T10:30:15|"));
		assertInvalid(encodeRaw("P|2026-09-03T10:30:15|99999999999999999999"));
	}

	@Test
	void id가_0이나_음수인_커서는_INVALID_INPUT이다() {
		assertInvalid(encodeRaw("P|2026-09-03T10:30:15|0"));
		assertInvalid(encodeRaw("N|2026-09-03T10:30:15|-5"));
	}

}
