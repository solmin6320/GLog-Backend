package com.jandilog.team;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.jandilog.team.support.TeamIntegrationTest;
import com.jandilog.team.support.TeamWarningFixture.CategoryRow;
import com.jandilog.team.support.TeamWarningFixture.WarningRow;

// 추방 차단이 끝난 뒤 초대코드로 다시 참가해도 경고 카테고리는 복원되지 않고, 아이디 재초대로 복귀할 때만 복원된다
// (기능명세서 6장 "아이디 재초대 복귀", FC-02 "아니오 → END", Q-02, 에이전트 회의 2026-10-07 C4 현행 유지)
class TeamRejoinNoRestoreIntegrationTest extends TeamIntegrationTest {

	private static final LocalDate WEEK_1 = LocalDate.of(2026, 9, 7);

	// 경고를 받은 팀원이 추방되고 1년 차단이 끝난 상태
	private record KickedWithWarning(long leader, CreatedTeam team, long member, long warningId, CategoryRow removed,
			WarningRow deleted) {
	}

	private KickedWithWarning 경고_받고_추방됐다가_차단이_만료된_팀원() {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		long member = joinedMember(team);
		long warningId = warnings.warning(member, WEEK_1, team.id());
		leaveService.kick(leader, team.id(), member);
		// 1년이 지나 차단 키가 사라진 상태
		redis.delete(banKey(team.id(), member));

		CategoryRow removed = warnings.category(warningId, team.id());
		WarningRow deleted = warnings.warning(warningId);
		assertThat(removed.removed()).isTrue();
		assertThat(deleted.alive()).isFalse();
		assertThat(deleted.deleteReason()).isEqualTo("KICKED");
		return new KickedWithWarning(leader, team, member, warningId, removed, deleted);
	}

	@Test
	void 차단이_만료된_뒤_초대코드로_다시_참가해도_경고_카테고리와_삭제_표시는_그대로다() {
		KickedWithWarning kicked = 경고_받고_추방됐다가_차단이_만료된_팀원();

		joinService.joinByCode(kicked.member(), kicked.team().inviteCode());

		assertThat(isActiveMember(kicked.team().id(), kicked.member())).isTrue();
		assertThat(currentMembership(kicked.team().id(), kicked.member()).get("joined_by")).isEqualTo("INVITE_CODE");

		CategoryRow category = warnings.category(kicked.warningId(), kicked.team().id());
		assertThat(category.removed()).isTrue();
		assertThat(category.removedAt()).isEqualTo(kicked.removed().removedAt());

		WarningRow warning = warnings.warning(kicked.warningId());
		assertThat(warning.alive()).isFalse();
		assertThat(warning.deletedAt()).isEqualTo(kicked.deleted().deletedAt());
		assertThat(warning.deleteReason()).isEqualTo("KICKED");
		assertThat(warnings.aliveCount(kicked.member())).isZero();
	}

	@Test
	void 같은_상황에서_아이디_재초대로_복귀하면_경고_카테고리와_삭제_표시가_복원된다() {
		KickedWithWarning kicked = 경고_받고_추방됐다가_차단이_만료된_팀원();

		inviteAndAccept(kicked.leader(), kicked.team(), kicked.member());

		assertThat(isActiveMember(kicked.team().id(), kicked.member())).isTrue();
		assertThat(currentMembership(kicked.team().id(), kicked.member()).get("joined_by")).isEqualTo("INVITATION");

		CategoryRow category = warnings.category(kicked.warningId(), kicked.team().id());
		assertThat(category.removed()).isFalse();
		assertThat(category.removedAt()).isNull();

		WarningRow warning = warnings.warning(kicked.warningId());
		assertThat(warning.alive()).isTrue();
		assertThat(warning.deletedAt()).isNull();
		assertThat(warning.deleteReason()).isNull();
		assertThat(warnings.aliveCount(kicked.member())).isEqualTo(1);
	}

}
