package com.jandilog.judgment.service;

import static com.jandilog.testsupport.judgment.JudgmentScenario.JUDGE_TIME;
import static com.jandilog.testsupport.judgment.JudgmentScenario.WEEK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;

import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.judgment.domain.HoldReason;
import com.jandilog.judgment.domain.JudgmentCalculator;
import com.jandilog.judgment.domain.JudgmentResult;
import com.jandilog.judgment.service.JudgmentRecordService.Outcome;
import com.jandilog.judgment.service.JudgmentRecordService.Type;
import com.jandilog.testsupport.judgment.JudgmentFixture.JudgmentRow;
import com.jandilog.testsupport.judgment.JudgmentFixture.WarningRow;
import com.jandilog.testsupport.judgment.JudgmentIntegrationTest;
import com.jandilog.testsupport.judgment.JudgmentScenario;

// 같은 주 재실행 멱등(회원 + 주차시작일, E-35)과 보류 재실행 (기능명세서 5장 "관련 내용").
// 확정된 주는 다시 돌려도 건드리지 않고, 보류는 확정이 아니라서 재실행 대상이다. 소속은 처음 저장한 주 종료 시각 스냅샷을 쓴다 (Q-04)
class JudgmentRecordIdempotencyTest extends JudgmentIntegrationTest {

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
		teams = fixture.teams(memberId, 2);
	}

	private JudgmentScenario scenario() {
		return JudgmentScenario.ofDefaultWeek().teams(teams);
	}

	private Outcome record(JudgmentScenario scenario) {
		return recordFor(memberId, scenario, scenario.teams());
	}

	private Outcome recordFor(long member, JudgmentScenario scenario, Set<Long> snapshot) {
		JudgmentResult result = calculator.judge(scenario.build());
		return service.record(member, scenario.weekStart(), result, snapshot);
	}

	private JudgmentRow saved() {
		return fixture.findJudgment(memberId, WEEK).orElseThrow();
	}

	// 인증일 3일 + 기록글 1개 → 통과
	private JudgmentScenario passing() {
		return scenario().grassOn(0, 1, 2).recordsOn(0);
	}

	// 인증일 0일 → 미달
	private JudgmentScenario failing() {
		return scenario();
	}

	// ---------- 확정된 주는 다시 돌려도 건드리지 않는다 ----------

	@Test
	void 확정된_주를_다시_저장하면_아무것도_하지_않는다() {
		Outcome first = record(passing());
		JudgmentRow before = saved();

		Outcome second = record(passing());

		assertThat(first.type()).isEqualTo(Type.CREATED);
		assertThat(second.type()).isEqualTo(Type.SKIPPED_CONFIRMED);
		assertThat(second.judgment().getId()).isEqualTo(first.judgment().getId());
		assertThat(fixture.countJudgments(memberId)).isEqualTo(1);
		assertThat(saved()).isEqualTo(before);
		assertThat(fixture.days(before.id())).hasSize(7);
		assertThat(fixture.judgmentTeamIds(before.id())).isEqualTo(teams);
	}

	@Test
	void 미달_주를_여러_번_저장해도_경고는_1개다() {
		record(failing());
		record(failing());
		Outcome third = record(failing());

		assertThat(third.type()).isEqualTo(Type.SKIPPED_CONFIRMED);
		assertThat(fixture.countJudgments(memberId)).isEqualTo(1);
		List<WarningRow> warnings = fixture.warnings(memberId);
		assertThat(warnings).hasSize(1);
		assertThat(fixture.warningTeamIds(warnings.get(0).id())).isEqualTo(teams);
	}

	@Test
	void 확정된_미달은_재실행_때_입력이_통과로_바뀌어도_그대로다() {
		// 글을 나중에 써도 확정 판정은 불변, 바꾸는 길은 관리자 정정뿐 (기능명세서 5장)
		record(failing());

		Outcome again = record(passing());

		assertThat(again.type()).isEqualTo(Type.SKIPPED_CONFIRMED);
		JudgmentRow row = saved();
		assertThat(row.status()).isEqualTo("FAIL");
		assertThat(row.verifiedDays()).isZero();
		assertThat(fixture.warnings(memberId)).hasSize(1);
		assertThat(fixture.warnings(memberId).get(0).deletedAt()).isNull();
	}

	@Test
	void 확정된_통과는_재실행_때_입력이_미달로_바뀌어도_경고가_생기지_않는다() {
		// 확정 후 글이 삭제된 것과 같은 상황
		record(passing());

		Outcome again = record(failing());

		assertThat(again.type()).isEqualTo(Type.SKIPPED_CONFIRMED);
		assertThat(saved().status()).isEqualTo("PASS");
		assertThat(fixture.warnings(memberId)).isEmpty();
	}

	@Test
	void 확정된_제외_주도_재실행하면_건너뛴다() {
		record(JudgmentScenario.ofDefaultWeek());

		Outcome again = record(failing());

		assertThat(again.type()).isEqualTo(Type.SKIPPED_CONFIRMED);
		assertThat(saved().status()).isEqualTo("EXCLUDED");
		assertThat(saved().skipReason()).isEqualTo("NO_TEAM");
		assertThat(fixture.warnings(memberId)).isEmpty();
		assertThat(fixture.judgmentTeamIds(saved().id())).isEmpty();
	}

	@Test
	void 확정된_면제_주도_재실행하면_건너뛴다() {
		record(scenario().personalExemption());

		Outcome again = record(failing());

		assertThat(again.type()).isEqualTo(Type.SKIPPED_CONFIRMED);
		assertThat(saved().status()).isEqualTo("EXEMPT");
		assertThat(fixture.warnings(memberId)).isEmpty();
	}

	@Test
	void 회원과_주차가_다르면_각각_따로_저장된다() {
		long other = fixture.member();
		Set<Long> otherTeams = fixture.teams(other, 1);
		clock.fixAt(JUDGE_TIME.plusDays(7).atZone(KST).toInstant());
		JudgmentScenario nextWeek = JudgmentScenario.of(WEEK.plusDays(7)).teams(teams);

		Outcome mine = record(failing());
		Outcome mineNextWeek = record(nextWeek);
		Outcome others = recordFor(other, JudgmentScenario.ofDefaultWeek().teams(otherTeams), otherTeams);

		assertThat(List.of(mine.type(), mineNextWeek.type(), others.type())).containsOnly(Type.CREATED);
		assertThat(fixture.countJudgments(memberId)).isEqualTo(2);
		assertThat(fixture.countJudgments(other)).isEqualTo(1);
		assertThat(fixture.warnings(memberId)).hasSize(2);
		assertThat(fixture.warnings(other)).hasSize(1);
	}

	@Test
	void DB_유니크_제약이_같은_회원_주차의_중복_행을_막는다() {
		record(passing());

		assertThatThrownBy(() -> jdbc.update(
				"insert into weekly_judgment (member_id, week_start, status, retry_count, corrected) values (?, ?, 'PASS', 0, false)",
				memberId, WEEK)).isInstanceOf(DuplicateKeyException.class);
		assertThat(fixture.countJudgments(memberId)).isEqualTo(1);
	}

	// ---------- 보류는 확정이 아니라서 재실행 대상이다 ----------

	@Test
	void 보류_주를_다시_돌려_통과가_나오면_같은_행이_통과로_확정된다() {
		Outcome hold = record(scenario().grassFailed(HoldReason.API_ERROR));
		long judgmentId = saved().id();

		Outcome rerun = record(passing());

		assertThat(hold.type()).isEqualTo(Type.CREATED);
		assertThat(rerun.type()).isEqualTo(Type.UPDATED);
		assertThat(fixture.countJudgments(memberId)).isEqualTo(1);
		JudgmentRow row = saved();
		assertThat(row.id()).isEqualTo(judgmentId);
		assertThat(row.status()).isEqualTo("PASS");
		assertThat(row.holdReason()).isNull();
		assertThat(row.verifiedDays()).isEqualTo(3);
		assertThat(row.recordCount()).isEqualTo(1);
		assertThat(row.judgedAt()).isEqualTo(JUDGE_TIME);
		assertThat(fixture.days(judgmentId)).hasSize(7);
		assertThat(fixture.warnings(memberId)).isEmpty();
	}

	@Test
	void 보류_주를_다시_돌려_미달이_나오면_그때_경고_1개가_생기고_저장된_스냅샷_팀이_붙는다() {
		record(scenario().grassFailed(HoldReason.API_ERROR));
		assertThat(fixture.warnings(memberId)).isEmpty();

		Outcome rerun = record(failing());

		assertThat(rerun.type()).isEqualTo(Type.UPDATED);
		assertThat(saved().status()).isEqualTo("FAIL");
		List<WarningRow> warnings = fixture.warnings(memberId);
		assertThat(warnings).hasSize(1);
		assertThat(warnings.get(0).weekStart()).isEqualTo(WEEK);
		assertThat(fixture.warningTeamIds(warnings.get(0).id())).isEqualTo(teams);
	}

	@Test
	void 보류가_확정된_뒤의_재실행은_건너뛴다() {
		record(scenario().grassFailed(HoldReason.API_ERROR));
		record(failing());

		Outcome third = record(failing());

		assertThat(third.type()).isEqualTo(Type.SKIPPED_CONFIRMED);
		assertThat(fixture.warnings(memberId)).hasSize(1);
		assertThat(fixture.countJudgments(memberId)).isEqualTo(1);
	}

	@Test
	void 보류_주를_다시_돌려도_또_실패하면_보류로_남고_경고가_없다() {
		record(scenario().grassFailed(HoldReason.API_ERROR));

		Outcome rerun = record(scenario().grassFailed(HoldReason.API_ERROR));

		assertThat(rerun.type()).isEqualTo(Type.UPDATED);
		assertThat(fixture.countJudgments(memberId)).isEqualTo(1);
		JudgmentRow row = saved();
		assertThat(row.status()).isEqualTo("HOLD");
		assertThat(row.holdReason()).isEqualTo("API_ERROR");
		assertThat(fixture.warnings(memberId)).isEmpty();
		assertThat(fixture.days(row.id())).isEmpty();
	}

	@Test
	void 재실행에서_보류_사유가_달라지면_새_사유로_갱신된다() {
		record(scenario().grassFailed(HoldReason.API_ERROR));

		record(scenario().grassFailed(HoldReason.IDENTITY_MISMATCH));

		JudgmentRow row = saved();
		assertThat(row.status()).isEqualTo("HOLD");
		assertThat(row.holdReason()).isEqualTo("IDENTITY_MISMATCH");
	}

	@Test
	void 보류_주를_재실행했더니_그사이_면제가_걸렸다면_면제로_확정되고_경고가_없다() {
		record(scenario().grassFailed(HoldReason.API_ERROR));

		Outcome rerun = record(scenario().personalExemption());

		assertThat(rerun.type()).isEqualTo(Type.UPDATED);
		JudgmentRow row = saved();
		assertThat(row.status()).isEqualTo("EXEMPT");
		assertThat(row.skipReason()).isEqualTo("PERSONAL_EXEMPTION");
		assertThat(row.holdReason()).isNull();
		assertThat(fixture.warnings(memberId)).isEmpty();
	}

	// ---------- 소속은 처음 저장한 스냅샷을 쓴다 (Q-04) ----------

	@Test
	void 보류_재실행에서_다른_소속을_넘겨도_처음_저장한_스냅샷이_유지된다() {
		Set<Long> later = fixture.teams(memberId, 1);
		record(scenario().grassFailed(HoldReason.API_ERROR));

		recordFor(memberId, failing(), later);

		JudgmentRow row = saved();
		assertThat(fixture.judgmentTeamIds(row.id())).isEqualTo(teams);
		WarningRow warning = fixture.warnings(memberId).get(0);
		assertThat(fixture.warningTeamIds(warning.id())).isEqualTo(teams);
	}

	@Test
	void 확정된_주를_다른_소속으로_다시_저장해도_스냅샷이_바뀌지_않는다() {
		Set<Long> later = fixture.teams(memberId, 1);
		record(failing());

		recordFor(memberId, failing(), later);

		assertThat(fixture.judgmentTeamIds(saved().id())).isEqualTo(teams);
		assertThat(fixture.warningTeamIds(fixture.warnings(memberId).get(0).id())).isEqualTo(teams);
	}

	// ---------- 회원 락과 트랜잭션 ----------

	@Test
	void 같은_회원_같은_주를_동시에_저장해도_행과_경고는_하나만_생긴다() throws Exception {
		List<Outcome> outcomes = runConcurrently(2, () -> record(failing()));

		assertThat(outcomes).extracting(Outcome::type).containsExactlyInAnyOrder(Type.CREATED, Type.SKIPPED_CONFIRMED);
		assertThat(fixture.countJudgments(memberId)).isEqualTo(1);
		assertThat(fixture.days(saved().id())).hasSize(7);
		assertThat(fixture.judgmentTeamIds(saved().id())).isEqualTo(teams);
		List<WarningRow> warnings = fixture.warnings(memberId);
		assertThat(warnings).hasSize(1);
		assertThat(fixture.warningTeamIds(warnings.get(0).id())).isEqualTo(teams);
	}

	@Test
	void 같은_보류_주를_동시에_재실행해도_한_번만_확정되고_경고는_1개다() throws Exception {
		record(scenario().grassFailed(HoldReason.API_ERROR));

		List<Outcome> outcomes = runConcurrently(2, () -> record(failing()));

		assertThat(outcomes).extracting(Outcome::type).containsExactlyInAnyOrder(Type.UPDATED, Type.SKIPPED_CONFIRMED);
		assertThat(saved().status()).isEqualTo("FAIL");
		assertThat(fixture.countJudgments(memberId)).isEqualTo(1);
		assertThat(fixture.days(saved().id())).hasSize(7);
		assertThat(fixture.warnings(memberId)).hasSize(1);
	}

	@Test
	void 소속_스냅샷이_없는_미달은_예외로_끝나고_아무것도_남지_않는다() {
		// 미달은 팀 소속이 있어야 나오므로 호출한 쪽 오류다. 판정 행도 같은 트랜잭션에서 되돌아가야 한다
		JudgmentResult fail = calculator.judge(failing().build());

		assertThatThrownBy(() -> service.record(memberId, WEEK, fail, Set.of()))
				.isInstanceOf(IllegalStateException.class);

		assertThat(fixture.countJudgments(memberId)).isZero();
		assertThat(fixture.warnings(memberId)).isEmpty();
	}

	@Test
	void 없는_회원이면_NOT_FOUND이고_저장하지_않는다() {
		JudgmentResult pass = calculator.judge(passing().build());
		long missingMemberId = Long.MAX_VALUE - 7;

		assertThatThrownBy(() -> service.record(missingMemberId, WEEK, pass, teams))
				.isInstanceOfSatisfying(ApiException.class,
						e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));

		assertThat(fixture.findJudgment(missingMemberId, WEEK)).isEmpty();
	}

	// 모든 작업이 준비될 때까지 기다렸다가 한꺼번에 시작한다
	private <T> List<T> runConcurrently(int count, Callable<T> task) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(count);
		try {
			CountDownLatch ready = new CountDownLatch(count);
			CountDownLatch go = new CountDownLatch(1);
			List<Future<T>> futures = new ArrayList<>();
			for (int i = 0; i < count; i++) {
				futures.add(pool.submit(() -> {
					ready.countDown();
					go.await();
					return task.call();
				}));
			}
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			go.countDown();
			List<T> results = new ArrayList<>();
			for (Future<T> future : futures) {
				results.add(future.get(30, TimeUnit.SECONDS));
			}
			return results;
		}
		finally {
			pool.shutdownNow();
		}
	}

}
