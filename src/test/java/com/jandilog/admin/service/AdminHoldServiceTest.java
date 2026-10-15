package com.jandilog.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.jandilog.admin.dto.AdminHoldRerunSummary;
import com.jandilog.admin.dto.AdminHoldRetryResult;
import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.judgment.domain.HoldReason;
import com.jandilog.judgment.domain.JudgmentResult;
import com.jandilog.judgment.domain.JudgmentStatus;
import com.jandilog.judgment.domain.WeeklyJudgment;
import com.jandilog.judgment.service.JudgmentHoldService;
import com.jandilog.judgment.service.JudgmentRecordService.Outcome;
import com.jandilog.judgment.service.JudgmentRecordService.Type;
import com.jandilog.member.domain.Member;
import com.jandilog.member.repository.MemberRepository;

// 보류 재시도 응답 변환 (AD-01 ⑦⑨). 실제 재판정은 JudgmentHoldService가 하므로 가짜로 대체해 변환 규칙만 본다
class AdminHoldServiceTest {

	private static final LocalDate WEEK = LocalDate.of(2026, 9, 7);
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 14, 7, 0);

	private JudgmentHoldService holdService;
	private MemberRepository memberRepository;
	private AdminHoldService service;

	@BeforeEach
	void setUp() {
		holdService = mock(JudgmentHoldService.class);
		memberRepository = mock(MemberRepository.class);
		service = new AdminHoldService(holdService, memberRepository);
		when(memberRepository.findById(7L))
				.thenReturn(Optional.of(Member.createPending(1000L, "octocat", "옥토캣", NOW)));
	}

	@Test
	@DisplayName("보류가 풀려 확정되면 resolved=true로 돌려준다")
	void resolvedWhenHoldBecomesConfirmed() {
		WeeklyJudgment judgment = WeeklyJudgment.of(7L, WEEK, JudgmentResult.judged(true, 4, 1, java.util.List.of(), NOW));
		when(holdService.rerunOne(7L, WEEK)).thenReturn(new Outcome(Type.UPDATED, judgment));

		AdminHoldRetryResult result = service.retryOne("7", "2026-09-07");

		assertThat(result.status()).isEqualTo(JudgmentStatus.PASS);
		assertThat(result.resolved()).isTrue();
		assertThat(result.weekStart()).isEqualTo("2026-09-07");
		assertThat(result.member().githubLogin()).isEqualTo("octocat");
	}

	@Test
	@DisplayName("여전히 보류거나 이미 확정돼 건너뛴 건은 resolved=false다")
	void notResolvedWhenStillHoldOrSkipped() {
		WeeklyJudgment stillHold = WeeklyJudgment.of(7L, WEEK, JudgmentResult.hold(HoldReason.IDENTITY_MISMATCH));
		when(holdService.rerunOne(7L, WEEK)).thenReturn(new Outcome(Type.UPDATED, stillHold));

		AdminHoldRetryResult hold = service.retryOne("7", "2026-09-07");
		assertThat(hold.status()).isEqualTo(JudgmentStatus.HOLD);
		assertThat(hold.holdReason()).isEqualTo(HoldReason.IDENTITY_MISMATCH);
		assertThat(hold.resolved()).isFalse();

		WeeklyJudgment confirmed = WeeklyJudgment.of(7L, WEEK, JudgmentResult.judged(false, 1, 0, java.util.List.of(), NOW));
		when(holdService.rerunOne(7L, WEEK)).thenReturn(new Outcome(Type.SKIPPED_CONFIRMED, confirmed));
		AdminHoldRetryResult skipped = service.retryOne("7", "2026-09-07");
		assertThat(skipped.status()).isEqualTo(JudgmentStatus.FAIL);
		assertThat(skipped.resolved()).isFalse();
	}

	@Test
	@DisplayName("없는 회원이나 잘못된 입력이면 재판정을 시도하지 않는다")
	void rejectsBadInputBeforeRerun() {
		assertThatThrownBy(() -> service.retryOne("8", "2026-09-07"))
				.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
		assertThatThrownBy(() -> service.retryOne("abc", "2026-09-07"))
				.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
		assertThatThrownBy(() -> service.retryOne("7", "2026-09-08"))
				.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
		verify(holdService, never()).rerunOne(anyLong(), org.mockito.ArgumentMatchers.any());
	}

	@Test
	@DisplayName("전체 재시도 결과를 그대로 옮긴다")
	void retryAllMapsSummary() {
		when(holdService.rerunAll()).thenReturn(new JudgmentHoldService.RerunSummary(5, 3, 1, 1));

		AdminHoldRerunSummary summary = service.retryAll();

		assertThat(summary).isEqualTo(new AdminHoldRerunSummary(5, 3, 1, 1));
	}

}
