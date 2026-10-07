package com.jandilog.judgment.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.jandilog.exemption.dto.CorrectedJudgment;
import com.jandilog.exemption.dto.CorrectionPreview;
import com.jandilog.exemption.dto.ExemptionPeriodInput;
import com.jandilog.exemption.dto.JudgmentCorrectionInput;
import com.jandilog.exemption.dto.PersonalExemptionResponse;
import com.jandilog.exemption.dto.RetroExemptionImpact;
import com.jandilog.exemption.dto.RetroExemptionPreview;
import com.jandilog.exemption.service.ExemptionPeriodService;
import com.jandilog.exemption.service.JudgmentCorrectionService;
import com.jandilog.exemption.service.PersonalExemptionService;
import com.jandilog.judgment.domain.HoldReason;
import com.jandilog.judgment.domain.JudgmentStatus;
import com.jandilog.judgment.service.WeeklyJudgmentBatchService.JudgmentSummary;
import com.jandilog.testsupport.judgment.JudgmentFixture.JudgmentRow;
import com.jandilog.testsupport.judgment.JudgmentPipelineIntegrationTest;
import com.jandilog.warning.domain.WarningRecalcResult.Calculated;

// 면제(기간·개인)와 판정 정정이 낀 판정 시나리오 (기능명세서 5·6·7장, Q-05, E-44, E-45).
// 면제 주는 연속 통과를 끊지도 늘리지도 않고, 정정·소급 면제는 이후 주까지 포함해 처음부터 다시 계산한다
class JudgmentSixWeekExemptionCorrectionIntegrationTest extends JudgmentPipelineIntegrationTest {

	@Autowired
	private ExemptionPeriodService periodService;
	@Autowired
	private PersonalExemptionService personalExemptionService;
	@Autowired
	private JudgmentCorrectionService correctionService;

	private Person captain;
	private long teamId;
	private long adminId;

	// 팀장이 이끄는 팀 하나와 관리자. 팀장 본인도 판정 대상이다
	private void setUpTeam() {
		captain = person("captain", LONG_AGO);
		teamId = team(captain.id());
		join(captain, teamId, LONG_AGO);
		adminId = admin();
	}

	private long requestAndApprovePersonal(Person target, int week) {
		PersonalExemptionResponse requested = personalExemptionService.request(captain.id(), Long.toString(teamId),
				Long.toString(target.id()), week(week).toString(), "개인 사정");
		personalExemptionService.approve(Long.toString(requested.id()));
		return requested.id();
	}

	@Test
	void 면제_기간과_승인된_개인_면제_주는_판정하지_않고_연속_통과를_끊지도_늘리지도_않는다() {
		setUpTeam();
		Person member = settled("member", teamId);
		Person both = settled("both", teamId);
		// 2주차는 전체 면제 기간, member의 4주차는 개인 면제, both는 2주차에 둘 다 걸린다 (E-44)
		plan(captain, "PP-PPP");
		plan(member, "PF-P-P");
		plan(both, "PF-PPP");

		clockAt(at(1, 3, 12, 0));
		periodService.create(adminId, new ExemptionPeriodInput(week(2).toString(), "시험 기간"));
		requestAndApprovePersonal(member, 4);
		requestAndApprovePersonal(both, 2);

		mondayRun(0);
		assertState("w0", member, 0, 1);
		mondayRun(1);
		assertState("w1", member, 1, 0);
		assertState("w1", both, 1, 0);

		// 면제 주에는 GitHub가 죽어 있어도 조회하지 않으므로 보류되지 않는다
		grass.failFor(login(member), HoldReason.API_ERROR);
		grass.failFor(login(both), HoldReason.API_ERROR);
		JudgmentSummary week2 = mondayRun(2);

		assertThat(week2.total()).isEqualTo(3);
		assertThat(week2.hold()).isZero();
		for (Person person : List.of(captain, member, both)) {
			JudgmentRow row = row(person, 2);
			assertThat(row.status()).as("%s 2주차", person).isEqualTo("EXEMPT");
			assertThat(row.verifiedDays()).isNull();
			assertThat(row.recordCount()).isNull();
			assertThat(fixture.days(row.id())).isEmpty();
		}
		// 면제 기간은 전체 회원에게 적용되고, 둘이 겹치면 두 면제 기록이 모두 남는다
		assertThat(row(captain, 2).skipReason()).isEqualTo("EXEMPTION_PERIOD");
		assertThat(row(member, 2).skipReason()).isEqualTo("EXEMPTION_PERIOD");
		assertThat(row(both, 2).skipReason()).isIn("EXEMPTION_PERIOD", "PERSONAL_EXEMPTION");
		assertThat(jdbc.queryForObject("select count(*) from personal_exemption where member_id = ? and week_start = ?"
				+ " and status = 'APPROVED'", Integer.class, both.id(), week(2))).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from exemption_period where week_start = ?", Integer.class,
				week(2))).isEqualTo(1);
		// 면제 주는 경고를 지우지도 새로 주지도 않고 연속 통과도 건드리지 않는다
		assertState("w2", member, 1, 0);
		assertState("w2", both, 1, 0);
		assertState("w2", captain, 0, 0);

		grass.recover(login(member));
		grass.recover(login(both));
		mondayRun(3);
		assertState("w3", member, 1, 1);
		assertState("w3", both, 1, 1);
		assertState("w3", captain, 0, 1);

		// 4주차: member는 개인 면제라 잔디를 조회하지 않는다
		grass.failFor(login(member), HoldReason.API_ERROR);
		JudgmentSummary week4 = mondayRun(4);
		assertThat(week4.hold()).isZero();
		assertThat(row(member, 4).status()).isEqualTo("EXEMPT");
		assertThat(row(member, 4).skipReason()).isEqualTo("PERSONAL_EXEMPTION");
		// 면제 주를 사이에 둔 3주·5주 통과가 연속 두 주로 이어져 가장 오래된 경고가 차감된다
		assertState("w4", member, 1, 1);
		assertState("w4", both, 0, 0);

		grass.recover(login(member));
		mondayRun(5);
		Calculated member5 = state(member);
		assertThat(member5.warningCount()).isZero();
		assertThat(member5.streak()).isZero();
		assertThat(member5.deductedWarningWeeks()).containsExactly(week(1));
		assertState("w5", both, 0, 1);
		assertState("w5", captain, 0, 1);
		// 면제된 주를 빼면 member의 경고는 1주차 미달 하나뿐이다
		assertThat(warningWeeks(member)).containsExactly(week(1));
	}

	@Test
	void 이미_판정된_주에_면제를_소급하면_이후_통과까지_포함해_처음부터_다시_계산한다() {
		setUpTeam();
		Person periodTarget = settled("periodTarget", teamId);
		Person personalTarget = settled("personalTarget", teamId);
		// 0~2주 미달로 3개 → 벌칙 대상, 3~5주 통과는 벌칙 대상이라 차감되지 않았다. 6주차 미달
		plan(periodTarget, "FFFPPPF");
		plan(personalTarget, "FFFPPPF");
		plan(captain, "PPPPPPP");

		mondayRuns(0, 5);
		for (Person person : List.of(periodTarget, personalTarget)) {
			Calculated state = state(person);
			assertThat(state.warningCount()).as("%s 소급 전 경고 수", person).isEqualTo(3);
			assertThat(state.penaltyTarget()).isTrue();
			assertThat(state.streak()).isZero();
		}
		assertState("소급 전", captain, 0, 0);
		clockAt(at(6, 1, 10, 0));

		// 개인 면제 승인 미리보기는 계산만 하고 아무것도 바꾸지 않는다
		PersonalExemptionResponse request = personalExemptionService.request(captain.id(), Long.toString(teamId),
				Long.toString(personalTarget.id()), week(1).toString(), "개인 사정");
		String beforePreview = fingerprint(captain, periodTarget, personalTarget);
		RetroExemptionPreview personalPreview = personalExemptionService.previewApproval(Long.toString(request.id()));
		assertThat(fingerprint(captain, periodTarget, personalTarget)).isEqualTo(beforePreview);
		assertThat(personalPreview.retroactive()).isTrue();
		assertThat(personalPreview.items()).singleElement().satisfies(item -> {
			assertThat(item.member().id()).isEqualTo(personalTarget.id());
			assertThat(item.currentStatus()).isEqualTo(JudgmentStatus.FAIL);
			assertThat(item.impact().warningCountBefore()).isEqualTo(3);
			assertThat(item.impact().warningCountAfter()).isEqualTo(1);
			assertThat(item.impact().leavesPenalty()).isTrue();
		});

		personalExemptionService.approve(Long.toString(request.id()));

		assertThat(row(personalTarget, 1).status()).isEqualTo("EXEMPT");
		assertThat(row(personalTarget, 1).skipReason()).isEqualTo("PERSONAL_EXEMPTION");
		// 0주 미달, 1주 면제, 2주 미달, 3·4주 통과로 0주 경고 차감, 5주 통과 → 경고 1, 연속 1
		assertState("개인 면제 소급 뒤", personalTarget, 1, 1);
		assertState("개인 면제 소급 뒤 다른 회원", periodTarget, 3, 0);

		// 면제 기간 등록 미리보기: 그 주에 통과·미달인 회원만 나온다(이미 면제인 회원은 제외)
		String beforePeriodPreview = fingerprint(captain, periodTarget, personalTarget);
		RetroExemptionPreview periodPreview = periodService.preview(week(1).toString());
		assertThat(fingerprint(captain, periodTarget, personalTarget)).isEqualTo(beforePeriodPreview);
		assertThat(periodPreview.retroactive()).isTrue();
		assertThat(periodPreview.items()).extracting(item -> item.member().id())
				.containsExactlyInAnyOrder(captain.id(), periodTarget.id());
		RetroExemptionImpact periodTargetImpact = periodPreview.items().stream()
				.filter(item -> item.member().id() == periodTarget.id()).findFirst().orElseThrow();
		assertThat(periodTargetImpact.impact().warningCountBefore()).isEqualTo(3);
		assertThat(periodTargetImpact.impact().warningCountAfter()).isEqualTo(1);
		assertThat(periodTargetImpact.impact().leavesPenalty()).isTrue();

		periodService.create(adminId, new ExemptionPeriodInput(week(1).toString(), "전체 면제"));

		assertThat(row(periodTarget, 1).status()).isEqualTo("EXEMPT");
		assertThat(row(periodTarget, 1).skipReason()).isEqualTo("EXEMPTION_PERIOD");
		assertThat(row(captain, 1).status()).as("통과였던 주도 전체 면제로 바뀐다").isEqualTo("EXEMPT");
		assertThat(row(personalTarget, 1).skipReason()).as("이미 면제인 주는 그대로").isEqualTo("PERSONAL_EXEMPTION");
		assertState("면제 기간 소급 뒤", periodTarget, 1, 1);
		assertState("면제 기간 소급 뒤", personalTarget, 1, 1);
		// 팀장: 0주 통과(1), 1주 면제(건너뜀), 2주 통과로 두 주 연속, 3주 통과(1), 4주 통과(0), 5주 통과(1)
		assertState("면제 기간 소급 뒤", captain, 0, 1);

		// 소급 뒤에도 판정은 이어진다: 6주차 미달은 새 경고로 쌓인다
		mondayRun(6);
		for (Person person : List.of(periodTarget, personalTarget)) {
			Calculated state = state(person);
			assertThat(state.warningCount()).as("%s 6주차 뒤 경고 수", person).isEqualTo(2);
			assertThat(state.activeWarningWeeks()).containsExactly(week(2), week(6));
			assertThat(state.deductedWarningWeeks()).containsExactly(week(0));
			assertThat(state.penaltyTarget()).isFalse();
		}
		assertState("w6", captain, 0, 0);
	}

	@Test
	void 미달을_통과로_정정하면_벌칙_대상에서_빠지고_이후_주_판정이_정정된_기록_위에서_이어진다() {
		adminId = admin();
		Person fixed = settled("fixed", team(outsider()));
		plan(fixed, "FPPFFFPP");
		int[][] expected = {{1, 0}, {1, 1}, {0, 0}, {1, 0}, {2, 0}, {3, 0}};

		for (int w = 0; w < 6; w++) {
			mondayRun(w);
			assertState("w" + w, fixed, expected[w][0], expected[w][1]);
		}
		assertThat(state(fixed).penaltyReachedWeek()).isEqualTo(week(5));

		clockAt(at(6, 1, 10, 0));
		long week4 = row(fixed, 4).id();
		String beforePreview = fingerprint(fixed);
		CorrectionPreview preview = correctionService.preview(Long.toString(week4), JudgmentStatus.PASS);
		assertThat(fingerprint(fixed)).as("미리보기는 아무것도 바꾸지 않는다").isEqualTo(beforePreview);
		assertThat(preview.currentStatus()).isEqualTo(JudgmentStatus.FAIL);
		assertThat(preview.impact().warningCountBefore()).isEqualTo(3);
		assertThat(preview.impact().warningCountAfter()).isEqualTo(2);
		assertThat(preview.impact().leavesPenalty()).isTrue();
		assertThat(preview.impact().recordOnly()).isFalse();

		CorrectedJudgment corrected = correctionService.correct(adminId,
				new JudgmentCorrectionInput(Long.toString(week4), JudgmentStatus.PASS, "내용이 충분한 글", JudgmentStatus.FAIL));

		assertThat(corrected.status()).isEqualTo(JudgmentStatus.PASS);
		assertThat(corrected.onHold()).isFalse();
		assertThat(corrected.warningCount()).isEqualTo(2);
		assertThat(corrected.penaltyTarget()).isFalse();
		assertThat(row(fixed, 4).status()).isEqualTo("PASS");
		assertThat(row(fixed, 4).corrected()).isTrue();
		assertThat(jdbc.queryForObject("select count(*) from judgment_correction where judgment_id = ?", Integer.class,
				week4)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from admin_action_log where admin_id = ? and action ="
				+ " 'CORRECT_JUDGMENT' and target_id = ?", Integer.class, adminId, Long.toString(week4))).isEqualTo(1);
		// 0주 미달, 1·2주 통과로 차감, 3주 미달, 4주 통과(정정), 5주 미달
		assertState("정정 뒤", fixed, 2, 0);

		mondayRun(6);
		assertState("w6", fixed, 2, 1);
		mondayRun(7);
		Calculated last = state(fixed);
		assertThat(last.warningCount()).isEqualTo(1);
		assertThat(last.streak()).isZero();
		assertThat(last.deductedWarningWeeks()).containsExactly(week(0), week(3));
		assertThat(last.activeWarningWeeks()).containsExactly(week(5));
	}

	@Test
	void 통과를_미달로_정정하면_경고가_새로_생기고_소속_스냅샷이_카테고리가_되며_이후_통과로_차감된다() {
		adminId = admin();
		long teamA = team(outsider());
		long teamB = team(outsider());
		Person fixed = settled("fixed", teamA, teamB);
		plan(fixed, "PPPPPPPP");

		mondayRuns(0, 5);
		assertState("정정 전", fixed, 0, 0);
		assertThat(fixture.warnings(fixed.id())).isEmpty();

		clockAt(at(6, 1, 10, 0));
		long week5 = row(fixed, 5).id();
		CorrectionPreview preview = correctionService.preview(Long.toString(week5), JudgmentStatus.FAIL);
		assertThat(preview.impact().warningCountBefore()).isZero();
		assertThat(preview.impact().warningCountAfter()).isEqualTo(1);
		assertThat(preview.impact().entersPenalty()).isFalse();
		assertThat(fixture.warnings(fixed.id())).as("미리보기는 경고를 만들지 않는다").isEmpty();

		CorrectedJudgment corrected = correctionService.correct(adminId,
				new JudgmentCorrectionInput(Long.toString(week5), JudgmentStatus.FAIL, "빈 커밋만 있는 주", null));

		assertThat(corrected.status()).isEqualTo(JudgmentStatus.FAIL);
		assertThat(corrected.warningCount()).isEqualTo(1);
		assertThat(corrected.penaltyTarget()).isFalse();
		JudgmentRow row = row(fixed, 5);
		assertThat(row.status()).isEqualTo("FAIL");
		assertThat(row.corrected()).isTrue();
		// 정정으로 생긴 경고는 판정 때 박아둔 소속 스냅샷을 카테고리로 갖는다
		assertThat(warningWeeks(fixed)).containsExactly(week(5));
		assertThat(fixture.warningTeamIds(warningOf(fixed, 5).id())).containsExactlyInAnyOrder(teamA, teamB);
		assertState("정정 뒤", fixed, 1, 0);

		mondayRun(6);
		assertState("w6", fixed, 1, 1);
		mondayRun(7);
		Calculated last = state(fixed);
		assertThat(last.warningCount()).isZero();
		assertThat(last.deductedWarningWeeks()).containsExactly(week(5));
	}

}
