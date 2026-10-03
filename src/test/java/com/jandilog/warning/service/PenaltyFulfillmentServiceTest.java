package com.jandilog.warning.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.jandilog.admin.domain.AdminActionLog;
import com.jandilog.admin.domain.AdminActionType;
import com.jandilog.admin.repository.AdminActionLogRepository;
import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.warning.domain.PenaltyFulfillment;
import com.jandilog.warning.domain.WarningRecalcResult;
import com.jandilog.warning.repository.PenaltyFulfillmentRepository;

// 벌칙 이행 체크 (기능명세서 6장, E-58): 대상 여부를 락 안에서 다시 계산하고, 기록과 관리자 로그를 함께 남긴다
class PenaltyFulfillmentServiceTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final Instant NOW = LocalDateTime.of(2026, 10, 1, 9, 30).atZone(KST).toInstant();

	private WarningRecalculationService recalculation;
	private PenaltyFulfillmentRepository fulfillments;
	private AdminActionLogRepository logs;
	private PenaltyFulfillmentService service;

	@BeforeEach
	void setUp() {
		recalculation = mock(WarningRecalculationService.class);
		fulfillments = mock(PenaltyFulfillmentRepository.class);
		logs = mock(AdminActionLogRepository.class);
		service = new PenaltyFulfillmentService(recalculation, fulfillments, logs, Clock.fixed(NOW, KST));
		when(fulfillments.save(any(PenaltyFulfillment.class))).thenAnswer(call -> call.getArgument(0));
	}

	@Test
	@DisplayName("벌칙 대상이면 도달 주와 이행 시각을 기록하고 관리자 행동 로그를 남긴다")
	void recordsFulfillmentAndLog() {
		List<LocalDate> weeks = List.of(LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 17),
				LocalDate.of(2026, 8, 24));
		when(recalculation.recalculate(7L))
				.thenReturn(new WarningRecalcResult.Calculated(4, 0, true, weeks, List.of(), List.of()));

		PenaltyFulfillment result = service.fulfill(99L, 7L);

		// 도달 주는 세 번째 경고의 주다 (네 번째가 아니다)
		assertThat(result.getReachedWeek()).isEqualTo(LocalDate.of(2026, 8, 17));
		assertThat(result.getMemberId()).isEqualTo(7L);
		assertThat(result.getAdminId()).isEqualTo(99L);
		assertThat(result.getFulfilledAt()).isEqualTo(LocalDateTime.of(2026, 10, 1, 9, 30));
		ArgumentCaptor<AdminActionLog> log = ArgumentCaptor.forClass(AdminActionLog.class);
		verify(logs).save(log.capture());
		assertThat(log.getValue().getAction()).isEqualTo(AdminActionType.FULFILL_PENALTY);
		assertThat(log.getValue().getTargetType()).isEqualTo("MEMBER");
		assertThat(log.getValue().getTargetId()).isEqualTo("7");
		assertThat(log.getValue().getAdminId()).isEqualTo(99L);
	}

	@Test
	@DisplayName("대상이 아니거나 보류 중이면 아무것도 기록하지 않고 거부한다")
	void rejectsNonTargetAndOnHold() {
		when(recalculation.recalculate(7L)).thenReturn(new WarningRecalcResult.Calculated(2, 0, false,
				List.of(LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 10)), List.of(), List.of()));
		when(recalculation.recalculate(8L)).thenReturn(new WarningRecalcResult.OnHold(List.of(LocalDate.of(2026, 9, 7))));

		assertThatThrownBy(() -> service.fulfill(99L, 7L)).isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_PENALTY_TARGET));
		assertThatThrownBy(() -> service.fulfill(99L, 8L)).isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_PENALTY_TARGET));
		verify(fulfillments, never()).save(any());
		verify(logs, never()).save(any());
	}

}
