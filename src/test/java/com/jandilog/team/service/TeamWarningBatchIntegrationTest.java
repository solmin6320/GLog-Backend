package com.jandilog.team.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.judgment.domain.JudgmentWeek;
import com.jandilog.testsupport.admin.AdminIntegrationTest;
import com.jandilog.warning.domain.WarningRecalcResult;
import com.jandilog.warning.service.WarningRecalculationService;

import jakarta.persistence.EntityManagerFactory;

// 팀 삭제·추방의 경고 카테고리 정리: 남은 카테고리는 경고 전체를 한 번에 확인하고, 팀 삭제 재계산은 회원을 한 번에 읽는다.
// 소프트 삭제 규칙(남은 카테고리가 없는 살아 있는 경고만)과 회원별 recalculate 결과는 그대로다
class TeamWarningBatchIntegrationTest extends AdminIntegrationTest {

	private static final LocalDate WEEK = JudgmentWeek.mondayOf(LocalDate.of(2045, 3, 6));
	private static final LocalDateTime NOW = WEEK.plusWeeks(6).atTime(10, 0);
	private static final LocalDateTime EARLIER = WEEK.plusWeeks(2).atTime(9, 0);

	@Autowired
	TeamWarningService teamWarningService;
	@Autowired
	WarningRecalculationService recalculation;
	@Autowired
	PlatformTransactionManager transactionManager;
	@Autowired
	EntityManagerFactory entityManagerFactory;

	@BeforeEach
	void fixClock() {
		clock.fixAt(NOW.atZone(ZoneId.of("Asia/Seoul")).toInstant());
	}

	@Test
	@DisplayName("팀 삭제는 남은 카테고리가 없는 살아 있는 경고만 TEAM_DELETED로 지운다")
	void teamDeleteSoftDeletesOnlyUncategorizedAliveWarnings() {
		long leader = activeMember();
		long team = data.team(data.uniqueName("삭제팀"), true, leader);
		long other = data.team(data.uniqueName("남는팀"), true, leader);

		long only = data.failWithWarning(activeMember(), WEEK, team);
		long shared = data.failWithWarning(activeMember(), WEEK, team, other);
		long otherOnly = data.failWithWarning(activeMember(), WEEK, other);
		long otherRemoved = data.failWithWarning(activeMember(), WEEK, team, other);
		jdbc.update("update warning_team set removed_at = ? where warning_id = ? and team_id = ?", EARLIER,
				otherRemoved, other);
		long kickedMember = activeMember();
		data.fail(kickedMember, WEEK);
		long alreadyDeleted = data.warning(kickedMember, WEEK, EARLIER, "KICKED");
		data.warningTeam(alreadyDeleted, team, null);

		inTransaction(() -> teamWarningService.removeCategoriesOnTeamDelete(team, NOW));

		assertThat(deleteState(only)).isEqualTo(NOW + "|TEAM_DELETED");
		assertThat(deleteState(otherRemoved)).isEqualTo(NOW + "|TEAM_DELETED");
		assertThat(deleteState(shared)).isEqualTo("alive");
		assertThat(deleteState(otherOnly)).isEqualTo("alive");
		// 이미 지운 경고는 이유·시각을 덮어쓰지 않는다
		assertThat(deleteState(alreadyDeleted)).isEqualTo(EARLIER + "|KICKED");
		for (long warning : List.of(only, shared, otherRemoved, alreadyDeleted)) {
			assertThat(removedAt(warning, team)).as("경고 %d", warning).isEqualTo(NOW.toString());
		}
		assertThat(removedAt(shared, other)).isNull();
		assertThat(removedAt(otherOnly, other)).isNull();
		assertThat(removedAt(otherRemoved, other)).isEqualTo(EARLIER.toString());
	}

	@Test
	@DisplayName("추방도 그 팀 카테고리만 빼고 남은 카테고리가 없는 경고만 KICKED로 지운다")
	void kickSoftDeletesOnlyUncategorizedWarnings() {
		long leader = activeMember();
		long team = data.team(data.uniqueName("추방팀"), true, leader);
		long other = data.team(data.uniqueName("다른팀"), true, leader);
		long member = activeMember();
		long only = data.failWithWarning(member, WEEK, team);
		long shared = data.failWithWarning(member, WEEK.plusWeeks(1), team, other);
		long otherOnly = data.failWithWarning(member, WEEK.plusWeeks(2), other);
		long someoneElse = data.failWithWarning(activeMember(), WEEK, team);

		inTransaction(() -> teamWarningService.removeCategoryOnKick(member, team, NOW));

		assertThat(deleteState(only)).isEqualTo(NOW + "|KICKED");
		assertThat(deleteState(shared)).isEqualTo("alive");
		assertThat(deleteState(otherOnly)).isEqualTo("alive");
		assertThat(removedAt(shared, team)).isEqualTo(NOW.toString());
		assertThat(removedAt(shared, other)).isNull();
		// 다른 회원의 같은 팀 경고는 건드리지 않는다
		assertThat(deleteState(someoneElse)).isEqualTo("alive");
		assertThat(removedAt(someoneElse, team)).isNull();
	}

	@Test
	@DisplayName("recalculateAll은 회원마다 recalculate를 부른 것과 같고, 없는 회원이 있으면 NOT_FOUND다")
	void recalculateAllMatchesPerMemberRecalculate() {
		long team = data.team(data.uniqueName("재계산팀"), true, adminId);
		List<Long> ids = new ArrayList<>();
		long penalty = activeMember();
		for (int w = 0; w < 3; w++) {
			data.failWithWarning(penalty, WEEK.plusWeeks(w), team);
		}
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
		ids.add(activeMember());

		Map<Long, WarningRecalcResult> all = recalculation.recalculateAll(ids);

		assertThat(all).containsOnlyKeys(ids);
		for (long id : ids) {
			assertThat(all.get(id)).as("회원 %d", id).isEqualTo(recalculation.recalculate(id));
		}
		List<Long> withUnknown = new ArrayList<>(ids);
		withUnknown.add(Long.MAX_VALUE - 3);
		assertThatThrownBy(() -> recalculation.recalculateAll(withUnknown)).isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
	}

	@Test
	@DisplayName("팀 삭제에서 회원마다 늘어나는 쿼리는 회원 행 잠금 두 번과 행 UPDATE뿐이다")
	void teamDeleteQueriesPerMemberAreLocksAndUpdatesOnly() {
		// 고정: 회원 id 1 + 카테고리 1 + 경고 1 + 남은 카테고리 1 + 재계산(판정·경고·이행) 3 = 7.
		// 회원마다: 잠금 2(변경 전 · 재계산 전) + 카테고리 UPDATE 1 + 경고 UPDATE 1 = 4
		assertThat(teamDeleteStatements(2)).isEqualTo(7 + 4 * 2);
		assertThat(teamDeleteStatements(6)).isEqualTo(7 + 4 * 6);
	}

	@Test
	@DisplayName("경고 카테고리가 없는 팀 삭제는 아무것도 바꾸지 않고 조회 두 번(회원 id · 카테고리)으로 끝난다")
	void teamDeleteWithoutCategoriesChangesNothing() {
		long leader = activeMember();
		long team = data.team(data.uniqueName("빈팀"), true, leader);
		long other = data.team(data.uniqueName("옆팀"), true, leader);
		long elsewhere = data.failWithWarning(activeMember(), WEEK, other);

		long count = statements(() -> inTransaction(() -> teamWarningService.removeCategoriesOnTeamDelete(team, NOW)));

		assertThat(count).isEqualTo(2);
		assertThat(deleteState(elsewhere)).isEqualTo("alive");
		assertThat(removedAt(elsewhere, other)).isNull();
	}

	@Test
	@DisplayName("recalculateAll은 빈 입력이면 잠금·조회 없이 빈 결과이고, 같은 회원이 겹쳐도 한 번만 잠그고 계산한다")
	void recalculateAllHandlesEmptyAndDuplicateInput() {
		long team = data.team(data.uniqueName("겹침팀"), true, adminId);
		long first = activeMember();
		data.failWithWarning(first, WEEK, team);
		long second = activeMember();
		data.pass(second, WEEK);
		List<Map<Long, WarningRecalcResult>> results = new ArrayList<>();

		assertThat(statements(() -> results.add(recalculation.recalculateAll(List.of())))).isZero();
		assertThat(results.get(0)).isEmpty();

		// 잠금 2(회원 둘) + 판정·경고·이행 3
		assertThat(statements(() -> results.add(recalculation.recalculateAll(List.of(first, second, first)))))
				.isEqualTo(5);
		assertThat(results.get(1)).containsOnlyKeys(first, second);
		assertThat(results.get(1).get(first)).isEqualTo(recalculation.recalculate(first));
		assertThat(results.get(1).get(second)).isEqualTo(recalculation.recalculate(second));
	}

	private long teamDeleteStatements(int members) {
		long team = data.team(data.uniqueName("쿼리팀"), true, adminId);
		for (int i = 0; i < members; i++) {
			data.failWithWarning(activeMember(), WEEK, team);
		}
		return statements(() -> inTransaction(() -> teamWarningService.removeCategoriesOnTeamDelete(team, NOW)));
	}

	private long statements(Runnable work) {
		Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		boolean enabled = statistics.isStatisticsEnabled();
		statistics.setStatisticsEnabled(true);
		try {
			statistics.clear();
			work.run();
			return statistics.getPrepareStatementCount();
		}
		finally {
			statistics.setStatisticsEnabled(enabled);
		}
	}

	private void inTransaction(Runnable work) {
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
	}

	private String deleteState(long warningId) {
		return jdbc.queryForObject("select deleted_at, delete_reason from warning where id = ?",
				(rs, row) -> rs.getObject("deleted_at") == null ? "alive"
						: rs.getObject("deleted_at", LocalDateTime.class) + "|" + rs.getString("delete_reason"),
				warningId);
	}

	private String removedAt(long warningId, long teamId) {
		LocalDateTime removed = jdbc.queryForObject(
				"select removed_at from warning_team where warning_id = ? and team_id = ?",
				(rs, row) -> rs.getObject("removed_at", LocalDateTime.class), warningId, teamId);
		return removed == null ? null : removed.toString();
	}

}
