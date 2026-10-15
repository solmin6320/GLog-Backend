package com.jandilog.warning.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.jandilog.judgment.domain.JudgmentWeek;
import com.jandilog.testsupport.admin.AdminIntegrationTest;
import com.jandilog.warning.domain.WarningRecalcResult;

import jakarta.persistence.EntityManagerFactory;

// 경고 일괄 계산: 회원마다 calculate를 부른 결과와 같고, 회원 수와 관계없이 판정·경고·이행 쿼리 세 개로 끝난다
class WarningRecalculationBatchIntegrationTest extends AdminIntegrationTest {

	private static final LocalDate WEEK = JudgmentWeek.mondayOf(LocalDate.of(2044, 3, 7));

	@Autowired
	WarningRecalculationService recalculation;
	@Autowired
	EntityManagerFactory entityManagerFactory;

	@BeforeEach
	void fixClock() {
		// 모든 시험 주차가 이미 끝난 시각
		clock.fixAt(WEEK.plusWeeks(10).atTime(10, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant());
	}

	@Test
	@DisplayName("벌칙 대상 · 차감 · 보류 · 삭제된 경고 · 이행 기준선 · 이력 없음이 섞여도 회원별 calculate와 같다")
	void matchesPerMemberCalculation() {
		List<Long> ids = mixedMembers();
		// 같은 회원을 두 번 넘겨도 한 번만 계산한다
		List<Long> input = new ArrayList<>(ids);
		input.add(ids.get(0));

		Map<Long, WarningRecalcResult> all = recalculation.calculateAll(input);

		assertThat(all).containsOnlyKeys(ids);
		for (long id : ids) {
			assertThat(all.get(id)).as("회원 %d", id).isEqualTo(recalculation.calculate(id));
		}
		// 갈래가 실제로 섞였는지 확인한다
		assertThat(all.values()).anyMatch(r -> r instanceof WarningRecalcResult.OnHold);
		assertThat(all.values()).anyMatch(r -> r instanceof WarningRecalcResult.Calculated c && c.penaltyTarget());
		assertThat(all.values())
				.anyMatch(r -> r instanceof WarningRecalcResult.Calculated c && !c.deductedWarningWeeks().isEmpty());
		assertThat(all.values())
				.anyMatch(r -> r instanceof WarningRecalcResult.Calculated c && !c.beforeBaselineWarningWeeks().isEmpty());
	}

	@Test
	@DisplayName("회원이 2명이든 8명이든 쿼리는 세 개다")
	void queryCountDoesNotGrowWithMembers() {
		List<Long> ids = mixedMembers();

		long few = statements(ids.subList(0, 2));
		long many = statements(ids);

		assertThat(few).isEqualTo(3);
		assertThat(many).isEqualTo(3);
	}

	@Test
	@DisplayName("빈 목록은 쿼리 없이 빈 결과다")
	void emptyInputRunsNoQuery() {
		assertThat(statements(List.of())).isZero();
		assertThat(recalculation.calculateAll(List.of())).isEmpty();
	}

	// 회원 일곱 명과 없는 회원 id 하나
	private List<Long> mixedMembers() {
		long team = data.team(data.uniqueName("일괄팀"), true, adminId);
		List<Long> ids = new ArrayList<>();

		long penalty = activeMember();
		data.failWithWarning(penalty, WEEK, team);
		data.failWithWarning(penalty, WEEK.plusWeeks(1), team);
		data.failWithWarning(penalty, WEEK.plusWeeks(2), team);
		ids.add(penalty);

		long deducted = activeMember();
		data.failWithWarning(deducted, WEEK, team);
		data.pass(deducted, WEEK.plusWeeks(1));
		data.pass(deducted, WEEK.plusWeeks(2));
		ids.add(deducted);

		long onHold = activeMember();
		data.failWithWarning(onHold, WEEK, team);
		data.hold(onHold, WEEK.plusWeeks(1), "API_ERROR", 1);
		ids.add(onHold);

		long kicked = activeMember();
		data.fail(kicked, WEEK);
		data.warning(kicked, WEEK, WEEK.plusWeeks(2).atTime(9, 0), "KICKED");
		data.pass(kicked, WEEK.plusWeeks(1));
		ids.add(kicked);

		long fulfilled = activeMember();
		data.failWithWarning(fulfilled, WEEK, team);
		data.failWithWarning(fulfilled, WEEK.plusWeeks(1), team);
		data.failWithWarning(fulfilled, WEEK.plusWeeks(2), team);
		data.fulfillment(fulfilled, adminId, WEEK.plusWeeks(3).atTime(12, 0), WEEK.plusWeeks(2));
		data.fulfillment(fulfilled, adminId, WEEK.plusWeeks(3).atTime(9, 0), WEEK.plusWeeks(2));
		data.failWithWarning(fulfilled, WEEK.plusWeeks(4), team);
		ids.add(fulfilled);

		long skipped = activeMember();
		data.firstWeek(skipped, WEEK);
		data.exempt(skipped, WEEK.plusWeeks(1));
		data.failWithWarning(skipped, WEEK.plusWeeks(2), team);
		data.pass(skipped, WEEK.plusWeeks(3));
		ids.add(skipped);

		ids.add(activeMember());
		ids.add(Long.MAX_VALUE - 7);
		return ids;
	}

	private long statements(List<Long> memberIds) {
		Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		boolean enabled = statistics.isStatisticsEnabled();
		statistics.setStatisticsEnabled(true);
		try {
			statistics.clear();
			recalculation.calculateAll(memberIds);
			return statistics.getPrepareStatementCount();
		}
		finally {
			statistics.setStatisticsEnabled(enabled);
		}
	}

}
