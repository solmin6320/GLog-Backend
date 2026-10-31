package com.jandilog.judgment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.jandilog.judgment.domain.HoldReason;
import com.jandilog.judgment.dto.GrassDay;
import com.jandilog.judgment.dto.GrassSnapshot;
import com.jandilog.judgment.repository.JudgmentSourceRepository;
import com.jandilog.judgment.repository.JudgmentSourceRepository.MemberRef;

// 프로필 잔디 전체 이력 새벽 일괄 갱신: 회원마다 따로 실패하고 나머지는 이어서 한다
class GrassHistorySchedulerTest {

	private final JudgmentSourceRepository sources = mock(JudgmentSourceRepository.class);
	private final GrassHistoryCacheService history = mock(GrassHistoryCacheService.class);
	private final GrassHistoryScheduler scheduler = new GrassHistoryScheduler(sources, history);

	private static GrassSnapshot snapshot(long memberId) {
		LocalDate today = LocalDate.of(2026, 10, 31);
		return new GrassSnapshot(memberId, today, today, today.atStartOfDay(), List.of(new GrassDay(today, 1)));
	}

	@Test
	void 활성_회원_모두를_숫자_id와_함께_갱신하고_실패는_세고_넘어간다() {
		when(sources.findActiveMembers()).thenReturn(List.of(new MemberRef(1, "one", 101L),
				new MemberRef(2, "two", 102L), new MemberRef(3, "three", 103L), new MemberRef(4, "four", 104L)));
		when(history.refresh(1, 101L, "one")).thenReturn(snapshot(1));
		when(history.refresh(2, 102L, "two"))
				.thenThrow(new GrassFetchException(HoldReason.IDENTITY_MISMATCH, "다른 계정"));
		when(history.refresh(3, 103L, "three")).thenThrow(new IllegalStateException("예기치 못한 오류"));
		when(history.refresh(4, 104L, "four")).thenReturn(snapshot(4));

		GrassHistoryScheduler.Summary summary = scheduler.refreshAll();

		assertThat(summary).isEqualTo(new GrassHistoryScheduler.Summary(4, 2, 2));
		verify(history).refresh(4, 104L, "four");
	}

	@Test
	void 회원이_없으면_아무것도_부르지_않는다() {
		when(sources.findActiveMembers()).thenReturn(List.of());

		assertThat(scheduler.refreshAll()).isEqualTo(new GrassHistoryScheduler.Summary(0, 0, 0));
		verify(history, never()).refresh(1, 101L, "one");
	}

	@Test
	void 회원_목록을_못_읽어도_예약_작업은_예외를_밖으로_내지_않는다() {
		when(sources.findActiveMembers()).thenThrow(new IllegalStateException("DB 장애 시험"));

		assertThatCode(scheduler::scheduledRefresh).doesNotThrowAnyException();
	}

}
