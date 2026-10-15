package com.jandilog.judgment.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.jandilog.judgment.domain.HoldReason;

// 잔디 조회 실패: reason이 곧 hold_reason이고, 재시도로 풀리는 것은 API_ERROR뿐이다 (Q-09)
class GrassFetchExceptionTest {

	@Test
	void API_ERROR는_재시도_대상이다() {
		GrassFetchException e = new GrassFetchException(HoldReason.API_ERROR, "오류");

		assertThat(e.getReason()).isEqualTo(HoldReason.API_ERROR);
		assertThat(e.isRetryable()).isTrue();
		assertThat(e.getMessage()).isEqualTo("오류");
	}

	@Test
	void IDENTITY_MISMATCH는_재시도해도_풀리지_않는다() {
		GrassFetchException e = new GrassFetchException(HoldReason.IDENTITY_MISMATCH, "없음");

		assertThat(e.getReason()).isEqualTo(HoldReason.IDENTITY_MISMATCH);
		assertThat(e.isRetryable()).isFalse();
	}

	@Test
	void 원인_예외를_함께_보관한다() {
		RuntimeException cause = new RuntimeException("원인");

		GrassFetchException e = new GrassFetchException(HoldReason.API_ERROR, "오류", cause);

		assertThat(e.getCause()).isSameAs(cause);
	}

}
