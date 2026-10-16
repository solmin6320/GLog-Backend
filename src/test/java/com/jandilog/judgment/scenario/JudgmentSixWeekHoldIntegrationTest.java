package com.jandilog.judgment.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.jandilog.exemption.dto.ExemptionPeriodInput;
import com.jandilog.exemption.dto.RetroExemptionPreview;
import com.jandilog.exemption.service.ExemptionPeriodService;
import com.jandilog.judgment.domain.HoldReason;
import com.jandilog.judgment.service.JudgmentHoldService;
import com.jandilog.judgment.service.JudgmentHoldService.HoldItem;
import com.jandilog.judgment.service.JudgmentRecordService.Outcome;
import com.jandilog.judgment.service.JudgmentRecordService.Type;
import com.jandilog.judgment.service.WeeklyJudgmentBatchService.JudgmentSummary;
import com.jandilog.testsupport.judgment.JudgmentFixture.JudgmentRow;
import com.jandilog.testsupport.judgment.JudgmentPipelineIntegrationTest;
import com.jandilog.warning.domain.WarningRecalcResult.Calculated;

// 보류(GitHub 조회 실패) 주가 낀 판정 시나리오 (기능명세서 5장, Q-03, Q-04, Q-07, Q-09, E-35, E-37).
// 보류가 남아 있는 동안은 그 회원의 경고 재계산을 보류하고, 풀려 확정되면 그때 처음부터 다시 계산한다
class JudgmentSixWeekHoldIntegrationTest extends JudgmentPipelineIntegrationTest {

	@Autowired
	private JudgmentHoldService holdService;
	@Autowired
	private ExemptionPeriodService periodService;

	@Test
	void 자동_재시도로_보류가_풀리면_그_회원의_경고를_처음부터_다시_계산하고_다른_회원은_영향이_없다() {
		long teamId = team(outsider());
		Person retried = settled("retried", teamId);
		Person bystander = settled("bystander", teamId);
		plan(retried, "FPFPPP");
		plan(bystander, "PPPPPP");

		mondayRuns(0, 1);
		assertState("w1", retried, 1, 1);
		grass.failFor(login(retried), HoldReason.API_ERROR);

		// 2주차 판정 월요일: 잔디 갱신도 판정 조회도 실패해 그 회원만 보류, 경고 없음
		JudgmentSummary summary = mondayRun(2);

		assertThat(summary.total()).isEqualTo(2);
		assertThat(summary.hold()).isEqualTo(1);
		JudgmentRow hold = row(retried, 2);
		assertThat(hold.status()).isEqualTo("HOLD");
		assertThat(hold.holdReason()).isEqualTo("API_ERROR");
		assertThat(hold.retryCount()).isZero();
		assertThat(hold.verifiedDays()).isNull();
		assertThat(fixture.days(hold.id())).isEmpty();
		assertThat(fixture.judgmentTeamIds(hold.id())).containsExactly(teamId);
		assertThat(warningWeeks(retried)).containsExactly(week(0));
		assertThat(onHold(retried).holdWeeks()).containsExactly(week(2));
		// 다른 회원은 보류와 무관하게 확정되고 바로 계산된다
		assertStatus(bystander, 2, "PASS");
		assertState("w2", bystander, 0, 1);

		// 10분 뒤 재시도에서 GitHub가 살아 있으면 확정되고 그때 처음부터 다시 계산한다 (Q-03, Q-07)
		grass.recover(login(retried));
		retryTick(2, 10);

		JudgmentRow resolved = row(retried, 2);
		assertThat(resolved.id()).as("같은 행이 확정된다 (E-35)").isEqualTo(hold.id());
		assertThat(resolved.status()).isEqualTo("FAIL");
		assertThat(resolved.holdReason()).isNull();
		assertThat(fixture.days(resolved.id())).hasSize(7);
		assertThat(warningWeeks(retried)).containsExactly(week(0), week(2));
		assertThat(fixture.warningTeamIds(warningOf(retried, 2).id())).containsExactly(teamId);
		assertState("w2 재시도 확정 뒤", retried, 2, 0);
		assertThat(holdsOf(retried)).isEmpty();

		// 확정된 뒤의 재시도 틱은 아무것도 바꾸지 않는다
		String before = fingerprint(retried, bystander);
		retryTick(2, 20);
		assertThat(fingerprint(retried, bystander)).isEqualTo(before);

		mondayRun(3);
		assertState("w3", retried, 2, 1);
		mondayRun(4);
		// 2주 연속 통과: 가장 오래된 경고(0주차)가 차감된다
		Calculated afterDeduction = state(retried);
		assertThat(afterDeduction.warningCount()).isEqualTo(1);
		assertThat(afterDeduction.streak()).isZero();
		assertThat(afterDeduction.deductedWarningWeeks()).containsExactly(week(0));
		assertThat(afterDeduction.activeWarningWeeks()).containsExactly(week(2));
		mondayRun(5);
		assertState("w5", retried, 1, 1);

		assertThat(fixture.warnings(bystander.id())).isEmpty();
		assertState("w5", bystander, 0, 0);
	}

	@Test
	void 재시도가_소진된_보류가_남은_동안은_뒤_주가_확정돼도_재계산을_보류하고_관리자_재실행_뒤_처음부터_계산한다() {
		long teamA = team(outsider());
		long teamB = team(outsider());
		Person passer = settled("passer", teamA);
		Person failer = settled("failer", teamA);
		Person bystander = settled("bystander", teamA);
		// 2주차 입력은 GitHub가 살아난 뒤 관리자 재실행 때 쓰인다: passer는 통과, failer는 미달
		plan(passer, "FPPPPP");
		plan(failer, "FPFPFP");
		plan(bystander, "PPPPPP");

		mondayRuns(0, 1);
		assertState("w1", passer, 1, 1);
		assertState("w1", failer, 1, 1);
		grass.failFor(login(passer), HoldReason.API_ERROR);
		grass.failFor(login(failer), HoldReason.API_ERROR);

		mondayRun(2);

		for (Person person : List.of(passer, failer)) {
			assertStatus(person, 2, "HOLD");
			assertThat(onHold(person).holdWeeks()).as("%s 보류 주", person).containsExactly(week(2));
			assertThat(warningWeeks(person)).as("%s 경고", person).containsExactly(week(0));
		}

		// 10분 간격 3회 재시도가 모두 실패하면 횟수가 소진된다. 네 번째 틱은 더 부르지 않는다 (Q-07)
		for (int i = 1; i <= 3; i++) {
			retryTick(2, i * 10);
			assertThat(row(passer, 2).retryCount()).as("%d번째 재시도 뒤 횟수", i).isEqualTo(i);
			assertThat(row(failer, 2).retryCount()).isEqualTo(i);
			assertThat(row(passer, 2).status()).isEqualTo("HOLD");
		}
		long passerCalls = callsOf(passer);
		long failerCalls = callsOf(failer);
		retryTick(2, 40);
		assertThat(row(passer, 2).retryCount()).isEqualTo(3);
		assertThat(callsOf(passer)).as("소진된 보류는 더 조회하지 않는다").isEqualTo(passerCalls);
		assertThat(callsOf(failer)).isEqualTo(failerCalls);

		// 소진된 보류는 관리자 보류 목록에 오른다
		assertThat(holdsOf(passer, failer, bystander)).extracting(HoldItem::memberId, HoldItem::weekStart,
				HoldItem::reason, HoldItem::retryCount, HoldItem::retriesExhausted)
				.containsExactlyInAnyOrder(tuple(passer.id(), week(2), HoldReason.API_ERROR, 3, true),
						tuple(failer.id(), week(2), HoldReason.API_ERROR, 3, true));

		// GitHub는 살아났지만 보류 주가 남은 채 3주차가 확정된다: 알 수 없는 2주차를 건너뛰거나 끊은 값을 내지 않는다
		grass.recover(login(passer));
		grass.recover(login(failer));
		// 보류 중 팀을 하나 더 만들어도 2주차 소속 스냅샷은 처음 저장한 그대로다 (Q-04, E-37)
		join(failer, teamB, at(3, 1, 9, 0));
		mondayRun(3);

		for (Person person : List.of(passer, failer)) {
			assertStatus(person, 3, "PASS");
			assertThat(onHold(person).holdWeeks()).as("%s 3주차 확정 뒤에도 보류", person).containsExactly(week(2));
			assertThat(warningWeeks(person)).as("%s 경고", person).containsExactly(week(0));
			assertThat(row(person, 2).status()).isEqualTo("HOLD");
		}
		long passerCallsAfter = callsOf(passer);
		retryTick(3, 10);
		assertThat(row(passer, 2).status()).as("소진된 보류는 GitHub가 살아나도 자동으로 풀리지 않는다").isEqualTo("HOLD");
		assertThat(callsOf(passer) - passerCallsAfter).isZero();

		// 관리자 재실행: 확정되면 그때 처음부터 다시 계산한다
		clockAt(at(4, 1, 10, 0));
		Outcome passerOutcome = holdService.rerunOne(passer.id(), week(2));
		Outcome failerOutcome = holdService.rerunOne(failer.id(), week(2));

		assertThat(passerOutcome.type()).isEqualTo(Type.UPDATED);
		assertThat(failerOutcome.type()).isEqualTo(Type.UPDATED);
		assertThat(holdsOf(passer, failer)).isEmpty();
		assertStatus(passer, 2, "PASS");
		assertStatus(failer, 2, "FAIL");
		// passer: 0주 미달, 1·2주 통과로 차감, 3주 통과 → 경고 0, 연속 1
		assertState("w2 재실행 뒤", passer, 0, 1);
		// failer: 0주·2주 미달, 3주 통과 → 경고 2, 연속 1
		assertState("w2 재실행 뒤", failer, 2, 1);
		assertThat(fixture.judgmentTeamIds(row(failer, 2).id())).containsExactly(teamA);
		assertThat(fixture.warningTeamIds(warningOf(failer, 2).id())).as("보류 때 박아둔 소속").containsExactly(teamA);
		// 확정된 뒤 같은 주를 한 번 더 돌려도 멱등하다 (E-35)
		String before = fingerprint(passer, failer);
		assertThat(holdService.rerunOne(passer.id(), week(2)).type()).isEqualTo(Type.SKIPPED_CONFIRMED);
		assertThat(fingerprint(passer, failer)).isEqualTo(before);

		mondayRun(4);
		assertState("w4", passer, 0, 0);
		Calculated failerAtPenalty = state(failer);
		assertThat(failerAtPenalty.warningCount()).isEqualTo(3);
		assertThat(failerAtPenalty.penaltyTarget()).isTrue();
		assertThat(failerAtPenalty.penaltyReachedWeek()).isEqualTo(week(4));
		// 4주차 경고는 판정 시점 소속 팀 전부를 카테고리로 갖는다
		assertThat(fixture.warningTeamIds(warningOf(failer, 4).id())).containsExactlyInAnyOrder(teamA, teamB);
		mondayRun(5);
		assertState("w5", passer, 0, 1);
		assertState("w5", failer, 3, 0);
		assertThat(fixture.warnings(bystander.id())).isEmpty();
		assertState("w5", bystander, 0, 0);
	}

	@Test
	void GitHub_아이디가_바뀐_회원의_보류는_자동_재시도_없이_바로_수동_목록에_오르고_재로그인_뒤에야_풀린다() {
		long teamId = team(outsider());
		Person renamed = settled("renamed", teamId);
		Person bystander = settled("bystander", teamId);
		plan(renamed, "FPPFPP");
		plan(bystander, "PPPPPP");
		String oldLogin = login(renamed);

		mondayRuns(0, 1);
		assertState("w1", renamed, 1, 1);
		// 회원이 GitHub 아이디를 바꿔 이전 아이디는 "사용자 없음"
		grass.failFor(oldLogin, HoldReason.IDENTITY_MISMATCH);

		mondayRun(2);

		JudgmentRow hold = row(renamed, 2);
		assertThat(hold.status()).isEqualTo("HOLD");
		assertThat(hold.holdReason()).isEqualTo("IDENTITY_MISMATCH");
		assertThat(hold.retryCount()).isZero();
		assertThat(onHold(renamed).holdWeeks()).containsExactly(week(2));
		// 재시도 횟수를 기다리지 않고 바로 관리자 보류 목록에 오른다 (Q-09)
		assertThat(holdsOf(renamed)).singleElement().satisfies(item -> {
			assertThat(item.weekStart()).isEqualTo(week(2));
			assertThat(item.reason()).isEqualTo(HoldReason.IDENTITY_MISMATCH);
			assertThat(item.retryCount()).isZero();
			assertThat(item.retriesExhausted()).isFalse();
		});

		// 자동 재시도 틱은 아이디 불일치 건을 건드리지 않는다
		long calls = callsOf(renamed);
		for (int minute = 10; minute <= 40; minute += 10) {
			retryTick(2, minute);
		}
		assertThat(callsOf(renamed)).as("재시도 없이 조회하지 않는다").isEqualTo(calls);
		assertThat(row(renamed, 2).retryCount()).isZero();

		mondayRun(3);
		assertThat(row(renamed, 3).status()).isEqualTo("HOLD");
		assertThat(onHold(renamed).holdWeeks()).containsExactly(week(2), week(3));
		assertState("w3 다른 회원", bystander, 0, 0);

		// 재로그인 전에 관리자가 재시도를 눌러도 소용없다
		clockAt(at(4, 1, 10, 0));
		assertThat(holdService.rerunOne(renamed.id(), week(2)).judgment().getStatus().name()).isEqualTo("HOLD");
		assertThat(row(renamed, 2).holdReason()).isEqualTo("IDENTITY_MISMATCH");
		assertThat(row(renamed, 2).retryCount()).isZero();
		assertThat(onHold(renamed).holdWeeks()).containsExactly(week(2), week(3));

		// 재로그인으로 GitHub 아이디가 새로 저장되면 재실행이 통한다
		String newLogin = oldLogin + "-n";
		jdbc.update("update member set github_login = ? where id = ?", newLogin, renamed.id());
		grass.copyData(oldLogin, newLogin);
		clockAt(at(4, 2, 10, 0));
		holdService.rerunOne(renamed.id(), week(2));

		assertStatus(renamed, 2, "PASS");
		// 3주차 보류가 남아 있어 아직 계산하지 않는다
		assertThat(onHold(renamed).holdWeeks()).containsExactly(week(3));
		holdService.rerunOne(renamed.id(), week(3));

		assertStatus(renamed, 3, "FAIL");
		assertThat(holdsOf(renamed)).isEmpty();
		// 0주 미달, 1·2주 통과로 차감, 3주 미달
		assertState("w3 재로그인 뒤", renamed, 1, 0);

		mondayRun(4);
		assertState("w4", renamed, 1, 1);
		mondayRun(5);
		Calculated last = state(renamed);
		assertThat(last.warningCount()).isZero();
		assertThat(last.deductedWarningWeeks()).containsExactly(week(0), week(3));
		assertState("w5 다른 회원", bystander, 0, 0);
		assertThat(fixture.warnings(bystander.id())).isEmpty();
	}

	@Test
	void 보류된_주에_면제_기간이_등록되면_GitHub가_죽어_있어도_재실행에서_면제로_확정되고_이미_판정된_회원은_소급_면제된다() {
		long teamId = team(outsider());
		long adminId = admin();
		Person held = settled("held", teamId);
		Person bystander = settled("bystander", teamId);
		plan(held, "FP-PPP");
		plan(bystander, "PPPPPP");

		mondayRuns(0, 1);
		assertState("w1", held, 1, 1);
		grass.failFor(login(held), HoldReason.API_ERROR);
		mondayRun(2);
		for (int minute = 10; minute <= 30; minute += 10) {
			retryTick(2, minute);
		}
		assertStatus(held, 2, "HOLD");
		assertThat(row(held, 2).retryCount()).isEqualTo(3);
		assertStatus(bystander, 2, "PASS");
		assertThat(holdsOf(held)).hasSize(1);

		// 3주차 화요일에 2주차를 전체 면제 기간으로 등록한다: 이미 통과로 판정된 회원은 소급되고 보류 건은 확정 전이라 바뀌지 않는다
		clockAt(at(3, 1, 10, 0));
		RetroExemptionPreview preview = periodService.preview(week(2).toString());
		assertThat(preview.items()).extracting(item -> item.member().id()).containsExactly(bystander.id());
		periodService.create(adminId, new ExemptionPeriodInput(week(2).toString(), "방학"));

		assertStatus(bystander, 2, "EXEMPT");
		assertStatus(held, 2, "HOLD");
		assertThat(onHold(held).holdWeeks()).containsExactly(week(2));

		// GitHub가 아직 죽어 있어도 면제 주는 조회하지 않으므로 재실행이 보류를 면제로 확정한다
		long calls = callsOf(held);
		Outcome outcome = holdService.rerunOne(held.id(), week(2));

		assertThat(outcome.type()).isEqualTo(Type.UPDATED);
		assertThat(callsOf(held)).isEqualTo(calls);
		assertStatus(held, 2, "EXEMPT");
		assertThat(row(held, 2).skipReason()).isEqualTo("EXEMPTION_PERIOD");
		assertThat(row(held, 2).holdReason()).isNull();
		assertThat(holdsOf(held)).isEmpty();
		assertThat(warningWeeks(held)).containsExactly(week(0));
		assertState("w2 면제 확정 뒤", held, 1, 1);

		grass.recover(login(held));
		mondayRun(3);
		// 면제 주를 사이에 두고 1주·3주 통과 → 0주 경고 차감
		Calculated afterDeduction = state(held);
		assertThat(afterDeduction.warningCount()).isZero();
		assertThat(afterDeduction.deductedWarningWeeks()).containsExactly(week(0));
		assertState("w3", bystander, 0, 1);
		mondayRun(4);
		assertState("w4", held, 0, 1);
		assertState("w4", bystander, 0, 0);
		mondayRun(5);
		assertState("w5", held, 0, 0);
		assertState("w5", bystander, 0, 1);
	}

	// 관리자 보류 목록에서 주어진 회원들의 항목만 (공유 DB의 다른 보류는 제외)
	private List<HoldItem> holdsOf(Person... persons) {
		Set<Long> ids = Arrays.stream(persons).map(Person::id).collect(Collectors.toSet());
		return holdService.listManualHolds().stream().filter(item -> ids.contains(item.memberId())).toList();
	}

}
