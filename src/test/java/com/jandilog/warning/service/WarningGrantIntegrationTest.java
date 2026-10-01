package com.jandilog.warning.service;

import static com.jandilog.testsupport.warning.RecalcAssert.assertActive;
import static com.jandilog.testsupport.warning.RecalcAssert.assertDeducted;
import static com.jandilog.testsupport.warning.RecalcAssert.assertState;
import static com.jandilog.testsupport.warning.RecalcAssert.holdWeeks;
import static com.jandilog.testsupport.warning.WeekScript.endOf;
import static com.jandilog.testsupport.warning.WeekScript.week;
import static com.jandilog.testsupport.warning.WeekScript.weeks;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.jandilog.judgment.domain.DayResult;
import com.jandilog.judgment.domain.HoldReason;
import com.jandilog.judgment.domain.JudgmentResult;
import com.jandilog.judgment.domain.JudgmentStatus;
import com.jandilog.judgment.domain.SkipReason;
import com.jandilog.judgment.service.JudgmentRecordService;
import com.jandilog.judgment.service.JudgmentRecordService.Outcome;
import com.jandilog.judgment.service.JudgmentRecordService.Type;
import com.jandilog.testsupport.warning.WarningIntegrationTest;

// 판정 저장이 경고를 부여하는 규칙 (기능명세서 6장 "주간 미달: 경고 1개, 속한 팀 전부를 카테고리로", DB명세서 4-2).
// 판정 계산·스케줄러는 판정 쪽 테스트가 맡고 여기서는 경고 행과 팀 카테고리만 본다
class WarningGrantIntegrationTest extends WarningIntegrationTest {

	@Autowired
	private JudgmentRecordService recordService;

	private static LocalDateTime judgedAt(LocalDate weekStart) {
		return weekStart.plusDays(7).atTime(7, 0);
	}

	private static List<DayResult> days(LocalDate weekStart, int verifiedDays) {
		List<DayResult> days = new ArrayList<>();
		for (int i = 0; i < 7; i++) {
			days.add(new DayResult(weekStart.plusDays(i), i < verifiedDays, false));
		}
		return days;
	}

	private static JudgmentResult fail(LocalDate weekStart) {
		return JudgmentResult.judged(false, 1, 0, days(weekStart, 1), judgedAt(weekStart));
	}

	private static JudgmentResult pass(LocalDate weekStart) {
		return JudgmentResult.judged(true, 3, 1, days(weekStart, 3), judgedAt(weekStart));
	}

	private Outcome saveJudgment(long member, int weekIndex, JudgmentResult result, Long... teamIds) {
		return recordService.record(member, week(weekIndex), result, Set.of(teamIds));
	}

	private int warningCount(long member) {
		return fixture.count("select count(*) from warning where member_id = ?", member);
	}

	private List<Long> warningTeamIds(long member, int weekIndex) {
		return jdbc.queryForList("select wt.team_id from warning_team wt join warning w on w.id = wt.warning_id"
				+ " where w.member_id = ? and w.week_start = ? order by wt.team_id", Long.class, member,
				week(weekIndex));
	}

	@Test
	void 미달이면_경고_1개가_생기고_소속_팀_전부가_카테고리로_붙는다() {
		long member = fixture.member();
		long teamA = fixture.team(member);
		long teamB = fixture.team(member);

		Outcome outcome = saveJudgment(member, 0, fail(week(0)), teamA, teamB);

		assertThat(outcome.type()).isEqualTo(Type.CREATED);
		assertThat(outcome.judgment().getStatus()).isEqualTo(JudgmentStatus.FAIL);
		// 팀이 둘이어도 경고는 사람당 1개
		assertThat(warningCount(member)).isEqualTo(1);
		assertThat(warningTeamIds(member, 0)).containsExactlyInAnyOrder(teamA, teamB);
		assertThat(fixture.count("select count(*) from warning_team wt join warning w on w.id = wt.warning_id"
				+ " where w.member_id = ? and wt.removed_at is not null", member)).isZero();
	}

	@Test
	void 팀_하나만_속하면_카테고리도_하나다() {
		long member = fixture.member();
		long team = fixture.team(member);

		saveJudgment(member, 0, fail(week(0)), team);

		assertThat(warningCount(member)).isEqualTo(1);
		assertThat(warningTeamIds(member, 0)).containsExactly(team);
	}

	@Test
	void 새_경고는_삭제_표시가_없고_판정_시각에_만들어진다() {
		long member = fixture.member();
		long team = fixture.team(member);

		saveJudgment(member, 0, fail(week(0)), team);

		Map<String, Object> row = jdbc.queryForMap(
				"select deleted_at, delete_reason from warning where member_id = ?", member);
		assertThat(row.get("deleted_at")).isNull();
		assertThat(row.get("delete_reason")).isNull();
		LocalDateTime createdAt = jdbc.queryForObject("select created_at from warning where member_id = ?",
				LocalDateTime.class, member);
		assertThat(createdAt).isEqualTo(judgedAt(week(0)));
	}

	@Test
	void 미달_판정과_함께_소속_스냅샷과_일자별_근거_7행도_저장된다() {
		long member = fixture.member();
		long teamA = fixture.team(member);
		long teamB = fixture.team(member);

		saveJudgment(member, 0, fail(week(0)), teamA, teamB);

		long judgmentId = fixture.judgmentId(member, 0);
		assertThat(fixture.count("select count(*) from judgment_team where judgment_id = ?", judgmentId)).isEqualTo(2);
		assertThat(fixture.count("select count(*) from judgment_day where judgment_id = ?", judgmentId)).isEqualTo(7);
	}

	@Test
	void 통과하면_경고가_생기지_않는다() {
		long member = fixture.member();
		long team = fixture.team(member);

		Outcome outcome = saveJudgment(member, 0, pass(week(0)), team);

		assertThat(outcome.judgment().getStatus()).isEqualTo(JudgmentStatus.PASS);
		assertThat(warningCount(member)).isZero();
		assertThat(fixture.count("select count(*) from warning_team wt join warning w on w.id = wt.warning_id"
				+ " where w.member_id = ?", member)).isZero();
	}

	@Test
	void 면제와_제외_주는_경고가_생기지_않는다() {
		long member = fixture.member();
		long team = fixture.team(member);

		saveJudgment(member, 0, JudgmentResult.skipped(SkipReason.EXEMPTION_PERIOD, judgedAt(week(0))), team);
		saveJudgment(member, 1, JudgmentResult.skipped(SkipReason.PERSONAL_EXEMPTION, judgedAt(week(1))), team);
		saveJudgment(member, 2, JudgmentResult.skipped(SkipReason.FIRST_WEEK, judgedAt(week(2))), team);
		saveJudgment(member, 3, JudgmentResult.skipped(SkipReason.NO_TEAM, judgedAt(week(3))));

		assertThat(warningCount(member)).isZero();
		assertState(recalculation.calculate(member), 0, 0);
	}

	@Test
	void 보류_주는_경고를_주지_않고_재계산도_보류한다() {
		long member = fixture.member();
		long team = fixture.team(member);

		Outcome outcome = saveJudgment(member, 0, JudgmentResult.hold(HoldReason.API_ERROR), team);

		assertThat(outcome.judgment().getStatus()).isEqualTo(JudgmentStatus.HOLD);
		assertThat(warningCount(member)).isZero();
		assertThat(holdWeeks(recalculation.calculate(member))).isEqualTo(weeks(0));
	}

	@Test
	void 보류가_미달로_확정되면_그때_경고가_생기고_처음_저장한_소속_스냅샷을_쓴다() {
		long member = fixture.member();
		long teamA = fixture.team(member);
		long teamB = fixture.team(member);
		saveJudgment(member, 0, JudgmentResult.hold(HoldReason.API_ERROR), teamA);

		// 재실행 때 넘긴 소속은 쓰지 않는다: 소속은 주 종료 시각 기준 스냅샷 (Q-04)
		Outcome outcome = saveJudgment(member, 0, fail(week(0)), teamB);

		assertThat(outcome.type()).isEqualTo(Type.UPDATED);
		assertThat(warningCount(member)).isEqualTo(1);
		assertThat(warningTeamIds(member, 0)).containsExactly(teamA);
		assertState(recalculation.calculate(member), 1, 0);
	}

	@Test
	void 보류가_통과로_확정되면_경고가_없다() {
		long member = fixture.member();
		long team = fixture.team(member);
		saveJudgment(member, 0, JudgmentResult.hold(HoldReason.IDENTITY_MISMATCH), team);

		Outcome outcome = saveJudgment(member, 0, pass(week(0)), team);

		assertThat(outcome.type()).isEqualTo(Type.UPDATED);
		assertThat(warningCount(member)).isZero();
		assertState(recalculation.calculate(member), 0, 1);
	}

	@Test
	void 확정된_주를_다시_돌려도_경고가_늘거나_바뀌지_않는다() {
		long member = fixture.member();
		long team = fixture.team(member);
		saveJudgment(member, 0, fail(week(0)), team);
		var snapshot = fixture.snapshot(member);

		Outcome again = saveJudgment(member, 0, fail(week(0)), team);
		Outcome different = saveJudgment(member, 0, pass(week(0)), team);

		assertThat(again.type()).isEqualTo(Type.SKIPPED_CONFIRMED);
		assertThat(different.type()).isEqualTo(Type.SKIPPED_CONFIRMED);
		assertThat(different.judgment().getStatus()).isEqualTo(JudgmentStatus.FAIL);
		assertThat(fixture.snapshot(member)).isEqualTo(snapshot);
		assertThat(warningCount(member)).isEqualTo(1);
	}

	@Test
	void 소속_스냅샷이_없는_미달은_저장하지_않고_통째로_되돌린다() {
		long member = fixture.member();

		assertThatThrownBy(() -> saveJudgment(member, 0, fail(week(0)))).isInstanceOf(IllegalStateException.class);

		assertThat(fixture.count("select count(*) from weekly_judgment where member_id = ?", member)).isZero();
		assertThat(warningCount(member)).isZero();
	}

	@Test
	void 미달이_3주_이어져_저장되면_재계산이_벌칙_대상으로_본다() {
		long member = fixture.member();
		long team = fixture.team(member);

		saveJudgment(member, 0, fail(week(0)), team);
		saveJudgment(member, 1, fail(week(1)), team);
		assertState(recalculation.calculate(member), 2, 0);
		saveJudgment(member, 2, fail(week(2)), team);

		assertThat(warningCount(member)).isEqualTo(3);
		assertState(recalculation.calculate(member), 3, 0);
	}

	@Test
	void 벌칙_대상에서_또_미달하면_경고_행이_계속_생겨_3을_넘는다() {
		long member = fixture.member();
		long team = fixture.team(member);

		for (int i = 0; i < 4; i++) {
			saveJudgment(member, i, fail(week(i)), team);
		}

		assertThat(warningCount(member)).isEqualTo(4);
		assertState(recalculation.calculate(member), 4, 0);
	}

	@Test
	void 벌칙_이행_체크_뒤의_미달은_이전_경고와_합치지_않고_새로_센다() {
		long member = fixture.member();
		long admin = fixture.admin();
		long team = fixture.team(member);
		for (int i = 0; i < 3; i++) {
			saveJudgment(member, i, fail(week(i)), team);
		}
		assertState(recalculation.calculate(member), 3, 0);

		fixture.fulfill(member, admin, endOf(2).plusDays(1).plusHours(14), week(2));
		assertState(recalculation.calculate(member), 0, 0);
		saveJudgment(member, 3, fail(week(3)), team);

		// 이전 경고 행은 지우지 않고 기준선 이후 것만 센다
		assertThat(warningCount(member)).isEqualTo(4);
		assertActive(assertState(recalculation.calculate(member), 1, 0), 3);
	}

	@Test
	void 삭제_표시된_경고의_주를_다시_돌려도_경고는_되살아나지_않는다() {
		long member = fixture.member();
		long team = fixture.team(member);
		saveJudgment(member, 0, fail(week(0)), team);
		saveJudgment(member, 1, fail(week(1)), team);
		fixture.softDeleteWarning(member, 0, LocalDateTime.of(2026, 2, 2, 0, 0));
		var snapshot = fixture.snapshot(member);

		Outcome again = saveJudgment(member, 0, fail(week(0)), team);

		assertThat(again.type()).isEqualTo(Type.SKIPPED_CONFIRMED);
		assertThat(fixture.snapshot(member)).isEqualTo(snapshot);
		assertThat(fixture.count("select count(*) from warning where member_id = ? and deleted_at is not null", member))
				.isEqualTo(1);
		assertActive(assertState(recalculation.calculate(member), 1, 0), 1);
	}

	@Test
	void 판정_저장은_같은_회원의_락이_풀릴_때까지_기다린다() throws Exception {
		long member = fixture.member();
		long team = fixture.team(member);
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<?> holder = pool.submit(() -> runInTransaction(() -> {
				recalculation.lockMember(member);
				locked.countDown();
				await(release);
			}));
			assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

			Future<Outcome> saving = pool.submit(() -> saveJudgment(member, 0, fail(week(0)), team));
			Thread.sleep(Duration.ofMillis(800));
			assertThat(saving.isDone()).as("락이 풀릴 때까지 기다려야 해요").isFalse();
			assertThat(warningCount(member)).isZero();

			release.countDown();
			holder.get(10, TimeUnit.SECONDS);
			assertThat(saving.get(10, TimeUnit.SECONDS).type()).isEqualTo(Type.CREATED);
			assertThat(warningCount(member)).isEqualTo(1);
		}
		finally {
			release.countDown();
			pool.shutdownNow();
		}
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(20, TimeUnit.SECONDS)) {
				throw new IllegalStateException("대기 시간 초과");
			}
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}

	@Test
	void 미달_뒤_통과_2주를_저장하면_재계산이_가장_오래된_경고를_차감한다() {
		long member = fixture.member();
		long team = fixture.team(member);

		saveJudgment(member, 0, fail(week(0)), team);
		saveJudgment(member, 1, fail(week(1)), team);
		saveJudgment(member, 2, pass(week(2)), team);
		saveJudgment(member, 3, pass(week(3)), team);

		// 경고 행은 그대로 두고 재계산이 차감을 계산한다 (더하고 빼지 않는다)
		assertThat(warningCount(member)).isEqualTo(2);
		var result = assertState(recalculation.calculate(member), 1, 0);
		assertActive(result, 1);
		assertDeducted(result, 0);
	}

	@Test
	void 같은_회원의_다른_주_미달은_각각_경고_하나씩이다() {
		long member = fixture.member();
		long team = fixture.team(member);

		saveJudgment(member, 0, fail(week(0)), team);
		saveJudgment(member, 1, fail(week(1)), team);

		assertThat(warningCount(member)).isEqualTo(2);
		assertThat(warningTeamIds(member, 0)).containsExactly(team);
		assertThat(warningTeamIds(member, 1)).containsExactly(team);
	}

}
