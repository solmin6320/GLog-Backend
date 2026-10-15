package com.jandilog.judgment.service;

import static com.jandilog.testsupport.judgment.JudgmentScenario.JUDGE_TIME;
import static com.jandilog.testsupport.judgment.JudgmentScenario.WEEK;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;

import com.jandilog.judgment.domain.HoldReason;
import com.jandilog.judgment.domain.JudgmentCalculator;
import com.jandilog.judgment.domain.JudgmentResult;
import com.jandilog.judgment.service.JudgmentRecordService.Outcome;
import com.jandilog.judgment.service.JudgmentRecordService.Type;
import com.jandilog.testsupport.judgment.JudgmentFixture.DayRow;
import com.jandilog.testsupport.judgment.JudgmentFixture.JudgmentRow;
import com.jandilog.testsupport.judgment.JudgmentFixture.WarningRow;
import com.jandilog.testsupport.judgment.JudgmentIntegrationTest;
import com.jandilog.testsupport.judgment.JudgmentScenario;

// 판정 계산 결과가 MariaDB에 저장되는 모양 (기능명세서 5장, DB명세서 1-6~1-8). 계산기를 거쳐 실제 테이블 행까지 확인한다.
// 시계는 2026-10-05(월) 07:00 KST, 판정 대상 주는 2026-09-28(월) ~ 10-04(일)
class JudgmentRecordServiceTest extends JudgmentIntegrationTest {

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

	private Outcome record(JudgmentScenario scenario) {
		JudgmentResult result = calculator.judge(scenario.build());
		return service.record(memberId, scenario.weekStart(), result, scenario.teams());
	}

	private JudgmentScenario scenario() {
		return JudgmentScenario.ofDefaultWeek().teams(teams);
	}

	private JudgmentRow saved() {
		return fixture.findJudgment(memberId, WEEK).orElseThrow();
	}

	// ---------- 통과 기준: 인증일 3일 이상 그리고 기록글 1개 이상 ----------

	@Test
	void 인증일_3일과_기록글_1개면_통과로_저장되고_경고가_없다() {
		Outcome outcome = record(scenario().grassOn(0, 1).recordsOn(2));

		assertThat(outcome.type()).isEqualTo(Type.CREATED);
		JudgmentRow row = saved();
		assertThat(row.status()).isEqualTo("PASS");
		assertThat(row.skipReason()).isNull();
		assertThat(row.holdReason()).isNull();
		assertThat(row.verifiedDays()).isEqualTo(3);
		assertThat(row.recordCount()).isEqualTo(1);
		assertThat(row.corrected()).isFalse();
		assertThat(row.judgedAt()).isEqualTo(JUDGE_TIME);
		assertThat(fixture.warnings(memberId)).isEmpty();
	}

	@Test
	void 잔디가_0칸이어도_기록글을_서로_다른_3일에_쓰면_통과한다() {
		record(scenario().recordsOn(0, 2, 4));

		JudgmentRow row = saved();
		assertThat(row.status()).isEqualTo("PASS");
		assertThat(row.verifiedDays()).isEqualTo(3);
		assertThat(row.recordCount()).isEqualTo(3);
		assertThat(fixture.warnings(memberId)).isEmpty();
	}

	@Test
	void 같은_날_잔디와_기록글이_함께_있으면_인증일은_1일로_저장된다() {
		// 월·화 잔디 + 월 기록글: 월요일은 둘 다여도 1일이라 인증일은 2일이고 미달이다
		record(scenario().grassOn(0, 1).recordsOn(0));

		JudgmentRow row = saved();
		assertThat(row.status()).isEqualTo("FAIL");
		assertThat(row.verifiedDays()).isEqualTo(2);
		assertThat(row.recordCount()).isEqualTo(1);
		DayRow monday = fixture.days(row.id()).get(0);
		assertThat(monday.hasGrass()).isTrue();
		assertThat(monday.hasRecord()).isTrue();
	}

	@Test
	void 같은_날_기록글이_여러_개여도_인증일은_1일이고_기록글_수는_합산된다() {
		record(scenario().grassOn(0, 1).postsOn(0, 3));

		JudgmentRow row = saved();
		assertThat(row.status()).isEqualTo("FAIL");
		assertThat(row.verifiedDays()).isEqualTo(2);
		assertThat(row.recordCount()).isEqualTo(3);
	}

	@Test
	void 인증일이_3일이어도_기록글이_0개면_미달로_저장된다() {
		record(scenario().grassOn(0, 1, 2));

		JudgmentRow row = saved();
		assertThat(row.status()).isEqualTo("FAIL");
		assertThat(row.verifiedDays()).isEqualTo(3);
		assertThat(row.recordCount()).isZero();
	}

	@Test
	void 기록글이_있어도_인증일이_2일이면_미달로_저장된다() {
		record(scenario().grassOn(0).recordsOn(1));

		JudgmentRow row = saved();
		assertThat(row.status()).isEqualTo("FAIL");
		assertThat(row.verifiedDays()).isEqualTo(2);
		assertThat(row.recordCount()).isEqualTo(1);
	}

	@Test
	void 일요일의_잔디와_기록글도_그_주_인증일로_저장된다() {
		record(scenario().grassOn(5, 6).recordsOn(6));

		JudgmentRow row = saved();
		assertThat(row.verifiedDays()).isEqualTo(2);
		DayRow sunday = fixture.days(row.id()).get(6);
		assertThat(sunday.day()).isEqualTo(LocalDate.of(2026, 10, 4));
		assertThat(sunday.hasGrass()).isTrue();
		assertThat(sunday.hasRecord()).isTrue();
	}

	// ---------- 일자별 근거 7행 ----------

	@Test
	void 확정된_판정은_월요일부터_일요일까지_일자별_근거_7행을_남긴다() {
		record(scenario().grassOn(0, 3).recordsOn(3, 5));

		List<DayRow> days = fixture.days(saved().id());
		assertThat(days).hasSize(7);
		assertThat(days).extracting(DayRow::day).containsExactly(WEEK, WEEK.plusDays(1), WEEK.plusDays(2),
				WEEK.plusDays(3), WEEK.plusDays(4), WEEK.plusDays(5), WEEK.plusDays(6));
		assertThat(days).extracting(DayRow::hasGrass).containsExactly(true, false, false, true, false, false, false);
		assertThat(days).extracting(DayRow::hasRecord).containsExactly(false, false, false, true, false, true, false);
	}

	// ---------- 소속 스냅샷과 여러 팀 ----------

	@Test
	void 소속_스냅샷은_judgment_team에_저장된다() {
		record(scenario().grassOn(0, 1, 2).recordsOn(0));

		assertThat(fixture.judgmentTeamIds(saved().id())).isEqualTo(teams);
	}

	@Test
	void 여러_팀에_속해도_판정은_한_번이고_통과면_경고가_없다() {
		Set<Long> three = fixture.teams(memberId, 3);

		record(scenario().teams(three).grassOn(0, 1, 2).recordsOn(0));

		assertThat(fixture.countJudgments(memberId)).isEqualTo(1);
		assertThat(fixture.judgmentTeamIds(saved().id())).isEqualTo(three);
		assertThat(fixture.warnings(memberId)).isEmpty();
	}

	@Test
	void 여러_팀에_속한_회원이_미달이면_경고는_1개이고_팀_전부가_카테고리에_붙는다() {
		Set<Long> three = fixture.teams(memberId, 3);

		record(scenario().teams(three));

		assertThat(fixture.countJudgments(memberId)).isEqualTo(1);
		assertThat(fixture.warnings(memberId)).hasSize(1);
		var warning = fixture.warnings(memberId).get(0);
		assertThat(warning.weekStart()).isEqualTo(WEEK);
		assertThat(warning.deletedAt()).isNull();
		assertThat(warning.createdAt()).isEqualTo(JUDGE_TIME);
		assertThat(fixture.warningTeamIds(warning.id())).isEqualTo(three);
		assertThat(fixture.countRemovedWarningTeams(warning.id())).isZero();
	}

	@Test
	void 통과한_주는_경고가_붙지_않고_미달한_주만_경고가_붙는다() {
		LocalDate nextWeek = WEEK.plusDays(7);
		clock.fixAt(JUDGE_TIME.plusDays(7).atZone(KST).toInstant());

		record(scenario().grassOn(0, 1, 2).recordsOn(0));
		record(JudgmentScenario.of(nextWeek).teams(teams));

		assertThat(fixture.countJudgments(memberId)).isEqualTo(2);
		assertThat(fixture.warnings(memberId)).extracting(WarningRow::weekStart).containsExactly(nextWeek);
	}

	// ---------- 판정 제외·면제: 판정하지 않고 경고도 없다 ----------

	@Test
	void 팀이_없으면_NO_TEAM으로_제외되고_경고도_일자별_근거도_없다() {
		record(JudgmentScenario.ofDefaultWeek());

		JudgmentRow row = saved();
		assertThat(row.status()).isEqualTo("EXCLUDED");
		assertThat(row.skipReason()).isEqualTo("NO_TEAM");
		assertThat(row.verifiedDays()).isNull();
		assertThat(row.recordCount()).isNull();
		assertThat(row.judgedAt()).isEqualTo(JUDGE_TIME);
		assertThat(fixture.judgmentTeamIds(row.id())).isEmpty();
		assertThat(fixture.days(row.id())).isEmpty();
		assertThat(fixture.warnings(memberId)).isEmpty();
	}

	@Test
	void 첫_참가_주는_FIRST_WEEK으로_제외되고_잔디가_0칸이어도_경고가_없다() {
		record(scenario().firstTeamJoinedAt(WEEK.plusDays(2).atTime(15, 30)));

		JudgmentRow row = saved();
		assertThat(row.status()).isEqualTo("EXCLUDED");
		assertThat(row.skipReason()).isEqualTo("FIRST_WEEK");
		assertThat(row.verifiedDays()).isNull();
		assertThat(fixture.judgmentTeamIds(row.id())).isEqualTo(teams);
		assertThat(fixture.days(row.id())).isEmpty();
		assertThat(fixture.warnings(memberId)).isEmpty();
	}

	@Test
	void 첫_참가_주보다_뒤의_주는_정상_판정한다() {
		record(scenario().firstTeamJoinedAt(WEEK.minusDays(3).atTime(10, 0)));

		assertThat(saved().status()).isEqualTo("FAIL");
		assertThat(fixture.warnings(memberId)).hasSize(1);
	}

	@Test
	void 면제_기간_주는_EXEMPT로_저장되고_경고가_없다() {
		record(scenario().exemptionPeriod());

		JudgmentRow row = saved();
		assertThat(row.status()).isEqualTo("EXEMPT");
		assertThat(row.skipReason()).isEqualTo("EXEMPTION_PERIOD");
		assertThat(row.verifiedDays()).isNull();
		assertThat(row.recordCount()).isNull();
		assertThat(fixture.days(row.id())).isEmpty();
		assertThat(fixture.warnings(memberId)).isEmpty();
	}

	@Test
	void 개인_면제_주는_EXEMPT로_저장되고_경고가_없다() {
		record(scenario().personalExemption());

		JudgmentRow row = saved();
		assertThat(row.status()).isEqualTo("EXEMPT");
		assertThat(row.skipReason()).isEqualTo("PERSONAL_EXEMPTION");
		assertThat(fixture.judgmentTeamIds(row.id())).isEqualTo(teams);
		assertThat(fixture.warnings(memberId)).isEmpty();
	}

	@Test
	void 면제_기간과_개인_면제가_겹쳐도_EXEMPT로_저장되고_경고가_없다() {
		record(scenario().exemptionPeriod().personalExemption());

		assertThat(saved().status()).isEqualTo("EXEMPT");
		assertThat(fixture.warnings(memberId)).isEmpty();
	}

	@Test
	void 제외_주는_잔디와_기록글이_충분해도_판정하지_않고_수치를_남기지_않는다() {
		record(scenario().exemptionPeriod().grassOn(0, 1, 2, 3).recordsOn(0, 1));

		JudgmentRow row = saved();
		assertThat(row.status()).isEqualTo("EXEMPT");
		assertThat(row.verifiedDays()).isNull();
		assertThat(row.recordCount()).isNull();
		assertThat(fixture.days(row.id())).isEmpty();
	}

	// ---------- 보류: 조회 실패는 그 회원만 보류, 경고 없음 ----------

	@ParameterizedTest
	@EnumSource(HoldReason.class)
	void 잔디_조회가_실패하면_보류로_저장되고_경고가_없다(HoldReason reason) {
		Outcome outcome = record(scenario().grassFailed(reason));

		assertThat(outcome.type()).isEqualTo(Type.CREATED);
		JudgmentRow row = saved();
		assertThat(row.status()).isEqualTo("HOLD");
		assertThat(row.holdReason()).isEqualTo(reason.name());
		assertThat(row.skipReason()).isNull();
		assertThat(row.verifiedDays()).isNull();
		assertThat(row.recordCount()).isNull();
		assertThat(row.judgedAt()).isNull();
		assertThat(row.retryCount()).isZero();
		assertThat(fixture.days(row.id())).isEmpty();
		assertThat(fixture.warnings(memberId)).isEmpty();
	}

	@Test
	void 보류는_소속_스냅샷을_남겨_재실행에서_같은_팀을_쓴다() {
		record(scenario().grassFailed(HoldReason.API_ERROR));

		assertThat(fixture.judgmentTeamIds(saved().id())).isEqualTo(teams);
	}

	@Test
	void 한_회원의_보류는_다른_회원의_판정에_영향을_주지_않는다() {
		long other = fixture.member();
		Set<Long> otherTeams = fixture.teams(other, 1);

		record(scenario().grassFailed(HoldReason.API_ERROR));
		JudgmentScenario otherScenario = JudgmentScenario.ofDefaultWeek().teams(otherTeams).grassOn(0, 1, 2)
				.recordsOn(0);
		service.record(other, WEEK, calculator.judge(otherScenario.build()), otherTeams);

		assertThat(saved().status()).isEqualTo("HOLD");
		assertThat(fixture.findJudgment(other, WEEK).orElseThrow().status()).isEqualTo("PASS");
	}

}
