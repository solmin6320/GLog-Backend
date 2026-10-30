package com.jandilog.exemption;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.databind.JsonNode;
import com.jandilog.judgment.domain.DayResult;
import com.jandilog.judgment.domain.JudgmentResult;
import com.jandilog.judgment.service.JudgmentRecordService;
import com.jandilog.testsupport.exemption.ExemptionIntegrationTest;

// 주가 끝난 뒤에 만들어지는 경고(미달 정정, 월요일 판정 전 변동)에 그 사이 추방·팀 삭제·아이디 재초대 복귀를 반영한다.
// 경고가 그때 있었다면 받았을 처리와 같아야 한다 (기능명세서 6장, Q-02, E-38)
class LateWarningTeamChangeIntegrationTest extends ExemptionIntegrationTest {

	private static final LocalDate BASE = monday(2048, 9, 7);

	@Autowired
	private JudgmentRecordService recordService;

	@BeforeEach
	void fixClock() {
		fixClockAt(BASE.plusWeeks(30).atTime(10, 0));
	}

	@Test
	@DisplayName("유일한 팀에서 추방된 뒤 그 주를 미달로 정정하면 경고는 KICKED 삭제 표시로 만들어지고 경고 수에 들어가지 않는다")
	void correctionAfterKickFromOnlyTeamCreatesDeletedWarning() {
		long leader = activeMember();
		long team = newTeam("추방후정정", leader);
		long member = activeMember();
		History history = history(member, BASE, "P P", team);
		LocalDateTime kickedAt = BASE.plusWeeks(5).atTime(12, 0);
		membership(team, member, JOINED_AT, kickedAt, "KICKED", "INVITE_CODE");

		JsonNode preview = previewCorrection(history.judgment(0), "FAIL");
		assertThat(preview.path("impact").path("warningCountAfter").asInt()).isZero();

		JsonNode corrected = correct(history.judgment(0), "FAIL", "빈 커밋만 있음");

		assertThat(corrected.path("warningCount").asInt()).isZero();
		long warningId = warningOf(member, BASE);
		assertThat(data.warningAlive(warningId)).isFalse();
		assertThat(deleteReason(warningId)).isEqualTo("KICKED");
		assertThat(data.categoryRemoved(warningId, team)).isTrue();
	}

	@Test
	@DisplayName("추방됐다가 아이디 초대로 돌아온 팀의 카테고리는 남는다 (Q-02)")
	void returnByInvitationKeepsCategory() {
		long leader = activeMember();
		long team = newTeam("복귀후정정", leader);
		long member = activeMember();
		History history = history(member, BASE, "P", team);
		membership(team, member, JOINED_AT, BASE.plusWeeks(3).atStartOfDay(), "KICKED", "INVITE_CODE");
		membership(team, member, BASE.plusWeeks(4).atStartOfDay(), null, null, "INVITATION");

		JsonNode corrected = correct(history.judgment(0), "FAIL", "복귀한 팀원");

		assertThat(corrected.path("warningCount").asInt()).isEqualTo(1);
		long warningId = warningOf(member, BASE);
		assertThat(data.warningAlive(warningId)).isTrue();
		assertThat(data.categoryRemoved(warningId, team)).isFalse();
	}

	@Test
	@DisplayName("초대코드로 다시 들어온 것은 복귀가 아니라서 추방으로 빠진 카테고리를 되살리지 않는다")
	void returnByCodeDoesNotRestoreCategory() {
		long leader = activeMember();
		long team = newTeam("코드재참가", leader);
		long member = activeMember();
		History history = history(member, BASE, "P", team);
		membership(team, member, JOINED_AT, BASE.plusWeeks(3).atStartOfDay(), "KICKED", "INVITE_CODE");
		membership(team, member, BASE.plusWeeks(60).atStartOfDay(), null, null, "INVITE_CODE");

		correct(history.judgment(0), "FAIL", "코드로 돌아온 팀원");

		long warningId = warningOf(member, BASE);
		assertThat(data.warningAlive(warningId)).isFalse();
		assertThat(deleteReason(warningId)).isEqualTo("KICKED");
	}

	@Test
	@DisplayName("두 팀 중 한 팀이 삭제됐으면 그 팀 카테고리만 빠지고 경고는 살아 있다")
	void deletedTeamCategoryIsRemovedOnly() {
		long leader = activeMember();
		long alive = newTeam("남은팀", leader);
		long deleted = newTeam("삭제된팀", leader);
		long member = activeMember();
		join(alive, member);
		join(deleted, member);
		History history = history(member, BASE, "P", alive, deleted);
		jdbc.update("update team set deleted_at = ? where id = ?", BASE.plusWeeks(2).atStartOfDay(), deleted);

		JsonNode corrected = correct(history.judgment(0), "FAIL", "한 팀 삭제");

		assertThat(corrected.path("warningCount").asInt()).isEqualTo(1);
		long warningId = warningOf(member, BASE);
		assertThat(data.warningAlive(warningId)).isTrue();
		assertThat(data.categoryRemoved(warningId, deleted)).isTrue();
		assertThat(data.categoryRemoved(warningId, alive)).isFalse();
	}

	@Test
	@DisplayName("유일한 팀이 삭제됐으면 TEAM_DELETED 삭제 표시로 만들어진다")
	void onlyTeamDeletedCreatesDeletedWarning() {
		long leader = activeMember();
		long team = newTeam("유일삭제팀", leader);
		long member = activeMember();
		join(team, member);
		History history = history(member, BASE, "P", team);
		jdbc.update("update team set deleted_at = ? where id = ?", BASE.plusWeeks(2).atStartOfDay(), team);

		correct(history.judgment(0), "FAIL", "팀 삭제 뒤 정정");

		long warningId = warningOf(member, BASE);
		assertThat(data.warningAlive(warningId)).isFalse();
		assertThat(deleteReason(warningId)).isEqualTo("TEAM_DELETED");
	}

	@Test
	@DisplayName("일요일이 끝난 뒤 월요일 판정 전에 추방됐으면 판정이 만든 경고도 KICKED 삭제 표시다")
	void kickBeforeMondayJudgmentIsApplied() {
		long leader = activeMember();
		long team = newTeam("판정전추방", leader);
		long member = activeMember();
		membership(team, member, JOINED_AT, BASE.plusDays(7).atTime(3, 0), "KICKED", "INVITE_CODE");

		recordService.record(member, BASE, failedWeek(BASE), Set.of(team));

		long warningId = warningOf(member, BASE);
		assertThat(data.warningAlive(warningId)).isFalse();
		assertThat(deleteReason(warningId)).isEqualTo("KICKED");
		assertThat(state(member).warningCount()).isZero();
	}

	@Test
	@DisplayName("주가 끝난 뒤 팀 변동이 없으면 판정이 만든 경고는 그대로 살아 있다")
	void noChangeKeepsWarningAlive() {
		long leader = activeMember();
		long team = newTeam("변동없음", leader);
		long member = activeMember();
		join(team, member);

		recordService.record(member, BASE, failedWeek(BASE), Set.of(team));

		long warningId = warningOf(member, BASE);
		assertThat(data.warningAlive(warningId)).isTrue();
		assertThat(data.categoryRemoved(warningId, team)).isFalse();
		assertThat(state(member).warningCount()).isEqualTo(1);
	}

	private void membership(long teamId, long memberId, LocalDateTime joinedAt, LocalDateTime leftAt,
			String leaveType, String joinedBy) {
		jdbc.update("insert into team_member (team_id, member_id, joined_at, left_at, leave_type, joined_by)"
				+ " values (?, ?, ?, ?, ?, ?)", teamId, memberId, joinedAt, leftAt, leaveType, joinedBy);
	}

	private long warningOf(long memberId, LocalDate week) {
		return jdbc.queryForObject("select id from warning where member_id = ? and week_start = ?", Long.class,
				memberId, week);
	}

	private String deleteReason(long warningId) {
		return jdbc.queryForObject("select delete_reason from warning where id = ?", String.class, warningId);
	}

	private static JudgmentResult failedWeek(LocalDate weekStart) {
		List<DayResult> days = new ArrayList<>();
		for (int i = 0; i < 7; i++) {
			days.add(new DayResult(weekStart.plusDays(i), false, false));
		}
		return JudgmentResult.judged(false, 0, 0, days, weekStart.plusDays(7).atTime(7, 0));
	}

}
