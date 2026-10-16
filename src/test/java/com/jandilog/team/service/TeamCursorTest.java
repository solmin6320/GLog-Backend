package com.jandilog.team.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.common.pagination.PageSlice;

// 팀원·초대 목록 커서: 20개 경계와 커서 해석 (기능명세서 10장 페이지네이션, Q-06)
class TeamCursorTest {

	private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 8, 9, 30, 0);

	private static List<Integer> rows(int count) {
		return IntStream.rangeClosed(1, count).boxed().toList();
	}

	private static TeamCursor cursorOf(int row) {
		return new TeamCursor(AT.plusSeconds(row), row);
	}

	// ----- 20개 경계 -----

	@Test
	void 한_페이지는_20개이고_다음_페이지_판단을_위해_21개를_읽는다() {
		assertThat(TeamCursor.PAGE_SIZE).isEqualTo(20);
		assertThat(TeamCursor.fetchSize().getPageNumber()).isZero();
		assertThat(TeamCursor.fetchSize().getPageSize()).isEqualTo(21);
	}

	@Test
	void 빈_목록은_항목도_다음_커서도_없다() {
		PageSlice<Integer> page = TeamCursor.slice(rows(0), TeamCursorTest::cursorOf);

		assertThat(page.rows()).isEmpty();
		assertThat(page.nextCursor()).isNull();
	}

	@Test
	void 정확히_20개면_다음_커서가_없다() {
		PageSlice<Integer> page = TeamCursor.slice(rows(20), TeamCursorTest::cursorOf);

		assertThat(page.rows()).hasSize(20);
		assertThat(page.nextCursor()).isNull();
	}

	@Test
	void 한_건_더_읽혔으면_20개만_남기고_20번째_행으로_다음_커서를_만든다() {
		PageSlice<Integer> page = TeamCursor.slice(rows(21), TeamCursorTest::cursorOf);

		assertThat(page.rows()).containsExactlyElementsOf(rows(20));
		String next = page.nextCursor();
		assertThat(next).isNotNull();
		assertThat(TeamCursor.parse(next)).isEqualTo(cursorOf(20));
	}

	@Test
	void 한_건_읽힌_목록은_그대로이고_다음_커서가_없다() {
		PageSlice<Integer> page = TeamCursor.slice(rows(1), TeamCursorTest::cursorOf);

		assertThat(page.rows()).containsExactly(1);
		assertThat(page.nextCursor()).isNull();
	}

	// ----- 커서 해석 -----

	@Test
	void 커서는_시각과_id를_담아_그대로_되돌아온다() {
		TeamCursor cursor = new TeamCursor(LocalDateTime.of(2026, 1, 2, 3, 4, 0), 4_000_000_000L);

		assertThat(TeamCursor.parse(cursor.encode())).isEqualTo(cursor);
	}

	@Test
	void 커서_문자열은_시각과_id를_Base64url로_감싼_형식_그대로다() {
		TeamCursor cursor = new TeamCursor(LocalDateTime.of(2026, 10, 8, 9, 30, 0), 7L);

		assertThat(cursor.encode()).isEqualTo(wrap("2026-10-08T09:30:00|7"));
	}

	@Test
	void 비어_있으면_첫_페이지라서_null이다() {
		assertThat(TeamCursor.parse(null)).isNull();
		assertThat(TeamCursor.parse("")).isNull();
	}

	@ParameterizedTest
	@ValueSource(strings = {"not-base64!!", "abc", "MQ", "MnwzfDQ"})
	void 읽을_수_없는_커서는_입력_오류다(String bad) {
		assertInvalid(bad);
	}

	@Test
	void 형식이_틀린_내용을_감싼_커서도_입력_오류다() {
		assertInvalid(wrap("2026-10-08T09:30:00|0"));
		assertInvalid(wrap("2026-10-08T09:30:00|-5"));
		assertInvalid(wrap("2026-10-08T09:30:00|abc"));
		assertInvalid(wrap("어제|7"));
		assertInvalid(wrap("2026-10-08T09:30:00|7|1"));
		assertInvalid(wrap("7"));
	}

	private static String wrap(String raw) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	private static void assertInvalid(String cursor) {
		assertThatThrownBy(() -> TeamCursor.parse(cursor)).isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
	}

}
