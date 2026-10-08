package com.jandilog.team;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.jandilog.common.exception.ErrorCode;
import com.jandilog.team.dto.SentInvitationResponse;
import com.jandilog.team.support.TeamIntegrationTest;
import com.jandilog.team.support.TeamWarningFixture.WarningRow;

// 추방 후 아이디 재초대로 복귀하면 그 팀 카테고리를 복원하고 경고 삭제 표시를 푼다 (기능명세서 6장, E-14, Q-02 확정)
class TeamReinviteRestoreIntegrationTest extends TeamIntegrationTest {

	private static final LocalDate WEEK_1 = LocalDate.of(2026, 9, 7);
	private static final LocalDate WEEK_2 = LocalDate.of(2026, 9, 14);

	@Test
	void 추방된_사람이_아이디_초대를_수락하면_삭제됐던_경고가_되살아난다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long member = joinedMember(team);
		long warningId = warnings.warning(member, WEEK_1, team.id());
		leaveService.kick(leader, team.id(), member);
		assertThat(warnings.warning(warningId).alive()).isFalse();

		inviteAndAccept(leader, team, member);

		WarningRow warning = warnings.warning(warningId);
		assertThat(warning.alive()).isTrue();
		assertThat(warning.deletedAt()).isNull();
		assertThat(warning.deleteReason()).isNull();
		assertThat(warnings.category(warningId, team.id()).removed()).isFalse();
		assertThat(warnings.aliveCount(member)).isEqualTo(1);
	}

	@Test
	void 소프트_삭제를_우회로_쓰지_못한다_추방_재초대_복귀로_경고가_사라지지_않는다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long member = joinedMember(team);
		long first = warnings.warning(member, WEEK_1, team.id());
		long second = warnings.warning(member, WEEK_2, team.id());

		leaveService.kick(leader, team.id(), member);
		inviteAndAccept(leader, team, member);

		assertThat(warnings.aliveCount(member)).isEqualTo(2);
		assertThat(warnings.warning(first).alive()).isTrue();
		assertThat(warnings.warning(second).alive()).isTrue();
	}

	@Test
	void 초대를_받기만_하거나_거절하거나_코드로_들어가려는_것으로는_복원되지_않는다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long member = joinedMember(team);
		long warningId = warnings.warning(member, WEEK_1, team.id());
		leaveService.kick(leader, team.id(), member);

		SentInvitationResponse sent = invitationService.invite(leader, team.id(), loginOf(member));
		assertThat(warnings.warning(warningId).alive()).isFalse();
		assertApiError(() -> joinService.joinByCode(member, team.inviteCode()), ErrorCode.TEAM_JOIN_BLOCKED);
		invitationService.decline(member, sent.id());

		assertThat(warnings.warning(warningId).alive()).isFalse();
		assertThat(warnings.warning(warningId).deleteReason()).isEqualTo("KICKED");
		assertThat(warnings.category(warningId, team.id()).removed()).isTrue();
	}

	@Test
	void 다른_팀_카테고리가_있던_경고는_복귀한_팀_카테고리만_되살린다() {
		long leaderA = member();
		long leaderB = member();
		CreatedTeam teamA = newTeam(leaderA);
		CreatedTeam teamB = newTeam(leaderB);
		long member = joinedMember(teamA);
		joinService.joinByCode(member, teamB.inviteCode());
		long warningId = warnings.warning(member, WEEK_1, teamA.id(), teamB.id());
		leaveService.kick(leaderA, teamA.id(), member);
		assertThat(warnings.warning(warningId).alive()).isTrue();

		inviteAndAccept(leaderA, teamA, member);

		assertThat(warnings.warning(warningId).alive()).isTrue();
		assertThat(warnings.category(warningId, teamA.id()).removed()).isFalse();
		assertThat(warnings.category(warningId, teamB.id()).removed()).isFalse();
	}

	@Test
	void 두_팀_모두에서_추방돼_삭제된_경고는_한_팀으로_복귀해도_되살아나고_그_팀_카테고리만_복원된다() {
		long leaderA = member();
		long leaderB = member();
		CreatedTeam teamA = newTeam(leaderA);
		CreatedTeam teamB = newTeam(leaderB);
		long member = joinedMember(teamA);
		joinService.joinByCode(member, teamB.inviteCode());
		long warningId = warnings.warning(member, WEEK_1, teamA.id(), teamB.id());
		leaveService.kick(leaderA, teamA.id(), member);
		leaveService.kick(leaderB, teamB.id(), member);
		assertThat(warnings.warning(warningId).alive()).isFalse();

		inviteAndAccept(leaderA, teamA, member);

		assertThat(warnings.warning(warningId).alive()).isTrue();
		assertThat(warnings.warning(warningId).deleteReason()).isNull();
		assertThat(warnings.category(warningId, teamA.id()).removed()).isFalse();
		assertThat(warnings.category(warningId, teamB.id()).removed()).isTrue();
	}

	@Test
	void 복귀한_뒤_다시_추방하면_경고가_다시_삭제_처리된다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long member = joinedMember(team);
		long warningId = warnings.warning(member, WEEK_1, team.id());
		leaveService.kick(leader, team.id(), member);
		inviteAndAccept(leader, team, member);
		assertThat(warnings.warning(warningId).alive()).isTrue();

		leaveService.kick(leader, team.id(), member);

		WarningRow warning = warnings.warning(warningId);
		assertThat(warning.alive()).isFalse();
		assertThat(warning.deleteReason()).isEqualTo("KICKED");
		assertThat(warnings.category(warningId, team.id()).removed()).isTrue();
	}

	@Test
	void 자진_탈퇴했다가_초대로_돌아오는_것은_경고를_건드리지_않는다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long member = joinedMember(team);
		long warningId = warnings.warning(member, WEEK_1, team.id());
		leaveService.leave(member, team.id());

		inviteAndAccept(leader, team, member);

		assertThat(warnings.warning(warningId).alive()).isTrue();
		assertThat(warnings.category(warningId, team.id()).removed()).isFalse();
		assertThat(warnings.aliveCount(member)).isEqualTo(1);
	}

	@Test
	void 복귀해도_다른_추방자의_경고는_그대로_삭제된_상태다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long returning = joinedMember(team);
		long stayingOut = joinedMember(team);
		long returningWarning = warnings.warning(returning, WEEK_1, team.id());
		long stayingOutWarning = warnings.warning(stayingOut, WEEK_1, team.id());
		leaveService.kick(leader, team.id(), returning);
		leaveService.kick(leader, team.id(), stayingOut);

		inviteAndAccept(leader, team, returning);

		assertThat(warnings.warning(returningWarning).alive()).isTrue();
		assertThat(warnings.warning(stayingOutWarning).alive()).isFalse();
		assertThat(warnings.category(stayingOutWarning, team.id()).removed()).isTrue();
	}

	@Test
	void 팀_삭제로_빠진_다른_팀의_경고는_복귀한_팀과_무관하게_삭제된_채_남는다() {
		long leaderA = member();
		long leaderB = member();
		CreatedTeam teamA = newTeam(leaderA);
		CreatedTeam teamB = newTeam(leaderB);
		long member = joinedMember(teamA);
		joinService.joinByCode(member, teamB.inviteCode());
		long inA = warnings.warning(member, WEEK_1, teamA.id());
		long inB = warnings.warning(member, WEEK_2, teamB.id());
		leaveService.kick(leaderA, teamA.id(), member);
		leaveService.delete(leaderB, teamB.id());

		inviteAndAccept(leaderA, teamA, member);

		assertThat(warnings.warning(inA).alive()).isTrue();
		WarningRow inBRow = warnings.warning(inB);
		assertThat(inBRow.alive()).isFalse();
		assertThat(inBRow.deleteReason()).isEqualTo("TEAM_DELETED");
	}

	@Test
	void 경고가_없던_추방자도_문제없이_복귀한다() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long member = joinedMember(team);
		leaveService.kick(leader, team.id(), member);

		inviteAndAccept(leader, team, member);

		assertThat(isActiveMember(team.id(), member)).isTrue();
		assertThat(warnings.aliveCount(member)).isZero();
	}

}
