package com.jandilog.warning.repository;

import static com.jandilog.testsupport.warning.RecalcAssert.assertActive;
import static com.jandilog.testsupport.warning.RecalcAssert.assertBeforeBaseline;
import static com.jandilog.testsupport.warning.RecalcAssert.assertSameNumbers;
import static com.jandilog.testsupport.warning.RecalcAssert.assertState;
import static com.jandilog.testsupport.warning.WeekScript.endOf;
import static com.jandilog.testsupport.warning.WeekScript.week;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import com.jandilog.testsupport.warning.WarningIntegrationTest;
import com.jandilog.warning.domain.PenaltyFulfillment;
import com.jandilog.warning.domain.Warning;
import com.jandilog.warning.domain.WarningDeleteReason;
import com.jandilog.warning.domain.WarningTeam;

// 경고·경고 팀·이행 체크의 저장과 소프트 삭제·복구 (기능명세서 6장). 삭제 표시를 바꾼 뒤 재계산이 어떻게 보는지까지 확인한다
class WarningRepositoryIntegrationTest extends WarningIntegrationTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 3, 2, 12, 0);

	@Autowired
	private WarningRepository warnings;
	@Autowired
	private WarningTeamRepository warningTeams;
	@Autowired
	private PenaltyFulfillmentRepository fulfillments;

	private Map<String, Object> warningRow(long member, int weekIndex) {
		return jdbc.queryForMap("select deleted_at, delete_reason from warning where member_id = ? and week_start = ?",
				member, week(weekIndex));
	}

	private void softDelete(long member, int weekIndex, WarningDeleteReason reason) {
		runInTransaction(() -> {
			Warning warning = warnings.findByMemberIdAndWeekStart(member, week(weekIndex)).orElseThrow();
			warning.softDelete(reason, NOW);
			warnings.save(warning);
		});
	}

	private void restore(long member, int weekIndex) {
		runInTransaction(() -> {
			Warning warning = warnings.findByMemberIdAndWeekStart(member, week(weekIndex)).orElseThrow();
			warning.restore();
			warnings.save(warning);
		});
	}

	// ---- 경고 ----

	@Test
	void 경고를_저장하면_사람당_주마다_한_행이고_처음에는_살아_있다() {
		long member = fixture.member();

		Warning saved = warnings.saveAndFlush(Warning.create(member, week(0), NOW));

		assertThat(saved.getId()).isNotNull();
		Warning found = warnings.findByMemberIdAndWeekStart(member, week(0)).orElseThrow();
		assertThat(found.isAlive()).isTrue();
		assertThat(found.getCreatedAt()).isEqualTo(NOW);
		assertThat(warnings.findByMemberIdAndWeekStart(member, week(1))).isEmpty();
	}

	@Test
	void 같은_회원의_같은_주에는_경고를_두_번_만들_수_없다() {
		long member = fixture.member();
		warnings.saveAndFlush(Warning.create(member, week(0), NOW));

		assertThatThrownBy(() -> warnings.saveAndFlush(Warning.create(member, week(0), NOW.plusDays(1))))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(fixture.count("select count(*) from warning where member_id = ?", member)).isEqualTo(1);
	}

	@Test
	void 다른_회원은_같은_주에도_각자_경고를_가진다() {
		long a = fixture.member();
		long b = fixture.member();

		warnings.saveAndFlush(Warning.create(a, week(0), NOW));
		warnings.saveAndFlush(Warning.create(b, week(0), NOW));

		assertThat(warnings.findByMemberIdOrderByWeekStartAsc(a)).hasSize(1);
		assertThat(warnings.findByMemberIdOrderByWeekStartAsc(b)).hasSize(1);
	}

	@Test
	void 추방으로_소프트_삭제하면_행은_남고_삭제_시각과_사유가_기록된다() {
		long member = fixture.member();
		fixture.history(member, "FF");

		softDelete(member, 0, WarningDeleteReason.KICKED);

		assertThat(fixture.count("select count(*) from warning where member_id = ?", member)).isEqualTo(2);
		var row = warningRow(member, 0);
		assertThat(row.get("deleted_at")).isNotNull();
		assertThat(row.get("delete_reason")).isEqualTo("KICKED");
		assertThat(warningRow(member, 1).get("deleted_at")).isNull();
	}

	@Test
	void 팀_삭제로_소프트_삭제하면_사유가_TEAM_DELETED로_기록된다() {
		long member = fixture.member();
		fixture.history(member, "F");

		softDelete(member, 0, WarningDeleteReason.TEAM_DELETED);

		assertThat(warningRow(member, 0).get("delete_reason")).isEqualTo("TEAM_DELETED");
	}

	@Test
	void 살아_있는_경고_목록은_삭제_표시된_경고를_뺀다() {
		long member = fixture.member();
		fixture.history(member, "FFF");
		softDelete(member, 1, WarningDeleteReason.KICKED);

		assertThat(warnings.findByMemberIdAndDeletedAtIsNullOrderByWeekStartAsc(member)).extracting(Warning::getWeekStart)
				.containsExactly(week(0), week(2));
		// 관리자가 삭제 내역을 볼 수 있도록 전체 목록에는 남는다
		assertThat(warnings.findByMemberIdOrderByWeekStartAsc(member)).extracting(Warning::getWeekStart)
				.containsExactly(week(0), week(1), week(2));
	}

	@Test
	void 복구하면_삭제_시각과_사유가_비워지고_살아_있는_목록에_돌아온다() {
		long member = fixture.member();
		fixture.history(member, "FF");
		softDelete(member, 0, WarningDeleteReason.KICKED);

		restore(member, 0);

		var row = warningRow(member, 0);
		assertThat(row.get("deleted_at")).isNull();
		assertThat(row.get("delete_reason")).isNull();
		assertThat(warnings.findByMemberIdAndDeletedAtIsNullOrderByWeekStartAsc(member)).hasSize(2);
	}

	@Test
	void 경고_목록은_다른_회원의_경고를_섞지_않는다() {
		long a = fixture.member();
		long b = fixture.member();
		fixture.history(a, "FF");
		fixture.history(b, "F");

		assertThat(warnings.findByMemberIdOrderByWeekStartAsc(a)).hasSize(2);
		assertThat(warnings.findByMemberIdOrderByWeekStartAsc(b)).hasSize(1);
	}

	// ---- 경고 팀 카테고리 (N:M) ----

	@Test
	void 경고_하나에_팀_여러_개를_카테고리로_붙이고_조회한다() {
		long member = fixture.member();
		long teamA = fixture.team(member);
		long teamB = fixture.team(member);
		Warning warning = warnings.saveAndFlush(Warning.create(member, week(0), NOW));

		warningTeams.save(WarningTeam.of(warning.getId(), teamA));
		warningTeams.save(WarningTeam.of(warning.getId(), teamB));

		assertThat(warningTeams.findByWarningId(warning.getId())).extracting(WarningTeam::getTeamId)
				.containsExactlyInAnyOrder(teamA, teamB);
		assertThat(warningTeams.findByWarningIdIn(List.of(warning.getId()))).hasSize(2);
		assertThat(warningTeams.findByWarningIdIn(List.of(-1L))).isEmpty();
	}

	@Test
	void 추방된_팀_카테고리만_removed_at이_찍히고_다른_팀은_그대로다() {
		long member = fixture.member();
		long kickedTeam = fixture.team(member);
		long keptTeam = fixture.team(member);
		fixture.history(member, "F");
		long warningId = fixture.warningId(member, 0);
		fixture.warningTeam(warningId, kickedTeam, null);
		fixture.warningTeam(warningId, keptTeam, null);

		runInTransaction(() -> warningTeams.findByWarningId(warningId).stream()
				.filter(wt -> wt.getTeamId() == kickedTeam).forEach(wt -> wt.remove(NOW)));

		var rows = warningTeams.findByWarningId(warningId);
		assertThat(rows).filteredOn(WarningTeam::isRemoved).extracting(WarningTeam::getTeamId).containsExactly(kickedTeam);
		assertThat(rows).filteredOn(wt -> !wt.isRemoved()).extracting(WarningTeam::getTeamId).containsExactly(keptTeam);
	}

	@Test
	void 재초대로_복귀하면_카테고리의_removed_at이_비워진다() {
		long member = fixture.member();
		long team = fixture.team(member);
		fixture.history(member, "F");
		long warningId = fixture.warningId(member, 0);
		fixture.warningTeam(warningId, team, NOW);

		runInTransaction(() -> warningTeams.findByWarningId(warningId).forEach(WarningTeam::restore));

		assertThat(warningTeams.findByWarningId(warningId)).allMatch(wt -> !wt.isRemoved());
		assertThat(fixture.count("select count(*) from warning_team where warning_id = ? and removed_at is null",
				warningId)).isEqualTo(1);
	}

	@Test
	void 같은_경고에_같은_팀을_두_번_붙일_수_없다() {
		long member = fixture.member();
		long team = fixture.team(member);
		fixture.history(member, "F");
		long warningId = fixture.warningId(member, 0);
		fixture.warningTeam(warningId, team, null);

		assertThatThrownBy(() -> fixture.warningTeam(warningId, team, null))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	// ---- 벌칙 이행 체크 ----

	@Test
	void 이행_기록이_없으면_기준선도_없다() {
		long member = fixture.member();

		assertThat(fulfillments.findFirstByMemberIdOrderByFulfilledAtDesc(member)).isEmpty();
	}

	@Test
	void 가장_최근_이행_시각의_기록을_돌려준다() {
		long member = fixture.member();
		long admin = fixture.admin();
		// 늦은 이행을 먼저 저장해 id 순서와 시각 순서를 어긋나게 한다
		fulfillments.saveAndFlush(PenaltyFulfillment.of(member, endOf(5), admin, week(5)));
		fulfillments.saveAndFlush(PenaltyFulfillment.of(member, endOf(2), admin, week(2)));

		PenaltyFulfillment latest = fulfillments.findFirstByMemberIdOrderByFulfilledAtDesc(member).orElseThrow();

		assertThat(latest.getFulfilledAt()).isEqualTo(endOf(5));
		assertThat(latest.getReachedWeek()).isEqualTo(week(5));
		assertThat(latest.getAdminId()).isEqualTo(admin);
		assertThat(latest.getMemberId()).isEqualTo(member);
	}

	@Test
	void 이행_기록은_회원마다_따로다() {
		long member = fixture.member();
		long other = fixture.member();
		long admin = fixture.admin();
		fulfillments.saveAndFlush(PenaltyFulfillment.of(other, endOf(2), admin, week(2)));

		assertThat(fulfillments.findFirstByMemberIdOrderByFulfilledAtDesc(member)).isEmpty();
		assertThat(fulfillments.findFirstByMemberIdOrderByFulfilledAtDesc(other)).isPresent();
	}

	// ---- 삭제 표시와 재계산 ----

	@Test
	void 추방으로_삭제_표시된_경고는_재계산에서_빠지고_복구하면_다시_센다() {
		long member = fixture.member();
		fixture.history(member, "FFF");
		assertState(recalculation.recalculate(member), 3, 0);

		softDelete(member, 1, WarningDeleteReason.KICKED);
		assertActive(assertState(recalculation.recalculate(member), 2, 0), 0, 2);

		restore(member, 1);
		assertState(recalculation.recalculate(member), 3, 0);
	}

	@Test
	void E59_이행_이전_주의_경고를_복구하면_기록만_되살아나고_경고_수는_그대로다() {
		long member = fixture.member();
		long admin = fixture.admin();
		fixture.history(member, "FFFFP");
		fixture.fulfill(member, admin, endOf(2), week(2));
		softDelete(member, 1, WarningDeleteReason.KICKED);
		var before = assertState(recalculation.recalculate(member), 1, 1);
		assertBeforeBaseline(before, 0, 2);

		restore(member, 1);

		var after = recalculation.recalculate(member);
		assertSameNumbers(after, before);
		assertBeforeBaseline(assertState(after, 1, 1), 0, 1, 2);
		assertThat(warnings.findByMemberIdAndDeletedAtIsNullOrderByWeekStartAsc(member)).hasSize(4);
	}

	@Test
	void 이행_이후_주의_경고를_복구하면_경고_수에_반영된다() {
		long member = fixture.member();
		long admin = fixture.admin();
		fixture.history(member, "FFFFP");
		fixture.fulfill(member, admin, endOf(2), week(2));
		softDelete(member, 3, WarningDeleteReason.TEAM_DELETED);
		assertState(recalculation.recalculate(member), 0, 1);

		restore(member, 3);

		assertActive(assertState(recalculation.recalculate(member), 1, 1), 3);
	}

}
