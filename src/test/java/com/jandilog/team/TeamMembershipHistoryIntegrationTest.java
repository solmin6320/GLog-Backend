package com.jandilog.team;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.jandilog.team.support.TeamIntegrationTest;

// 주 종료 시각 기준 소속 스냅샷 (Q-04 확정, DB명세서 1-3·4-2): joined_at <= 기준 시각 < left_at
class TeamMembershipHistoryIntegrationTest extends TeamIntegrationTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private void at(String kstLocalDateTime) {
		clock.fixAt(LocalDateTime.parse(kstLocalDateTime).atZone(KST).toInstant());
	}

	private static LocalDateTime t(String kstLocalDateTime) {
		return LocalDateTime.parse(kstLocalDateTime);
	}

	private List<Long> teamsAt(long memberId, String kstLocalDateTime) {
		return membershipService.teamIdsAt(memberId, t(kstLocalDateTime));
	}

	@Test
	void 참가_이전은_빠지고_참가한_시각부터_소속이다() {
		long leader = member();
		at("2026-09-07T10:00:00");
		CreatedTeam team = newTeam(leader);
		long joiner = member();
		at("2026-09-08T09:00:00");
		joinService.joinByCode(joiner, team.inviteCode());

		assertThat(teamsAt(joiner, "2026-09-08T08:59:59")).isEmpty();
		assertThat(teamsAt(joiner, "2026-09-08T09:00:00")).containsExactly(team.id());
		assertThat(teamsAt(joiner, "2026-09-30T00:00:00")).containsExactly(team.id());
		assertThat(teamsAt(leader, "2026-09-07T09:59:59")).isEmpty();
		assertThat(teamsAt(leader, "2026-09-07T10:00:00")).containsExactly(team.id());
	}

	@Test
	void 나간_시각_이후에는_소속이_아니고_나가기_직전까지는_소속이다() {
		long leader = member();
		at("2026-09-07T10:00:00");
		CreatedTeam team = newTeam(leader);
		long member = member();
		at("2026-09-08T09:00:00");
		joinService.joinByCode(member, team.inviteCode());
		at("2026-09-10T12:00:00");
		leaveService.leave(member, team.id());

		assertThat(teamsAt(member, "2026-09-10T11:59:59")).containsExactly(team.id());
		// 나간 시각 정각은 이미 소속이 아니다 (left_at > 기준 시각일 때만 소속)
		assertThat(teamsAt(member, "2026-09-10T12:00:00")).isEmpty();
		assertThat(teamsAt(member, "2026-09-12T00:00:00")).isEmpty();
	}

	@Test
	void 나갔다_다시_들어오면_이력_구간마다_소속이_갈린다() {
		long leader = member();
		at("2026-09-07T10:00:00");
		CreatedTeam team = newTeam(leader);
		long member = member();
		at("2026-09-08T09:00:00");
		joinService.joinByCode(member, team.inviteCode());
		at("2026-09-10T12:00:00");
		leaveService.leave(member, team.id());
		at("2026-09-14T09:00:00");
		inviteAndAccept(leader, team, member);
		at("2026-09-17T18:00:00");
		leaveService.kick(leader, team.id(), member);
		at("2026-09-20T08:00:00");
		inviteAndAccept(leader, team, member);

		assertThat(teamsAt(member, "2026-09-09T00:00:00")).containsExactly(team.id());
		assertThat(teamsAt(member, "2026-09-12T00:00:00")).isEmpty();
		assertThat(teamsAt(member, "2026-09-14T09:00:00")).containsExactly(team.id());
		assertThat(teamsAt(member, "2026-09-17T17:59:59")).containsExactly(team.id());
		assertThat(teamsAt(member, "2026-09-18T00:00:00")).isEmpty();
		assertThat(teamsAt(member, "2026-09-21T00:00:00")).containsExactly(team.id());
		assertThat(membershipRows(team.id(), member)).hasSize(3);
	}

	@Test
	void 주_종료_시각_일요일_23시_59분_기준으로_그_순간의_소속만_잡힌다() {
		long leader = member();
		at("2026-09-07T10:00:00");
		CreatedTeam team = newTeam(leader);
		long leftMidWeek = member();
		long joinedAtBoundary = member();
		long joinedAfterBoundary = member();
		at("2026-09-08T09:00:00");
		joinService.joinByCode(leftMidWeek, team.inviteCode());
		at("2026-09-10T12:00:00");
		leaveService.leave(leftMidWeek, team.id());
		// 9/7 월요일 주의 종료 시각은 9/13 일요일 23:59
		at("2026-09-13T23:59:00");
		joinService.joinByCode(joinedAtBoundary, team.inviteCode());
		at("2026-09-14T00:00:00");
		joinService.joinByCode(joinedAfterBoundary, team.inviteCode());

		assertThat(teamsAt(leader, "2026-09-13T23:59:00")).containsExactly(team.id());
		assertThat(teamsAt(leftMidWeek, "2026-09-13T23:59:00")).isEmpty();
		assertThat(teamsAt(joinedAtBoundary, "2026-09-13T23:59:00")).containsExactly(team.id());
		assertThat(teamsAt(joinedAfterBoundary, "2026-09-13T23:59:00")).isEmpty();
	}

	@Test
	void 팀이_삭제되면_삭제_시각부터_소속에서_빠지고_그_전_이력은_남는다() {
		long leader = member();
		at("2026-09-07T10:00:00");
		CreatedTeam team = newTeam(leader);
		long member = member();
		at("2026-09-08T09:00:00");
		joinService.joinByCode(member, team.inviteCode());
		at("2026-09-22T23:59:00");
		leaveService.delete(leader, team.id());

		assertThat(teamsAt(member, "2026-09-22T23:58:59")).containsExactly(team.id());
		assertThat(teamsAt(member, "2026-09-22T23:59:00")).isEmpty();
		assertThat(teamsAt(leader, "2026-09-22T23:58:59")).containsExactly(team.id());
		assertThat(teamsAt(leader, "2026-09-23T00:00:00")).isEmpty();
	}

	@Test
	void 여러_팀_소속은_팀_id_오름차순으로_한_번에_나온다() {
		long a = member();
		long b = member();
		at("2026-09-07T10:00:00");
		CreatedTeam first = newTeam(a);
		CreatedTeam second = newTeam(b);
		long member = member();
		at("2026-09-08T09:00:00");
		joinService.joinByCode(member, second.inviteCode());
		joinService.joinByCode(member, first.inviteCode());

		assertThat(teamsAt(member, "2026-09-09T00:00:00")).containsExactly(first.id(), second.id());
	}

	@Test
	void 여러_회원을_한_번에_물어도_회원별로_갈리고_팀이_없는_회원은_빈_목록이다() {
		long leader = member();
		at("2026-09-07T10:00:00");
		CreatedTeam team = newTeam(leader);
		long inTeam = member();
		long noTeam = member();
		long leftBefore = member();
		at("2026-09-08T09:00:00");
		joinService.joinByCode(inTeam, team.inviteCode());
		joinService.joinByCode(leftBefore, team.inviteCode());
		at("2026-09-09T09:00:00");
		leaveService.leave(leftBefore, team.id());

		Map<Long, List<Long>> result = membershipService.teamIdsAt(List.of(leader, inTeam, noTeam, leftBefore),
				t("2026-09-13T23:59:00"));

		assertThat(result).containsOnlyKeys(leader, inTeam, noTeam, leftBefore);
		assertThat(result.get(leader)).containsExactly(team.id());
		assertThat(result.get(inTeam)).containsExactly(team.id());
		assertThat(result.get(noTeam)).isEmpty();
		assertThat(result.get(leftBefore)).isEmpty();
		assertThat(membershipService.teamIdsAt(List.<Long>of(), t("2026-09-13T23:59:00"))).isEmpty();
	}

	@Test
	void 기록된_참가_시각은_KST_벽시계_값이다() {
		long leader = member();
		clock.fixAt(Instant.parse("2026-09-07T01:30:00Z"));
		CreatedTeam team = newTeam(leader);

		Object joinedAt = currentMembership(team.id(), leader).get("joined_at");

		assertThat(time(joinedAt)).isEqualTo(LocalDateTime.of(2026, 9, 7, 10, 30, 0));
	}

}
