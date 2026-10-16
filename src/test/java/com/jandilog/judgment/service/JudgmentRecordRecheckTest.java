package com.jandilog.judgment.service;

import static com.jandilog.testsupport.judgment.JudgmentScenario.JUDGE_TIME;
import static com.jandilog.testsupport.judgment.JudgmentScenario.WEEK;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.jandilog.judgment.domain.JudgmentCalculator;
import com.jandilog.judgment.domain.JudgmentResult;
import com.jandilog.judgment.domain.SkipReason;
import com.jandilog.judgment.service.JudgmentRecordService.Outcome;
import com.jandilog.judgment.service.JudgmentRecordService.Type;
import com.jandilog.testsupport.judgment.JudgmentFixture.JudgmentRow;
import com.jandilog.testsupport.judgment.JudgmentIntegrationTest;
import com.jandilog.testsupport.judgment.JudgmentScenario;

// 회원 락을 잡은 뒤 저장 직전에 결과를 다시 확인하는 훅 (면제 승인 경쟁 방지, 기능명세서 5장·7장).
// 시계는 2026-10-05(월) 07:00 KST, 판정 대상 주는 2026-09-28(월) ~ 10-04(일)
class JudgmentRecordRecheckTest extends JudgmentIntegrationTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	@Autowired
	private JudgmentRecordService service;

	private JudgmentCalculator calculator;
	private long memberId;
	private Set<Long> teams;

	@BeforeEach
	void setUp() {
		clock.fixAt(JUDGE_TIME.atZone(KST).toInstant());
		calculator = new JudgmentCalculator(clock);
		memberId = fixture.member();
		teams = fixture.teams(memberId, 1);
	}

	@Test
	void 훅이_면제_결과를_돌려주면_미달이_아니라_면제로_저장되고_경고가_없다() {
		JudgmentResult computed = fail();

		Outcome outcome = service.record(memberId, WEEK, computed, teams, false,
				result -> JudgmentResult.skipped(SkipReason.PERSONAL_EXEMPTION, result.judgedAt()));

		assertThat(outcome.type()).isEqualTo(Type.CREATED);
		JudgmentRow row = fixture.findJudgment(memberId, WEEK).orElseThrow();
		assertThat(row.status()).isEqualTo("EXEMPT");
		assertThat(row.skipReason()).isEqualTo("PERSONAL_EXEMPTION");
		assertThat(fixture.days(row.id())).isEmpty();
		assertThat(fixture.warnings(memberId)).isEmpty();
	}

	@Test
	void 훅이_결과를_그대로_돌려주면_계산한_대로_저장한다() {
		AtomicInteger calls = new AtomicInteger();

		service.record(memberId, WEEK, fail(), teams, false, result -> {
			calls.incrementAndGet();
			return result;
		});

		assertThat(calls.get()).isEqualTo(1);
		assertThat(fixture.findJudgment(memberId, WEEK).orElseThrow().status()).isEqualTo("FAIL");
		assertThat(fixture.warnings(memberId)).hasSize(1);
	}

	@Test
	void 이미_확정된_주는_훅을_부르지_않고_건너뛴다() {
		service.record(memberId, WEEK, fail(), teams);
		AtomicInteger calls = new AtomicInteger();

		Outcome outcome = service.record(memberId, WEEK, fail(), teams, false, result -> {
			calls.incrementAndGet();
			return JudgmentResult.skipped(SkipReason.EXEMPTION_PERIOD, result.judgedAt());
		});

		assertThat(outcome.type()).isEqualTo(Type.SKIPPED_CONFIRMED);
		assertThat(calls.get()).isZero();
		assertThat(fixture.findJudgment(memberId, WEEK).orElseThrow().status()).isEqualTo("FAIL");
		assertThat(fixture.warnings(memberId)).hasSize(1);
	}

	private JudgmentResult fail() {
		return calculator.judge(JudgmentScenario.ofDefaultWeek().teams(teams).build());
	}

}
