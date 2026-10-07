package com.jandilog.judgment.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.jandilog.exemption.dto.ExemptionPeriodInput;
import com.jandilog.exemption.service.ExemptionPeriodService;
import com.jandilog.judgment.service.JudgmentCatchUpService;
import com.jandilog.judgment.service.JudgmentCatchUpService.Result;
import com.jandilog.judgment.service.WeeklyJudgmentBatchService.JudgmentSummary;
import com.jandilog.testsupport.judgment.JudgmentFixture.JudgmentRow;
import com.jandilog.testsupport.judgment.JudgmentPipelineIntegrationTest;
import com.jandilog.warning.domain.WarningRecalcResult.Calculated;

// 같은 주를 다시 돌려도 결과가 변하지 않는지(E-35), 확정 뒤 글이 바뀌어도 판정이 불변인지(5장),
// 기록글 인정 기준(Q-11)이 주마다 반영되는지, 서버가 월요일을 놓쳤을 때 이어지는지(E-34)를 본다
class JudgmentSixWeekIdempotencyIntegrationTest extends JudgmentPipelineIntegrationTest {

	@Autowired
	private ExemptionPeriodService periodService;

	@Test
	void 여섯_주를_판정한_뒤_모든_주를_다시_돌려도_판정_근거_경고가_하나도_바뀌지_않고_GitHub도_부르지_않는다() {
		long teamId = team(outsider());
		Person steady = settled("steady", teamId);
		Person faller = settled("faller", teamId);
		Person noTeam = person("noTeam", null);
		plan(steady, "PPPP-P");
		plan(faller, "FG-P-F");
		plan(noTeam, "PPPPPP");
		// 4주차는 전체 면제 기간
		clockAt(at(3, 3, 12, 0));
		periodService.create(admin(), new ExemptionPeriodInput(week(4).toString(), "방학"));

		mondayRuns(0, 5);

		assertStatus(steady, 4, "EXEMPT");
		assertStatus(faller, 4, "EXEMPT");
		assertStatus(noTeam, 4, "EXCLUDED");
		Calculated steadyState = state(steady);
		Calculated fallerState = state(faller);
		assertThat(fallerState.warningCount()).isEqualTo(4);
		String before = fingerprint(steady, faller, noTeam);
		int callsBefore = fakeGrass.callCount();

		// 같은 월요일에 직전 주 판정이 한 번 더 도는 경우: 확정된 회원은 건너뛴다
		clockAt(at(6, 0, 7, 30));
		JudgmentSummary again = batch.judgeLastWeek();
		assertThat(again.skippedConfirmed()).isEqualTo(3);
		assertThat(again.created()).isZero();

		// 이틀 뒤 관리자가 모든 주를 다시 돌린다: 새로 만드는 것도 바꾸는 것도 없다
		clockAt(at(7, 1, 9, 0));
		for (int w = 0; w < 6; w++) {
			JudgmentSummary summary = batch.judgeWeek(week(w));
			assertThat(summary.total()).as("w%d 대상", w).isEqualTo(3);
			assertThat(summary.skippedConfirmed()).as("w%d 건너뜀", w).isEqualTo(3);
			assertThat(summary.created()).as("w%d 새로 저장", w).isZero();
			assertThat(summary.updated()).as("w%d 갱신", w).isZero();
			assertThat(summary.hold()).isZero();
			assertThat(summary.failed()).isZero();
		}

		assertThat(fingerprint(steady, faller, noTeam)).isEqualTo(before);
		assertThat(fakeGrass.callCount()).as("확정된 주는 GitHub를 다시 부르지 않는다").isEqualTo(callsBefore);
		Calculated steadyAfter = state(steady);
		Calculated fallerAfter = state(faller);
		assertThat(steadyAfter).isEqualTo(steadyState);
		assertThat(fallerAfter).isEqualTo(fallerState);
		// 판정은 (회원, 주) 하나, 미달 주마다 경고 하나
		assertThat(fixture.countJudgments(steady.id())).isEqualTo(6);
		assertThat(fixture.countJudgments(faller.id())).isEqualTo(6);
		assertThat(warningWeeks(faller)).containsExactly(week(0), week(1), week(2), week(5));
	}

	@Test
	void 끝나지_않은_주는_판정하지_않고_월요일이_되면_판정한다() {
		long teamId = team(outsider());
		Person person = settled("person", teamId);
		plan(person, "-P");

		// 일요일 23:59:59: 아직 채울 수 있는 주라 판정도 경고도 만들지 않는다
		clock.fixAt(week(0).plusDays(6).atTime(23, 59, 59).atZone(KST).toInstant());
		JudgmentSummary early = batch.judgeWeek(week(0));

		assertThat(early.created()).isZero();
		assertThat(rowIfPresent(person, 0)).isEmpty();
		assertThat(fixture.warnings(person.id())).isEmpty();

		mondayRun(0);
		assertStatus(person, 0, "FAIL");
		assertThat(warningWeeks(person)).containsExactly(week(0));
		// 진행 중인 1주차도 일요일 밤까지는 판정하지 않는다
		clock.fixAt(week(1).plusDays(6).atTime(23, 59, 59).atZone(KST).toInstant());
		batch.judgeWeek(week(1));
		assertThat(rowIfPresent(person, 1)).isEmpty();
		mondayRun(1);
		assertStatus(person, 1, "PASS");
		assertState("w1", person, 1, 1);
	}

	@Test
	void 기록글_인정_기준이_주마다_판정에_반영되고_확정된_뒤에는_글을_지우거나_써도_판정이_바뀌지_않는다() {
		long teamId = team(outsider());
		Person poster = settled("poster", teamId);

		// 0주: 같은 날(화) 잔디와 기록글이 함께 있어도 인증일은 1일 → 월·화·수 3일, 기록글 2개
		grassOn(poster, 0, 0, 1);
		recordOn(poster, 0, 1, 2);
		// 1주: 수요일 기록글이 판정 전에 삭제됨 → 제외 (Q-11)
		grassOn(poster, 1, 0, 1);
		recordPost(poster, week(1).plusDays(2), true, at(1, 4, 9, 0));
		// 2주: 필수 항목이 빈 글은 기록글이 아니다
		grassOn(poster, 2, 0, 1);
		recordPost(poster, week(2).plusDays(2), false, null);
		// 3주: 인증일 3일이어도 기록글이 없으면 미달
		grassOn(poster, 3, 0, 1, 2);
		// 4주: 잔디 없이 기록글만 서로 다른 3일 → 기록으로 인증
		recordOn(poster, 4, 0, 1, 2);
		// 5주: 일요일 기록글은 5주차, 6주차 월요일 기록글은 6주차 (주 경계)
		grassOn(poster, 5, 0, 1);
		recordOn(poster, 5, 6);
		grassOn(poster, 6, 1, 2);
		recordOn(poster, 6, 0);

		int[] verified = {3, 2, 2, 3, 3, 3, 3};
		int[] records = {2, 0, 0, 0, 3, 1, 1};
		String[] status = {"PASS", "FAIL", "FAIL", "FAIL", "PASS", "PASS", "PASS"};
		int[] warnings = {0, 1, 2, 3, 3, 3, 3};
		for (int w = 0; w <= 6; w++) {
			mondayRun(w);
			JudgmentRow row = row(poster, w);
			assertThat(row.status()).as("w%d 판정", w).isEqualTo(status[w]);
			assertThat(row.verifiedDays()).as("w%d 인증일", w).isEqualTo(verified[w]);
			assertThat(row.recordCount()).as("w%d 기록글", w).isEqualTo(records[w]);
			assertThat(state(poster).warningCount()).as("w%d 경고 수", w).isEqualTo(warnings[w]);
		}
		assertThat(warningWeeks(poster)).containsExactly(week(1), week(2), week(3));
		assertThat(state(poster).penaltyTarget()).isTrue();

		// 확정 뒤: 통과 주의 글을 지우고, 미달 주에 기록글과 잔디를 뒤늦게 채운다
		String before = fingerprint(poster);
		jdbc.update("update post_index set deleted_at = ? where author_id = ? and written_date between ? and ?",
				at(7, 1, 9, 0), poster.id(), week(0), week(0).plusDays(6));
		recordOn(poster, 1, 2);
		grass.put(login(poster), week(1).plusDays(2), 1);
		clockAt(at(7, 2, 9, 0));
		for (int w = 0; w <= 6; w++) {
			JudgmentSummary summary = batch.judgeWeek(week(w));
			assertThat(summary.skippedConfirmed()).as("w%d 건너뜀", w).isEqualTo(1);
		}

		assertThat(fingerprint(poster)).as("확정된 판정은 글이 바뀌어도 그대로다").isEqualTo(before);
		assertThat(row(poster, 0).status()).isEqualTo("PASS");
		assertThat(row(poster, 0).recordCount()).isEqualTo(2);
		assertThat(row(poster, 1).status()).isEqualTo("FAIL");
		assertThat(warningWeeks(poster)).containsExactly(week(1), week(2), week(3));
	}

	@Test
	void 서버가_월요일_판정을_놓치면_기동할_때_빠진_주를_오래된_순으로_판정하고_다음_월요일부터_이어서_계산한다() {
		long teamId = team(outsider());
		Person sleeper = settled("sleeper", teamId);
		plan(sleeper, "PFFPPP");

		mondayRun(0);
		mondayRun(1);
		assertState("w0~w1", sleeper, 1, 0);
		JudgmentRow week0 = row(sleeper, 0);
		JudgmentRow week1 = row(sleeper, 1);

		// 3·4주차 월요일 판정(2·3주차 대상)을 서버가 꺼져 있어서 놓치고 5주차 월요일 오전 8시에 기동한다
		clockAt(at(5, 0, 8, 0));
		JudgmentCatchUpService catchUp = new JudgmentCatchUpService(batch, () -> latestJudgedWeek(sleeper), clock);

		Result result = catchUp.catchUp();

		assertThat(result.judgedWeeks()).containsExactly(week(2), week(3), week(4));
		assertThat(result.leftWeeks()).isEmpty();
		assertStatus(sleeper, 2, "FAIL");
		assertStatus(sleeper, 3, "PASS");
		assertStatus(sleeper, 4, "PASS");
		assertThat(row(sleeper, 2).judgedAt()).isEqualTo(at(5, 0, 8, 0));
		// 이미 확정된 주는 건드리지 않는다
		assertThat(row(sleeper, 0)).isEqualTo(week0);
		assertThat(row(sleeper, 1)).isEqualTo(week1);
		assertThat(warningWeeks(sleeper)).containsExactly(week(1), week(2));
		// 빠진 주가 제때 판정된 것과 같은 값: 1·2주 미달, 3·4주 통과로 가장 오래된 1주차 경고 차감
		Calculated afterCatchUp = state(sleeper);
		assertThat(afterCatchUp.warningCount()).isEqualTo(1);
		assertThat(afterCatchUp.streak()).isZero();
		assertThat(afterCatchUp.deductedWarningWeeks()).containsExactly(week(1));
		assertThat(afterCatchUp.activeWarningWeeks()).containsExactly(week(2));

		// 다시 기동해도 더 판정할 주가 없다
		assertThat(catchUp.catchUp().judgedWeeks()).isEmpty();

		// 이어지는 월요일 판정은 기동 때 채운 이력 위에서 계산한다
		mondayRun(5);
		assertStatus(sleeper, 5, "PASS");
		assertState("w5", sleeper, 1, 1);
	}

	private Optional<LocalDate> latestJudgedWeek(Person person) {
		List<LocalDate> weeks = jdbc.queryForList("select max(week_start) from weekly_judgment where member_id = ?",
				LocalDate.class, person.id());
		return weeks.isEmpty() || weeks.get(0) == null ? Optional.empty() : Optional.of(weeks.get(0));
	}

}
