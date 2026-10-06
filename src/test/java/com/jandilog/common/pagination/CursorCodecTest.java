package com.jandilog.common.pagination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;

// 커서 기반 페이지네이션의 커서 규칙 (Q-06, E-57): 페이지 크기 20, 불투명 문자열, 잘못된 커서는 잘못된 입력
class CursorCodecTest {

	@Test
	@DisplayName("페이지 크기는 20이다")
	void pageSizeIsTwenty() {
		assertThat(CursorCodec.PAGE_SIZE).isEqualTo(20);
	}

	@Test
	@DisplayName("커서가 없거나 비었으면 첫 페이지라 null로 읽는다")
	void absentCursorMeansFirstPage() {
		assertThat(CursorCodec.decode(null)).isNull();
		assertThat(CursorCodec.decode("")).isNull();
		assertThat(CursorCodec.decodeLong(null, 99L)).isEqualTo(99L);
		assertThat(CursorCodec.decodeLong("", 99L)).isEqualTo(99L);
	}

	@Test
	@DisplayName("문자열·숫자 커서는 감쌌다 풀면 같은 값이고 URL에 그대로 쓸 수 있는 문자만 쓴다")
	void roundTripUsesUrlSafeCharacters() {
		String cursor = CursorCodec.encode("2044-03-07");
		assertThat(CursorCodec.decode(cursor)).isEqualTo("2044-03-07");
		assertThat(cursor).matches("[A-Za-z0-9_-]+");

		assertThat(CursorCodec.decodeLong(CursorCodec.encode(123456789012L), 0L)).isEqualTo(123456789012L);
		// 한글처럼 여러 바이트인 값도 깨지지 않는다
		assertThat(CursorCodec.decode(CursorCodec.encode("잔디로그"))).isEqualTo("잔디로그");
	}

	@Test
	@DisplayName("Base64가 아닌 커서는 INVALID_INPUT이다")
	void malformedCursorIsInvalidInput() {
		for (String bad : new String[] {"!!!", "%%%%", "a b", "a+b/c="}) {
			assertThatThrownBy(() -> CursorCodec.decode(bad)).as("커서 " + bad).isInstanceOf(ApiException.class)
					.extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
		}
	}

	@Test
	@DisplayName("숫자가 아닌 값을 감싼 커서는 숫자 커서로 읽을 때 INVALID_INPUT이다")
	void nonNumericCursorIsInvalidForLong() {
		assertThatThrownBy(() -> CursorCodec.decodeLong(CursorCodec.encode("abc"), 0L))
				.isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
	}

}
