package com.jandilog.judgment.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.jandilog.exemption.dto.JudgmentCorrectionInput;
import com.jandilog.exemption.service.JudgmentCorrectionService;
import com.jandilog.judgment.domain.HoldReason;
import com.jandilog.judgment.domain.JudgmentStatus;
import com.jandilog.judgment.service.WeeklyJudgmentBatchService.JudgmentSummary;
import com.jandilog.team.dto.SentInvitationResponse;
import com.jandilog.team.service.TeamInvitationService;
import com.jandilog.team.service.TeamLeaveService;
import com.jandilog.testsupport.judgment.JudgmentFixture.JudgmentRow;
import com.jandilog.testsupport.judgment.JudgmentFixture.WarningRow;
import com.jandilog.testsupport.judgment.JudgmentPipelineIntegrationTest;
import com.jandilog.warning.domain.WarningRecalcResult.Calculated;

// 팀 소속이 판정과 경고에 미치는 영향을 주를 건너 확인하는 시나리오 (기능명세서 5·6장, Q-04, E-36, E-38).
// 소속은 주 종료 시각(일요일 23:59) 스냅샷이고, 경고 카테고리는 판정 시점 소속 팀 전부다
class JudgmentSixWeekTeamSnapshotIntegrationTest extends JudgmentPipelineIntegrationTest {

	@Autowired
	private TeamLeaveService teamLeaveService;
	@Autowired
	private TeamInvitationService invitationService;
	@Autowired
	private JudgmentCorrectionService correctionService;

	@Test
	void 주_종료_시각의_소속이_판정_소속과_경고_카테고리가_되고_여러_팀이어도_판정과_경고는_하나다() {
		long teamA = team(outsider());
		long teamB = team(outsider());
		Person mover = settled("mover", teamA);
		Person multi = settled("multi", teamA, teamB);
		plan(mover, "FFPPFP");
		plan(multi, "FPPPPP");

		mondayRun(0);
		assertThat(fixture.judgmentTeamIds(row(mover, 0).id())).containsExactly(teamA);
		assertThat(fixture.warningTeamIds(warningOf(mover, 0).id())).containsExactly(teamA);
		assertState("w0", mover, 1, 0);

		// 1주차 일요일 낮에 A팀을 자진 탈퇴하고 저녁에 B팀에 들어간다: 주 종료 시각에는 B팀 소속이다 (Q-04)
		clockAt(at(1, 6, 12, 0));
		teamLeaveService.leave(mover.id(), teamA);
		join(mover, teamB, at(1, 6, 20, 0));
		// 자진 탈퇴는 경고를 그대로 둔다
		assertThat(aliveWarningWeeks(mover)).containsExactly(week(0));
		assertThat(fixture.countRemovedWarningTeams(warningOf(mover, 0).id())).isZero();
		assertState("탈퇴 직후", mover, 1, 0);

		mondayRun(1);
		assertThat(fixture.judgmentTeamIds(row(mover, 1).id())).as("1주차 소속 스냅샷").containsExactly(teamB);
		assertThat(fixture.warningTeamIds(warningOf(mover, 1).id())).as("1주차 경고 카테고리").containsExactly(teamB);
		assertThat(fixture.judgmentTeamIds(row(mover, 0).id())).as("0주차 스냅샷은 그대로").containsExactly(teamA);
		assertState("w1", mover, 2, 0);

		// 두 팀에 속한 회원도 판정은 한 번, 미달 경고는 한 개이고 두 팀이 모두 카테고리다
		JudgmentRow multiWeek0 = row(multi, 0);
		assertThat(multiWeek0.status()).isEqualTo("FAIL");
		assertThat(fixture.judgmentTeamIds(multiWeek0.id())).containsExactlyInAnyOrder(teamA, teamB);
		assertThat(jdbc.queryForObject("select count(*) from weekly_judgment where member_id = ? and week_start = ?",
				Integer.class, multi.id(), week(0))).isEqualTo(1);
		assertThat(fixture.warnings(multi.id())).hasSize(1);
		assertThat(fixture.warningTeamIds(warningOf(multi, 0).id())).containsExactlyInAnyOrder(teamA, teamB);
		assertState("w1", multi, 1, 1);

		mondayRun(2);
		assertState("w2", mover, 2, 1);
		assertState("w2", multi, 0, 0);
		mondayRun(3);
		// 2주 연속 통과: 가장 오래된 0주차 경고가 차감된다
		assertState("w3", mover, 1, 0);
		assertThat(state(mover).deductedWarningWeeks()).containsExactly(week(0));
		mondayRun(4);
		assertThat(fixture.warningTeamIds(warningOf(mover, 4).id())).containsExactly(teamB);
		assertState("w4", mover, 2, 0);
		mondayRun(5);
		assertState("w5", mover, 2, 1);
		assertState("w5", multi, 0, 1);
		for (int w = 2; w <= 5; w++) {
			assertThat(fixture.judgmentTeamIds(row(mover, w).id())).as("w%d 소속 스냅샷", w).containsExactly(teamB);
		}
	}

	@Test
	void 추방은_그_팀_카테고리만_빼고_마지막_팀에서_추방되면_경고가_삭제돼_이후_주에도_되살아나지_않고_자진_탈퇴는_경고를_유지한다() {
		long leaderA = outsider();
		long leaderB = outsider();
		long teamA = team(leaderA);
		long teamB = team(leaderB);
		Person kicked = settled("kicked", teamA, teamB);
		Person leaver = settled("leaver", teamA);
		plan(kicked, "FFF---");
		plan(leaver, "FF----");

		mondayRuns(0, 1);
		assertState("w1", kicked, 2, 0);
		assertState("w1", leaver, 2, 0);

		// 2주차 화요일: A팀에서 추방 → 두 경고 모두 B팀 카테고리가 남아 살아 있다. leaver는 자진 탈퇴
		clockAt(at(2, 1, 10, 0));
		teamLeaveService.kick(leaderA, teamA, kicked.id());
		teamLeaveService.leave(leaver.id(), teamA);

		assertThat(aliveWarningWeeks(kicked)).containsExactly(week(0), week(1));
		for (WarningRow warning : fixture.warnings(kicked.id())) {
			assertThat(fixture.warningTeamIds(warning.id())).containsExactlyInAnyOrder(teamA, teamB);
			assertThat(fixture.countRemovedWarningTeams(warning.id())).as("A팀 카테고리만 빠진다").isEqualTo(1);
		}
		assertState("추방(A) 직후", kicked, 2, 0);
		assertState("자진 탈퇴 직후", leaver, 2, 0);

		// 2주차 판정: kicked는 B팀 소속이라 미달 경고(B팀 카테고리), leaver는 팀이 없어 제외
		mondayRun(2);
		assertThat(fixture.judgmentTeamIds(row(kicked, 2).id())).containsExactly(teamB);
		assertState("w2", kicked, 3, 0);
		assertThat(state(kicked).penaltyTarget()).isTrue();
		assertStatus(leaver, 2, "EXCLUDED");
		assertThat(row(leaver, 2).skipReason()).isEqualTo("NO_TEAM");
		assertState("w2", leaver, 2, 0);

		// 3주차 화요일: B팀에서도 추방 → 남은 카테고리가 없어 세 경고가 모두 삭제 표시되고 벌칙 대상에서도 빠진다
		clockAt(at(3, 1, 10, 0));
		teamLeaveService.kick(leaderB, teamB, kicked.id());

		assertThat(aliveWarningWeeks(kicked)).isEmpty();
		assertThat(fixture.warnings(kicked.id())).hasSize(3).allSatisfy(warning -> {
			assertThat(warning.deletedAt()).isNotNull();
			assertThat(warning.deleteReason()).isEqualTo("KICKED");
		});
		assertState("추방(B) 직후", kicked, 0, 0);

		// 이후 주는 팀이 없어 제외되고, 삭제된 경고는 판정·재계산에서 되살아나지 않는다 (E-60)
		for (int w = 3; w <= 5; w++) {
			JudgmentSummary summary = mondayRun(w);
			assertThat(summary.failed()).isZero();
			assertStatus(kicked, w, "EXCLUDED");
			assertThat(row(kicked, w).skipReason()).isEqualTo("NO_TEAM");
			assertThat(aliveWarningWeeks(kicked)).as("w%d 살아 있는 경고", w).isEmpty();
			assertState("w" + w, kicked, 0, 0);
			// 자진 탈퇴한 leaver의 경고는 계속 유지된다
			assertThat(aliveWarningWeeks(leaver)).containsExactly(week(0), week(1));
			assertState("w" + w, leaver, 2, 0);
		}
		assertThat(fixture.warnings(kicked.id())).hasSize(3);
		assertThat(fixture.warningTeamIds(warningOf(leaver, 0).id())).containsExactly(teamA);
		assertThat(fixture.countRemovedWarningTeams(warningOf(leaver, 0).id())).isZero();
	}

	@Test
	void 팀_없음과_가입_전_주와_첫_참가_주는_GitHub가_죽어도_경고_없이_제외되고_재가입_주는_정상_판정한다() {
		long teamA = team(outsider());
		long teamB = team(outsider());
		LocalDateTime newcomerJoined = at(2, 2, 12, 0);
		// 2주차 수요일에 처음 팀에 들어온다: 0·1주차는 가입 전, 2주차가 첫 참가 주
		Person newcomer = person("newcomer", newcomerJoined);
		join(newcomer, teamA, newcomerJoined);
		plan(newcomer, "PP--PP");
		// 0주차 화요일 첫 참가 → 1주차 수요일 탈퇴 → 3주차 화요일 다른 팀에 재가입
		LocalDateTime rejoinerJoined = at(0, 1, 9, 0);
		Person rejoiner = person("rejoiner", rejoinerJoined);
		join(rejoiner, teamA, rejoinerJoined);
		plan(rejoiner, "PP--PP");
		// 한 번도 팀에 속하지 않은 회원
		Person outsiderPerson = person("noTeam", null);
		plan(outsiderPerson, "PPPPPP");

		// 제외·면제 주는 잔디를 조회하지 않으므로 GitHub가 죽어 있어도 보류되지 않는다
		grass.failFor(login(newcomer), HoldReason.API_ERROR);
		grass.failFor(login(rejoiner), HoldReason.API_ERROR);
		grass.failFor(login(outsiderPerson), HoldReason.API_ERROR);

		mondayRun(0);
		assertStatus(newcomer, 0, "EXCLUDED");
		assertThat(row(newcomer, 0).skipReason()).isEqualTo("NO_TEAM");
		assertStatus(rejoiner, 0, "EXCLUDED");
		assertThat(row(rejoiner, 0).skipReason()).isEqualTo("FIRST_WEEK");
		assertThat(fixture.judgmentTeamIds(row(rejoiner, 0).id())).containsExactly(teamA);

		// 1주차 수요일 탈퇴(1주차 진행 중에 clock을 맞춰 실제 탈퇴 흐름을 탄다)
		clockAt(at(1, 2, 9, 0));
		teamLeaveService.leave(rejoiner.id(), teamA);
		mondayRun(1);
		assertThat(row(newcomer, 1).skipReason()).isEqualTo("NO_TEAM");
		assertThat(row(rejoiner, 1).skipReason()).isEqualTo("NO_TEAM");

		JudgmentSummary week2 = mondayRun(2);
		assertThat(week2.hold()).as("제외 주는 보류가 없다").isZero();
		assertThat(row(newcomer, 2).skipReason()).isEqualTo("FIRST_WEEK");
		assertThat(fixture.judgmentTeamIds(row(newcomer, 2).id())).containsExactly(teamA);
		assertThat(row(rejoiner, 2).skipReason()).isEqualTo("NO_TEAM");
		for (int w = 0; w <= 2; w++) {
			for (Person person : new Person[] {newcomer, rejoiner, outsiderPerson}) {
				JudgmentRow row = row(person, w);
				assertThat(row.status()).as("%s w%d", person, w).isEqualTo("EXCLUDED");
				assertThat(row.verifiedDays()).isNull();
				assertThat(fixture.days(row.id())).isEmpty();
			}
		}
		for (Person person : new Person[] {newcomer, rejoiner, outsiderPerson}) {
			assertThat(fixture.warnings(person.id())).as("%s 제외 주 경고", person).isEmpty();
			assertState("w2", person, 0, 0);
		}

		// 3주차: 다른 팀에 재가입한 회원은 첫 참가 주가 아니라(생애 1회) 정상 판정이라 활동이 없으면 미달이다
		grass.recover(login(newcomer));
		grass.recover(login(rejoiner));
		join(rejoiner, teamB, at(3, 1, 9, 0));
		mondayRun(3);
		assertStatus(newcomer, 3, "FAIL");
		assertStatus(rejoiner, 3, "FAIL");
		assertThat(fixture.warningTeamIds(warningOf(newcomer, 3).id())).containsExactly(teamA);
		assertThat(fixture.warningTeamIds(warningOf(rejoiner, 3).id())).containsExactly(teamB);
		assertState("w3", newcomer, 1, 0);
		assertState("w3", rejoiner, 1, 0);

		mondayRun(4);
		assertState("w4", newcomer, 1, 1);
		assertState("w4", rejoiner, 1, 1);
		mondayRun(5);
		for (Person person : new Person[] {newcomer, rejoiner}) {
			assertState("w5", person, 0, 0);
			assertThat(state(person).deductedWarningWeeks()).containsExactly(week(3));
		}
		// 팀이 없는 회원은 끝까지 제외이고 경고가 없다
		assertStatus(outsiderPerson, 5, "EXCLUDED");
		assertThat(fixture.warnings(outsiderPerson.id())).isEmpty();
	}

	@Test
	void 추방된_회원이_아이디_초대로_돌아오면_그_팀_카테고리가_복원되고_삭제됐던_경고가_다시_살아나_이어지는_판정에_반영된다() {
		long leaderId = outsider();
		long teamA = team(leaderId);
		Person kicked = settled("kicked", teamA);
		plan(kicked, "FFF--P");

		mondayRuns(0, 2);
		assertThat(state(kicked).penaltyTarget()).isTrue();

		clockAt(at(3, 1, 10, 0));
		teamLeaveService.kick(leaderId, teamA, kicked.id());
		assertThat(aliveWarningWeeks(kicked)).isEmpty();
		assertState("추방 직후", kicked, 0, 0);

		mondayRuns(3, 4);
		assertStatus(kicked, 3, "EXCLUDED");
		assertStatus(kicked, 4, "EXCLUDED");
		assertState("w4", kicked, 0, 0);

		// 5주차 화요일: 팀장이 GitHub 아이디로 다시 초대하고 수락하면 카테고리와 경고 삭제 표시가 되돌아온다 (Q-02)
		clockAt(at(5, 1, 10, 0));
		SentInvitationResponse invitation = invitationService.invite(leaderId, teamA, login(kicked));
		invitationService.accept(kicked.id(), invitation.id());

		assertThat(aliveWarningWeeks(kicked)).containsExactly(week(0), week(1), week(2));
		for (WarningRow warning : fixture.warnings(kicked.id())) {
			assertThat(warning.deletedAt()).isNull();
			assertThat(warning.deleteReason()).isNull();
			assertThat(fixture.countRemovedWarningTeams(warning.id())).isZero();
		}
		Calculated restored = state(kicked);
		assertThat(restored.warningCount()).isEqualTo(3);
		assertThat(restored.penaltyTarget()).isTrue();
		assertThat(restored.penaltyReachedWeek()).isEqualTo(week(2));

		// 돌아온 주도 처음 참가한 주가 아니라 정상 판정이고, 벌칙 대상이라 통과해도 차감되지 않는다
		mondayRun(5);
		assertStatus(kicked, 5, "PASS");
		assertThat(fixture.judgmentTeamIds(row(kicked, 5).id())).containsExactly(teamA);
		assertState("w5", kicked, 3, 0);
	}

	@Test
	void 추방으로_삭제된_경고는_다른_주차를_통과로_정정했다가_미달로_되돌려도_되살아나지_않는다() {
		long adminId = admin();
		long leaderId = outsider();
		long teamA = team(leaderId);
		Person kicked = settled("kicked", teamA);
		plan(kicked, "FFF-");

		mondayRuns(0, 2);
		clockAt(at(3, 1, 10, 0));
		teamLeaveService.kick(leaderId, teamA, kicked.id());
		assertThat(aliveWarningWeeks(kicked)).isEmpty();
		assertState("추방 직후", kicked, 0, 0);

		// 0주차를 통과로 정정했다가 다시 미달로 정정해도 삭제 표시된 경고는 세지 않는다 (E-60)
		long week0 = row(kicked, 0).id();
		clockAt(at(3, 1, 11, 0));
		correctionService.correct(adminId,
				new JudgmentCorrectionInput(Long.toString(week0), JudgmentStatus.PASS, "내용 확인", null));
		assertState("통과 정정 뒤", kicked, 0, 0);
		correctionService.correct(adminId,
				new JudgmentCorrectionInput(Long.toString(week0), JudgmentStatus.FAIL, "다시 확인", null));

		assertState("미달 정정 뒤", kicked, 0, 0);
		assertThat(aliveWarningWeeks(kicked)).isEmpty();
		assertThat(fixture.warnings(kicked.id())).hasSize(3)
				.allSatisfy(warning -> assertThat(warning.deletedAt()).isNotNull());
		mondayRun(3);
		assertStatus(kicked, 3, "EXCLUDED");
		assertState("w3", kicked, 0, 0);
	}

}
