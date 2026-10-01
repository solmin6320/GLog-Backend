package com.jandilog.warning.service;

import static com.jandilog.testsupport.warning.RecalcAssert.assertActive;
import static com.jandilog.testsupport.warning.RecalcAssert.assertBeforeBaseline;
import static com.jandilog.testsupport.warning.RecalcAssert.assertDeducted;
import static com.jandilog.testsupport.warning.RecalcAssert.assertSameNumbers;
import static com.jandilog.testsupport.warning.RecalcAssert.assertState;
import static com.jandilog.testsupport.warning.RecalcAssert.calculated;
import static com.jandilog.testsupport.warning.RecalcAssert.holdWeeks;
import static com.jandilog.testsupport.warning.WeekScript.endOf;
import static com.jandilog.testsupport.warning.WeekScript.parse;
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
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.IllegalTransactionStateException;

import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.judgment.domain.JudgmentStatus;
import com.jandilog.testsupport.warning.WarningIntegrationTest;
import com.jandilog.warning.domain.WarningRecalcPreview;
import com.jandilog.warning.domain.WarningRecalcResult;
import com.jandilog.warning.domain.WarningRecalculator;

// 판정 이력·경고·이행 기준선을 DB에서 읽어 처음부터 다시 계산한다 (DB명세서 4-3). 공유 compose MariaDB를 쓴다
class WarningRecalculationServiceIntegrationTest extends WarningIntegrationTest {

	@Test
	void 판정_이력이_없는_회원은_경고_0_연속_0이다() {
		long member = fixture.member();
		assertState(recalculation.recalculate(member), 0, 0);
		assertState(recalculation.calculate(member), 0, 0);
	}

	@Test
	void DB의_판정_이력으로_경고와_연속을_처음부터_계산한다() {
		long member = fixture.member();
		fixture.history(member, "FFPPFPP");

		var result = assertState(recalculation.recalculate(member), 1, 0);
		assertActive(result, 4);
		assertDeducted(result, 0, 1);
	}

	@Test
	void 미달_3개는_벌칙_대상이고_이후_통과는_차감하지_않는다() {
		long member = fixture.member();
		fixture.history(member, "FFFPP");

		var result = assertState(recalculation.recalculate(member), 3, 0);
		assertThat(result.deductedWarningWeeks()).isEmpty();
	}

	@Test
	void 벌칙_대상에서_또_미달하면_3을_넘는다() {
		long member = fixture.member();
		fixture.history(member, "FFFFF");

		assertState(recalculation.recalculate(member), 5, 0);
	}

	@Test
	void 면제와_제외_주는_연속을_끊지도_늘리지도_않는다() {
		long member = fixture.member();
		fixture.history(member, "FPEXP");

		var result = assertState(recalculation.recalculate(member), 0, 0);
		assertDeducted(result, 0);
	}

	@Test
	void 경고_0개에서_2주_연속_통과하면_연속이_0으로_돌아간다() {
		long member = fixture.member();
		fixture.history(member, "PPP");

		assertState(recalculation.recalculate(member), 0, 1);

		long other = fixture.member();
		fixture.history(other, "PP");
		assertState(recalculation.recalculate(other), 0, 0);
	}

	@Test
	void 회원마다_자기_이력만_계산한다() {
		long a = fixture.member();
		long b = fixture.member();
		fixture.history(a, "FFF");
		fixture.history(b, "PP");

		assertState(recalculation.recalculate(a), 3, 0);
		assertState(recalculation.recalculate(b), 0, 0);
	}

	@Test
	void recalculate와_calculate는_같은_값을_돌려준다() {
		long member = fixture.member();
		fixture.history(member, "FPFPPF");

		assertThat(recalculation.recalculate(member)).isEqualTo(recalculation.calculate(member));
	}

	@Test
	void 같은_회원을_여러_번_재계산해도_결과가_같고_DB는_바뀌지_않는다() {
		long member = fixture.member();
		long admin = fixture.admin();
		fixture.history(member, "FFPDFP");
		fixture.fulfill(member, admin, endOf(0), week(0));
		var snapshot = fixture.snapshot(member);

		WarningRecalcResult first = recalculation.recalculate(member);
		WarningRecalcResult second = recalculation.recalculate(member);
		WarningRecalcResult third = recalculation.calculate(member);

		assertThat(second).isEqualTo(first);
		assertThat(third).isEqualTo(first);
		assertThat(fixture.snapshot(member)).isEqualTo(snapshot);
	}

	@Test
	void E60_삭제_표시된_경고는_DB에서도_세지_않는다() {
		long member = fixture.member();
		fixture.history(member, "FDF");

		var result = assertState(recalculation.recalculate(member), 2, 0);
		assertActive(result, 0, 2);
	}

	@Test
	void E60_삭제된_경고가_있는_회원의_다른_주차를_정정해도_삭제된_경고는_되살아나지_않는다() {
		long member = fixture.member();
		fixture.history(member, "FDFF");
		assertState(recalculation.recalculate(member), 3, 0);

		// 미달 주차를 센다면 3주 정정 뒤 다시 미달 4주가 된다. 경고 레코드를 세므로 그렇지 않다
		fixture.setStatus(member, 3, JudgmentStatus.PASS);
		var result = assertState(recalculation.recalculate(member), 2, 1);
		assertActive(result, 0, 2);

		fixture.setStatus(member, 3, JudgmentStatus.EXEMPT);
		assertActive(assertState(recalculation.recalculate(member), 2, 0), 0, 2);

		fixture.setStatus(member, 0, JudgmentStatus.PASS);
		assertActive(assertState(recalculation.recalculate(member), 1, 0), 2);
	}

	@Test
	void 삭제된_경고를_복구하면_그_주부터_다시_센다() {
		long member = fixture.member();
		fixture.history(member, "FDF");
		assertState(recalculation.recalculate(member), 2, 0);

		fixture.restoreWarning(member, 1);
		assertState(recalculation.recalculate(member), 3, 0);
	}

	@Test
	void 결정_대기_삭제_표시된_경고가_붙은_미달_주는_경고에_안_넣지만_연속은_끊는다() {
		long member = fixture.member();
		fixture.history(member, "FPDP");

		assertActive(assertState(recalculation.recalculate(member), 1, 1), 0);
	}

	// ---- 이행 기준선 ----

	@Test
	void 가장_최근_이행_시각이_기준선이다() {
		long member = fixture.member();
		long admin = fixture.admin();
		fixture.history(member, "FFFFFFFF");
		// 늦은 이행을 먼저 넣어 id 순서와 시각 순서를 어긋나게 한다
		fixture.fulfill(member, admin, endOf(5), week(5));
		fixture.fulfill(member, admin, endOf(2), week(2));

		var result = assertState(recalculation.recalculate(member), 2, 0);
		assertActive(result, 6, 7);
		assertBeforeBaseline(result, 0, 1, 2, 3, 4, 5);
	}

	@Test
	void 이행_체크_뒤에는_경고_0_연속_0이다() {
		long member = fixture.member();
		long admin = fixture.admin();
		fixture.history(member, "FFF");
		assertState(recalculation.recalculate(member), 3, 0);

		fixture.fulfill(member, admin, endOf(2).plusHours(10), week(2));

		assertState(recalculation.recalculate(member), 0, 0);
	}

	@Test
	void E58_이행_이전_주차를_정정해도_DB에서도_현재_카운트가_그대로다() {
		long member = fixture.member();
		long admin = fixture.admin();
		fixture.history(member, "FFFPF");
		fixture.fulfill(member, admin, endOf(2), week(2));
		var expected = recalculation.recalculate(member);
		assertState(expected, 1, 0);

		fixture.setStatus(member, 1, JudgmentStatus.PASS);
		assertSameNumbers(recalculation.recalculate(member), expected);
		fixture.setStatus(member, 0, JudgmentStatus.EXEMPT);
		assertSameNumbers(recalculation.recalculate(member), expected);
		fixture.setStatus(member, 2, JudgmentStatus.PASS);
		assertSameNumbers(recalculation.recalculate(member), expected);
	}

	@Test
	void E59_이행_이전_경고를_복구해도_DB에서도_경고_수는_그대로다() {
		long member = fixture.member();
		long admin = fixture.admin();
		fixture.history(member, "FDFFP");
		fixture.fulfill(member, admin, endOf(2), week(2));
		var expected = assertState(recalculation.recalculate(member), 1, 1);
		assertBeforeBaseline(expected, 0, 2);

		fixture.restoreWarning(member, 1);

		var restored = recalculation.recalculate(member);
		assertSameNumbers(restored, expected);
		assertBeforeBaseline(calculated(restored), 0, 1, 2);
	}

	@Test
	void 이행_이후_주차의_정정과_복구는_반영된다() {
		long member = fixture.member();
		long admin = fixture.admin();
		fixture.history(member, "FFFDP");
		fixture.fulfill(member, admin, endOf(2), week(2));
		assertState(recalculation.recalculate(member), 0, 1);

		fixture.restoreWarning(member, 3);
		assertActive(assertState(recalculation.recalculate(member), 1, 1), 3);

		fixture.setStatus(member, 4, JudgmentStatus.FAIL);
		assertActive(assertState(recalculation.recalculate(member), 2, 0), 3, 4);
	}

	@Test
	void 결정_대기_이행_시각이_주_끝과_같으면_그_주는_이전이고_1초_빠르면_이후다() {
		long member = fixture.member();
		long admin = fixture.admin();
		fixture.history(member, "FFFF");

		long fulfillment = fixture.fulfill(member, admin, endOf(2), week(2));
		assertActive(assertState(recalculation.recalculate(member), 1, 0), 3);

		jdbc.update("update penalty_fulfillment set fulfilled_at = ? where id = ?", endOf(2).minusSeconds(1), fulfillment);
		assertActive(assertState(recalculation.recalculate(member), 2, 0), 2, 3);

		jdbc.update("update penalty_fulfillment set fulfilled_at = ? where id = ?", endOf(2).plusSeconds(1), fulfillment);
		assertActive(assertState(recalculation.recalculate(member), 1, 0), 3);
	}

	@Test
	void 다른_회원의_이행은_기준선이_되지_않는다() {
		long member = fixture.member();
		long other = fixture.member();
		long admin = fixture.admin();
		fixture.history(member, "FFF");
		fixture.history(other, "FFF");
		fixture.fulfill(other, admin, endOf(2), week(2));

		assertState(recalculation.recalculate(member), 3, 0);
		assertState(recalculation.recalculate(other), 0, 0);
	}

	// ---- 보류 ----

	@Test
	void 보류_주가_있으면_숫자_없이_보류로_끝난다() {
		long member = fixture.member();
		fixture.history(member, "FPH");

		assertThat(holdWeeks(recalculation.recalculate(member))).isEqualTo(weeks(2));
		assertThat(holdWeeks(recalculation.calculate(member))).isEqualTo(weeks(2));
	}

	@Test
	void 보류가_풀려_확정되면_처음부터_다시_계산한다() {
		long member = fixture.member();
		fixture.history(member, "FPH");
		holdWeeks(recalculation.recalculate(member));

		fixture.setStatus(member, 2, JudgmentStatus.PASS);

		assertState(recalculation.recalculate(member), 0, 0);
	}

	@Test
	void 이행_기준선_이전의_보류는_영향이_없고_이후의_보류는_보류다() {
		long member = fixture.member();
		long admin = fixture.admin();
		fixture.history(member, "HFFH");
		fixture.fulfill(member, admin, endOf(1), week(1));

		assertThat(holdWeeks(recalculation.recalculate(member))).isEqualTo(weeks(3));

		// 보류가 미달로 확정되면 판정 저장 서비스가 경고 행도 같이 만든다
		fixture.setStatus(member, 3, JudgmentStatus.FAIL);
		fixture.warning(member, week(3), null);
		assertActive(assertState(recalculation.recalculate(member), 2, 0), 2, 3);
	}

	// ---- 순서 ----

	private record Edit(String name, Runnable apply) {

		@Override
		public String toString() {
			return name;
		}

	}

	private static void permute(List<Edit> rest, List<Edit> chosen, List<List<Edit>> out) {
		if (rest.isEmpty()) {
			out.add(new ArrayList<>(chosen));
			return;
		}
		for (int i = 0; i < rest.size(); i++) {
			List<Edit> remaining = new ArrayList<>(rest);
			chosen.add(remaining.remove(i));
			permute(remaining, chosen, out);
			chosen.remove(chosen.size() - 1);
		}
	}

	@Test
	void 정정_소급_면제_복구를_어떤_순서로_해도_DB에서_계산한_결과가_같다() {
		List<WarningRecalcResult> finals = new ArrayList<>();
		List<Edit> sample = null;
		int orders = 0;
		// 최종 이력: F E F P P F P P -> 경고 1개(5주), 0주·2주 경고를 차감
		for (int run = 0; run < 24; run++) {
			long member = fixture.member();
			fixture.history(member, "FFDFFFPP");
			List<Edit> edits = List.of(
					new Edit("소급 면제 1주", () -> fixture.setStatus(member, 1, JudgmentStatus.EXEMPT)),
					new Edit("2주 경고 복구", () -> fixture.restoreWarning(member, 2)),
					new Edit("3주 통과 정정", () -> fixture.setStatus(member, 3, JudgmentStatus.PASS)),
					new Edit("4주 통과 정정", () -> fixture.setStatus(member, 4, JudgmentStatus.PASS)));
			List<List<Edit>> all = new ArrayList<>();
			permute(edits, new ArrayList<>(), all);
			List<Edit> order = all.get(run);
			for (Edit edit : order) {
				edit.apply().run();
				// 변경 때마다 다시 계산한다. 중간 결과가 최종 결과에 남아서는 안 된다
				recalculation.recalculate(member);
			}
			finals.add(recalculation.recalculate(member));
			orders++;
			sample = order;
		}

		assertThat(orders).isEqualTo(24);
		assertThat(sample).isNotNull();
		for (WarningRecalcResult result : finals) {
			var calculated = assertState(result, 1, 0);
			assertActive(calculated, 5);
			assertDeducted(calculated, 0, 2);
			assertThat(result).isEqualTo(finals.get(0));
		}
	}

	@Test
	void 이행_기준선이_있어도_편집_순서와_무관하게_DB_결과가_같다() {
		long admin = fixture.admin();
		List<WarningRecalcResult> finals = new ArrayList<>();
		for (int run = 0; run < 6; run++) {
			long member = fixture.member();
			fixture.history(member, "FFFFPP");
			fixture.fulfill(member, admin, endOf(2), week(2));
			List<Edit> edits = List.of(
					new Edit("0주 통과 정정", () -> fixture.setStatus(member, 0, JudgmentStatus.PASS)),
					new Edit("2주 경고 삭제", () -> fixture.softDeleteWarning(member, 2, LocalDateTime.of(2026, 2, 2, 0, 0))),
					new Edit("3주 통과 정정", () -> fixture.setStatus(member, 3, JudgmentStatus.PASS)));
			List<List<Edit>> all = new ArrayList<>();
			permute(edits, new ArrayList<>(), all);
			for (Edit edit : all.get(run)) {
				edit.apply().run();
				recalculation.recalculate(member);
			}
			finals.add(recalculation.recalculate(member));
		}

		for (WarningRecalcResult result : finals) {
			// 이행 뒤 3~5주가 모두 통과: 연속 1
			assertState(result, 0, 1);
			assertSameNumbers(result, finals.get(0));
		}
	}

	// ---- 전체 재훑기 (기능명세서 6장: 저장한 값을 더하고 빼지 않는다) ----

	@Test
	void 경고_수와_연속_통과를_저장하는_컬럼이나_캐시_테이블이_없다() {
		assertThat(fixture.count("select count(*) from information_schema.columns where table_schema = database()"
				+ " and table_name in ('member', 'weekly_judgment', 'warning', 'warning_team', 'penalty_fulfillment')"
				+ " and (column_name like '%warning_count%' or column_name like '%streak%'"
				+ " or column_name like '%consecutive%')")).isZero();
		assertThat(fixture.count("select count(*) from information_schema.tables where table_schema = database()"
				+ " and (table_name like '%warning_state%' or table_name like '%streak%')")).isZero();
	}

	@Test
	void 이력을_직접_고쳐도_결과는_그_이력만으로_계산한_값과_같다() {
		long member = fixture.member();
		char[] script = "FFFFFFFFFF".toCharArray();
		fixture.history(member, new String(script));
		Random random = new Random(20261001L);
		char[] symbols = {'P', 'F', 'E'};

		for (int step = 0; step < 40; step++) {
			int week = random.nextInt(script.length);
			char symbol = symbols[random.nextInt(symbols.length)];
			script[week] = symbol;
			fixture.setStatus(member, week, switch (symbol) {
				case 'P' -> JudgmentStatus.PASS;
				case 'F' -> JudgmentStatus.FAIL;
				default -> JudgmentStatus.EXEMPT;
			});

			// 앞선 편집 경로와 상관없이 지금 이력만 처음부터 훑은 값과 같아야 한다
			assertThat(recalculation.recalculate(member)).as("%d번째 편집 뒤 이력=%s", step, new String(script))
					.isEqualTo(WarningRecalculator.recalculate(parse(new String(script)), null));
		}
	}

	@Test
	void 팀_카테고리가_일부만_빠진_경고는_그대로_세고_모두_빠져_삭제되면_빠진다() {
		long member = fixture.member();
		long teamA = fixture.team(member);
		long teamB = fixture.team(member);
		fixture.history(member, "FF");
		long first = fixture.warningId(member, 0);
		LocalDateTime kickedAt = LocalDateTime.of(2026, 1, 20, 0, 0);
		fixture.warningTeam(first, teamA, kickedAt);
		fixture.warningTeam(first, teamB, null);

		// 카테고리 하나만 빠진 경고는 살아 있다
		assertState(recalculation.recalculate(member), 2, 0);

		// 남은 카테고리도 빠져 경고가 삭제 표시되면 세지 않는다
		jdbc.update("update warning_team set removed_at = ? where warning_id = ? and team_id = ?", kickedAt, first, teamB);
		fixture.softDeleteWarning(member, 0, kickedAt);
		assertActive(assertState(recalculation.recalculate(member), 1, 0), 1);

		// 복구하면 다시 센다
		fixture.restoreWarning(member, 0);
		assertState(recalculation.recalculate(member), 2, 0);
	}

	// ---- 미리보기 (기능명세서 7장: 계산만 하고 아무것도 바꾸지 않는다) ----

	@Test
	void 미리보기는_어떤_가정을_넣어도_DB를_바꾸지_않는다() {
		long member = fixture.member();
		long admin = fixture.admin();
		fixture.history(member, "FFPPFDP");
		fixture.fulfill(member, admin, endOf(0), week(0));
		var snapshot = fixture.snapshot(member);

		recalculation.preview(member, Map.of(week(3), JudgmentStatus.FAIL));
		recalculation.preview(member, Map.of(week(1), JudgmentStatus.EXEMPT, week(2), JudgmentStatus.FAIL));
		recalculation.preview(member, Map.of(week(5), JudgmentStatus.PASS, week(6), JudgmentStatus.EXEMPT));
		// 판정 행이 아직 없는 주를 가정해도 행을 만들지 않는다
		recalculation.preview(member, Map.of(week(9), JudgmentStatus.EXEMPT));
		recalculation.preview(member, Map.of());

		assertThat(fixture.snapshot(member)).isEqualTo(snapshot);
		assertThat(fixture.count("select count(*) from weekly_judgment where member_id = ?", member)).isEqualTo(7);
		assertThat(fixture.count("select count(*) from warning where member_id = ?", member)).isEqualTo(4);
	}

	@Test
	void 미리보기_뒤_실제_재계산은_가정이_아닌_저장된_값으로_계산한다() {
		long member = fixture.member();
		fixture.history(member, "FFF");
		var actual = recalculation.recalculate(member);

		recalculation.preview(member, Map.of(week(2), JudgmentStatus.EXEMPT));

		assertThat(recalculation.recalculate(member)).isEqualTo(actual);
		assertState(actual, 3, 0);
	}

	@Test
	void 미리보기는_지금_값과_가정_적용_값을_함께_돌려준다() {
		long member = fixture.member();
		fixture.history(member, "FFF");

		// 3번째 미달을 소급 면제하면 경고 3 -> 2, 벌칙 대상에서 빠진다
		WarningRecalcPreview preview = recalculation.preview(member, Map.of(week(2), JudgmentStatus.EXEMPT));

		assertThat(preview.comparable()).isTrue();
		assertThat(preview.warningCountBefore()).isEqualTo(3);
		assertThat(preview.warningCountAfter()).isEqualTo(2);
		assertThat(preview.leavesPenalty()).isTrue();
		assertThat(preview.entersPenalty()).isFalse();
		assertState(preview.before(), 3, 0);
		assertState(preview.after(), 2, 0);
	}

	@Test
	void 미리보기의_지금_값은_calculate와_같다() {
		long member = fixture.member();
		fixture.history(member, "FPFPPF");

		var preview = recalculation.preview(member, Map.of(week(0), JudgmentStatus.PASS));

		assertThat(preview.before()).isEqualTo(recalculation.calculate(member));
	}

	@Test
	void 통과_주를_미달로_정정하는_미리보기는_경고가_늘고_벌칙_대상에_들어간다() {
		long member = fixture.member();
		fixture.history(member, "FFPPF");
		assertState(recalculation.calculate(member), 2, 0);

		// 3주 통과를 미달로 가정: 2주 연속이 깨져 차감이 사라진다. 경고 행이 없어도 새로 생길 경고로 센다
		var preview = recalculation.preview(member, Map.of(week(3), JudgmentStatus.FAIL));

		assertThat(preview.warningCountBefore()).isEqualTo(2);
		assertThat(preview.warningCountAfter()).isEqualTo(4);
		assertThat(preview.entersPenalty()).isTrue();
		assertActive(calculated(preview.after()), 0, 1, 3, 4);
	}

	@Test
	void 미달_주를_통과로_정정하는_미리보기는_경고가_줄어든다() {
		long member = fixture.member();
		fixture.history(member, "FFFP");

		var preview = recalculation.preview(member, Map.of(week(2), JudgmentStatus.PASS));

		assertThat(preview.warningCountBefore()).isEqualTo(3);
		// F F P P: 2주 연속 통과로 가장 오래된 경고 차감
		assertThat(preview.warningCountAfter()).isEqualTo(1);
		assertThat(preview.leavesPenalty()).isTrue();
		assertDeducted(calculated(preview.after()), 0);
	}

	@Test
	void E58_미리보기에서도_이행_이전_주를_가정하면_값이_그대로다() {
		long member = fixture.member();
		long admin = fixture.admin();
		fixture.history(member, "FFFPF");
		fixture.fulfill(member, admin, endOf(2), week(2));

		var preview = recalculation.preview(member,
				Map.of(week(0), JudgmentStatus.PASS, week(1), JudgmentStatus.EXEMPT, week(2), JudgmentStatus.PASS));

		assertSameNumbers(preview.after(), preview.before());
		assertThat(preview.entersPenalty()).isFalse();
		assertThat(preview.leavesPenalty()).isFalse();
		assertState(preview.before(), 1, 0);
	}

	@Test
	void E60_미리보기에서도_삭제된_경고는_되살아나지_않는다() {
		long member = fixture.member();
		fixture.history(member, "FDF");

		var preview = recalculation.preview(member, Map.of(week(0), JudgmentStatus.PASS));

		assertThat(preview.warningCountBefore()).isEqualTo(2);
		assertThat(preview.warningCountAfter()).isEqualTo(1);
		assertThat(calculated(preview.after()).activeWarningWeeks()).containsExactly(week(2));
	}

	@Test
	void 삭제된_경고가_붙은_주를_미달로_가정해도_경고에_넣지_않는다() {
		long member = fixture.member();
		fixture.history(member, "FDP");

		var preview = recalculation.preview(member, Map.of(week(1), JudgmentStatus.FAIL));

		assertThat(preview.warningCountBefore()).isEqualTo(1);
		assertThat(preview.warningCountAfter()).isEqualTo(1);
	}

	@Test
	void 보류가_있으면_미리보기도_비교할_수_없다() {
		long member = fixture.member();
		fixture.history(member, "FPH");

		var preview = recalculation.preview(member, Map.of(week(0), JudgmentStatus.EXEMPT));

		assertThat(preview.comparable()).isFalse();
		assertThat(holdWeeks(preview.before())).isEqualTo(weeks(2));
		assertThat(holdWeeks(preview.after())).isEqualTo(weeks(2));
	}

	@Test
	void 보류_주를_확정으로_가정하면_가정_쪽만_계산된다() {
		long member = fixture.member();
		fixture.history(member, "FPH");

		var preview = recalculation.preview(member, Map.of(week(2), JudgmentStatus.PASS));

		assertThat(preview.comparable()).isFalse();
		assertThat(holdWeeks(preview.before())).isEqualTo(weeks(2));
		assertState(preview.after(), 0, 0);
	}

	@Test
	void 판정_행이_없는_주를_면제로_가정해도_앞뒤_연속이_이어진다() {
		long member = fixture.member();
		fixture.history(member, "FP.P");

		var preview = recalculation.preview(member, Map.of(week(2), JudgmentStatus.EXEMPT));

		assertState(preview.before(), 0, 0);
		assertState(preview.after(), 0, 0);
		assertThat(fixture.count("select count(*) from weekly_judgment where member_id = ?", member)).isEqualTo(3);
	}

	// ---- 같은 트랜잭션 안의 변경 ----

	@Test
	void 변경을_마친_같은_트랜잭션에서_재계산하면_변경이_반영된_값이다() {
		long member = fixture.member();
		fixture.history(member, "FFPH");

		var inside = inTransaction(() -> {
			fixture.setStatus(member, 3, JudgmentStatus.PASS);
			return recalculation.recalculate(member);
		});

		assertState(inside, 1, 0);
		assertThat(recalculation.calculate(member)).isEqualTo(inside);
	}

	// ---- 회원 단위 락 (Q-08: MariaDB 회원 행 비관적 락) ----

	@Test
	void 회원_락은_이미_열린_트랜잭션_안에서만_잡을_수_있다() {
		long member = fixture.member();

		assertThatThrownBy(() -> recalculation.lockMember(member)).isInstanceOf(IllegalTransactionStateException.class);
		runInTransaction(() -> recalculation.lockMember(member));
	}

	@Test
	void 없는_회원은_락도_재계산도_NOT_FOUND다() {
		long missing = -1L;

		assertThatThrownBy(() -> runInTransaction(() -> recalculation.lockMember(missing)))
				.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
		assertThatThrownBy(() -> recalculation.recalculate(missing))
				.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
	}

	@Test
	void 한_회원의_락이_잡혀_있으면_같은_회원의_재계산은_기다렸다가_커밋된_값으로_계산한다() throws Exception {
		long member = fixture.member();
		fixture.history(member, "FFPH");
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			// 락을 쥔 채 보류 주를 통과로 확정하고 커밋하는 쪽
			Future<?> holder = pool.submit(() -> runInTransaction(() -> {
				recalculation.lockMember(member);
				locked.countDown();
				await(release);
				fixture.setStatus(member, 3, JudgmentStatus.PASS);
			}));
			assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

			Future<WarningRecalcResult> waiter = pool.submit(() -> recalculation.recalculate(member));
			Thread.sleep(Duration.ofMillis(800));
			assertThat(waiter.isDone()).as("락이 풀릴 때까지 기다려야 해요").isFalse();

			release.countDown();
			holder.get(10, TimeUnit.SECONDS);
			// 기다린 쪽은 보류 상태가 아니라 확정된 이력으로 계산한다
			assertState(waiter.get(10, TimeUnit.SECONDS), 1, 0);
		}
		finally {
			release.countDown();
			pool.shutdownNow();
		}
	}

	@Test
	void 한_회원의_락은_다른_회원의_재계산을_막지_않는다() throws Exception {
		long lockedMember = fixture.member();
		long other = fixture.member();
		fixture.history(other, "FF");
		CountDownLatch holding = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<?> holder = pool.submit(() -> runInTransaction(() -> {
				recalculation.lockMember(lockedMember);
				holding.countDown();
				await(release);
			}));
			assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();

			Future<WarningRecalcResult> done = pool.submit(() -> recalculation.recalculate(other));
			assertState(done.get(10, TimeUnit.SECONDS), 2, 0);

			release.countDown();
			holder.get(10, TimeUnit.SECONDS);
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
	void 판정_행이_있는_주_수가_달라도_빈_주는_건너뛴다() {
		long member = fixture.member();
		fixture.history(member, "F.F.PP");

		var result = assertState(recalculation.recalculate(member), 1, 0);
		assertActive(result, 2);
		assertDeducted(result, 0);
		LocalDate firstWeek = week(0);
		assertThat(result.deductedWarningWeeks()).containsExactly(firstWeek);
	}

}
