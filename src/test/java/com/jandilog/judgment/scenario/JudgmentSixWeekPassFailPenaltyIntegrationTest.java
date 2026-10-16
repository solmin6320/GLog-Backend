package com.jandilog.judgment.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.jandilog.exemption.dto.CorrectedJudgment;
import com.jandilog.exemption.dto.CorrectionPreview;
import com.jandilog.exemption.dto.JudgmentCorrectionInput;
import com.jandilog.exemption.service.JudgmentCorrectionService;
import com.jandilog.judgment.domain.JudgmentStatus;
import com.jandilog.judgment.service.WeeklyJudgmentBatchService.JudgmentSummary;
import com.jandilog.testsupport.judgment.JudgmentFixture.DayRow;
import com.jandilog.testsupport.judgment.JudgmentFixture.JudgmentRow;
import com.jandilog.testsupport.judgment.JudgmentPipelineIntegrationTest;
import com.jandilog.warning.domain.PenaltyFulfillment;
import com.jandilog.warning.domain.WarningRecalcResult.Calculated;
import com.jandilog.warning.service.PenaltyFulfillmentService;
import com.jandilog.warning.service.PenaltyTargetService;
import com.jandilog.warning.service.PenaltyTargetService.Target;

// 통과·미달·벌칙·이행이 주를 건너 이어지는 판정 시나리오 (기능명세서 5·6장, Q-10, Q-13).
// 월요일 07:00 일괄 판정 → 경고 부여 → 처음부터 다시 계산한 경고 수·연속 통과를 주마다 확인한다
class JudgmentSixWeekPassFailPenaltyIntegrationTest extends JudgmentPipelineIntegrationTest {

	@Autowired
	private PenaltyFulfillmentService penaltyFulfillmentService;
	@Autowired
	private PenaltyTargetService penaltyTargetService;
	@Autowired
	private JudgmentCorrectionService correctionService;

	@Test
	void 꾸준히_통과하는_회원은_6주_내내_경고가_없고_연속_통과는_두_주마다_0으로_돌아간다() {
		Person steady = settled("steady", team(outsider()));
		// 1·4주차는 잔디 없이 기록글만 서로 다른 3일에 쓴 주(기록으로 인증)
		plan(steady, "PRPPRP");
		int[] streakAfterWeek = {1, 0, 1, 0, 1, 0};

		for (int w = 0; w < 6; w++) {
			JudgmentSummary summary = mondayRun(w);

			assertThat(summary.total()).as("w%d 대상", w).isEqualTo(1);
			assertThat(summary.created()).as("w%d 새로 저장", w).isEqualTo(1);
			assertThat(summary.hold()).as("w%d 보류", w).isZero();
			assertThat(summary.failed()).as("w%d 예외", w).isZero();

			JudgmentRow row = row(steady, w);
			boolean recordOnlyWeek = w == 1 || w == 4;
			assertThat(row.status()).as("w%d 판정", w).isEqualTo("PASS");
			assertThat(row.verifiedDays()).as("w%d 인증일", w).isEqualTo(recordOnlyWeek ? 3 : 4);
			assertThat(row.recordCount()).as("w%d 기록글", w).isEqualTo(recordOnlyWeek ? 3 : 1);
			assertThat(row.judgedAt()).as("w%d 판정 시각", w).isEqualTo(week(w + 1).atTime(7, 0));
			assertThat(fixture.days(row.id())).as("w%d 일자별 근거", w).hasSize(7);
			// 06:00 갱신이 채운 캐시를 07:00 판정이 그대로 써서 하루 1회만 조회한다
			assertThat(callsOf(steady)).as("w%d 누적 GitHub 조회", w).isEqualTo(w + 1);
			assertState("w" + w + " 판정 뒤", steady, 0, streakAfterWeek[w]);
		}

		assertThat(fixture.warnings(steady.id())).isEmpty();
		// 첫 주(잔디 월~수 + 목요일 기록글)와 기록글만 쓴 주의 일자별 근거
		assertThat(fixture.days(row(steady, 0).id())).extracting(DayRow::hasGrass)
				.containsExactly(true, true, true, false, false, false, false);
		assertThat(fixture.days(row(steady, 0).id())).extracting(DayRow::hasRecord)
				.containsExactly(false, false, false, true, false, false, false);
		assertThat(fixture.days(row(steady, 1).id())).extracting(DayRow::hasGrass).containsOnly(false);
		assertThat(fixture.days(row(steady, 1).id())).extracting(DayRow::hasRecord)
				.containsExactly(true, false, true, false, true, false, false);
	}

	@Test
	void 미달이_쌓여_벌칙_대상이_되고_통과해도_차감되지_않다가_이행하면_0에서_다시_시작한다() {
		Person faller = settled("faller", team(outsider()));
		long adminId = admin();
		// 0~2주 미달(인증일 부족·기록글 없음·활동 없음) → 3주 미달로 벌칙 대상 → 3·4주 통과 → 5주 또 미달
		// → 이행 → 6주 통과, 7주 미달, 8·9주 통과
		plan(faller, "FG-PPF" + "PFPP");

		String[] status = {"FAIL", "FAIL", "FAIL", "PASS", "PASS", "FAIL"};
		int[] warnings = {1, 2, 3, 3, 3, 4};
		for (int w = 0; w < 6; w++) {
			mondayRun(w);

			assertStatus(faller, w, status[w]);
			Calculated state = state(faller);
			assertThat(state.warningCount()).as("w%d 경고 수", w).isEqualTo(warnings[w]);
			// 벌칙 대상에서는 통과해도 차감되지 않고 연속 통과도 쌓이지 않는다 (Q-10)
			assertThat(state.streak()).as("w%d 연속 통과", w).isZero();
			assertThat(state.penaltyTarget()).as("w%d 벌칙 대상", w).isEqualTo(warnings[w] >= 3);
			if (warnings[w] >= 3) {
				assertThat(state.penaltyReachedWeek()).as("w%d 3개 도달 주", w).isEqualTo(week(2));
			}
		}
		// 미달한 주마다 경고가 하나씩, 통과한 주에는 없다. 3개를 넘어도 계속 부여된다
		assertThat(warningWeeks(faller)).containsExactly(week(0), week(1), week(2), week(5));
		assertThat(penaltyTargetsOf(faller)).singleElement().satisfies(target -> {
			assertThat(target.warningCount()).isEqualTo(4);
			assertThat(target.reachedWeek()).isEqualTo(week(2));
		});

		// 6주차 화요일 오전에 이행 체크: 이 시각이 기준선이라 직전까지 끝난 주(0~5주차)는 더 이상 세지 않는다
		clockAt(at(6, 1, 10, 0));
		PenaltyFulfillment fulfillment = penaltyFulfillmentService.fulfill(adminId, faller.id());

		assertThat(fulfillment.getReachedWeek()).isEqualTo(week(2));
		assertThat(fulfillment.getFulfilledAt()).isEqualTo(at(6, 1, 10, 0));
		Calculated afterFulfillment = state(faller);
		assertThat(afterFulfillment.warningCount()).isZero();
		assertThat(afterFulfillment.streak()).isZero();
		assertThat(afterFulfillment.penaltyTarget()).isFalse();
		// 이행 이전에 받은 경고는 지워지지 않고 기록으로 남는다 (Q-13)
		assertThat(afterFulfillment.beforeBaselineWarningWeeks()).containsExactly(week(0), week(1), week(2), week(5));
		assertThat(aliveWarningWeeks(faller)).containsExactly(week(0), week(1), week(2), week(5));
		assertThat(penaltyTargetsOf(faller)).isEmpty();
		assertThat(jdbc.queryForObject("select count(*) from admin_action_log where admin_id = ? and action ="
				+ " 'FULFILL_PENALTY' and target_type = 'MEMBER' and target_id = ?", Integer.class, adminId,
				Long.toString(faller.id()))).isEqualTo(1);

		// 이행 이전 주차를 나중에 정정해도 기록만 바뀌고 이행 이후 경고 수에는 다시 반영되지 않는다 (E-58)
		JudgmentRow week1 = row(faller, 1);
		CorrectionPreview preview = correctionService.preview(Long.toString(week1.id()), JudgmentStatus.PASS);
		assertThat(preview.impact().recordOnly()).isTrue();
		clockAt(at(6, 1, 11, 0));
		CorrectedJudgment corrected = correctionService.correct(adminId,
				new JudgmentCorrectionInput(Long.toString(week1.id()), JudgmentStatus.PASS, "빈 커밋 확인", null));
		assertThat(corrected.status()).isEqualTo(JudgmentStatus.PASS);
		assertThat(corrected.warningCount()).isZero();
		assertThat(corrected.penaltyTarget()).isFalse();
		assertThat(row(faller, 1).corrected()).isTrue();
		assertState("이행 전 주차 정정 뒤", faller, 0, 0);

		// 이행 뒤 첫 주(이행이 일어난 6주차)부터 다시 센다: 통과 → 미달 → 통과 → 통과로 그 미달 경고가 차감된다
		mondayRun(6);
		assertStatus(faller, 6, "PASS");
		assertState("w6", faller, 0, 1);
		mondayRun(7);
		assertStatus(faller, 7, "FAIL");
		assertState("w7", faller, 1, 0);
		mondayRun(8);
		assertState("w8", faller, 1, 1);
		mondayRun(9);
		Calculated last = state(faller);
		assertThat(last.warningCount()).isZero();
		assertThat(last.streak()).isZero();
		assertThat(last.deductedWarningWeeks()).containsExactly(week(7));
		// 차감된 경고도 기록에서 지워지지 않는다
		assertThat(warningWeeks(faller)).containsExactly(week(0), week(1), week(2), week(5), week(7));
	}

	private List<Target> penaltyTargetsOf(Person person) {
		return penaltyTargetService.findTargets().stream().filter(target -> target.memberId() == person.id()).toList();
	}

}
