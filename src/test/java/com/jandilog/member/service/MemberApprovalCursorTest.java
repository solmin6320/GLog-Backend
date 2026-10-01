package com.jandilog.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;

// 가입 승인 목록의 커서: 마지막 행 id를 감싼 불투명 문자열 (E-57, Q-06 커서 기반 20개)
class MemberApprovalCursorTest {

	private static String encodeRaw(String text) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
	}

	@Test
	void 한_페이지는_20개다() {
		assertThat(MemberApprovalService.PAGE_SIZE).isEqualTo(20);
	}

	@ParameterizedTest
	@ValueSource(longs = {0, 1, 20, 21, 12345, Long.MAX_VALUE})
	void 인코딩한_커서를_디코딩하면_원래_id가_나온다(long id) {
		assertThat(MemberApprovalService.decodeCursor(MemberApprovalService.encodeCursor(id))).isEqualTo(id);
	}

	@Test
	void 커서는_URL에_그대로_실을_수_있는_문자만_쓴다() {
		for (long id : new long[] {1, 62, 63, 999_999, Long.MAX_VALUE}) {
			assertThat(MemberApprovalService.encodeCursor(id)).matches("^[A-Za-z0-9_-]+$");
		}
	}

	@Test
	void 커서가_없거나_비어_있으면_처음부터다() {
		assertThat(MemberApprovalService.decodeCursor(null)).isZero();
		assertThat(MemberApprovalService.decodeCursor("")).isZero();
	}

	@ParameterizedTest
	@ValueSource(strings = {"!!!", "a+b/", "abc", "한글", "====", "not a cursor", "1", "12345"})
	void base64url이_아니거나_숫자가_아닌_커서는_INVALID_INPUT이다(String cursor) {
		assertInvalid(cursor);
	}

	@Test
	void 음수나_숫자가_아닌_내용을_감싼_커서는_INVALID_INPUT이다() {
		assertInvalid(encodeRaw("-1"));
		assertInvalid(encodeRaw("abc"));
		assertInvalid(encodeRaw("1.5"));
		assertInvalid(encodeRaw("1 2"));
		assertInvalid(encodeRaw(" 5"));
		assertInvalid(encodeRaw("99999999999999999999"));
	}

	private void assertInvalid(String cursor) {
		assertThatThrownBy(() -> MemberApprovalService.decodeCursor(cursor))
				.isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).getErrorCode())
				.isEqualTo(ErrorCode.INVALID_INPUT);
	}

}
