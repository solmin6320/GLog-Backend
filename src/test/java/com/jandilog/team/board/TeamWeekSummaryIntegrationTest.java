package com.jandilog.team.board;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.jandilog.post.domain.PostType;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;

// 내 팀 목록의 이번 주 요약(TM-01 ⑧, "통과 n명 · 미달 n명 · 아직 n명")의 경계: 팀 없음, 가입 직후, 여러 팀, 팀원 변동.
// 요약은 팀 현황판과 같은 규칙으로 센다. 판정 제외·면제인 팀원은 어느 쪽에도 세지 않는다.
// 이번 주는 2026-12-07(월)~12-13(일), 시각은 12-09(수)
class TeamWeekSummaryIntegrationTest extends TeamBoardTestBase {

	private static final LocalDate MON = LocalDate.of(2026, 12, 7);
	private static final LocalDate TUE = MON.plusDays(1);
	private static final LocalDate WED = MON.plusDays(2);
	private static final String CREATE_TEAM = """
			mutation($input: CreateTeamInput!) {
			  createTeam(input: $input) {
			    inviteCode
			    team { id weekSummary { weekStart passCount failCount notYetCount } }
			  }
			}
			""";
	private static final String TEAM_SUMMARY = """
			query($id: ID!) { team(id: $id) { id weekSummary { weekStart passCount failCount notYetCount } } }
			""";

	private int postSeconds;

	@BeforeEach
	void setUpClock() {
		at("2026-12-09T12:07:13");
	}

	// 통과 조건을 이미 채운 상태: 잔디 월·화·수와 수요일 기록글 1개
	private void makePass(long memberId, long teamId) {
		seedGrass(memberId, WED, Set.of(MON, TUE, WED));
		String time = LocalDateTime.of(2026, 12, 9, 12, 20, 0).plusSeconds(++postSeconds).toString();
		postAt(time, memberId, PostType.DEVLOG, "통과-" + postSeconds, true, teamId);
	}

	@Test
	void 팀이_하나도_없으면_내_팀_목록은_빈_목록이다() {
		long alone = member();

		assertThat(myTeams(alone)).isEmpty();
	}

	@Test
	void 방금_만든_팀은_팀장이_첫_참가_주라_요약이_모두_0이고_다음_주부터_센다() {
		long creator = member();

		GraphQlResponse created = graphQl.post(bearerFor(creator), CREATE_TEAM,
				Map.of("input", Map.of("name", "요약-" + members.tag(), "isPublic", true)));
		assertThat(created.hasErrors()).as(created.rawBody()).isFalse();
		JsonNode team = created.data().path("createTeam").path("team");
		long teamId = team.path("id").asLong();
		trackTeam(teamId);

		assertThat(team.path("weekSummary").path("weekStart").asText()).isEqualTo("2026-12-07");
		assertThat(counts(team.path("weekSummary"))).containsExactly(0, 0, 0);
		assertThat(counts(summaryOf(myTeams(creator), teamId))).containsExactly(0, 0, 0);

		// 첫 참가 주가 지나면 판정 대상이라 아직으로 센다
		at("2026-12-14T00:00:00");
		JsonNode nextWeek = summaryOf(myTeams(creator), teamId);
		assertThat(nextWeek.path("weekStart").asText()).isEqualTo("2026-12-14");
		assertThat(counts(nextWeek)).containsExactly(0, 0, 1);
	}

	@Test
	void 방금_가입한_팀원은_요약에_세지_않고_팀원_수와_상관없이_센_인원만_나온다() {
		long leader = member();
		long old = member();
		TeamRef team = createTeam(leader, true);
		join(old, team);
		settleFirstWeek(leader, old);
		long newcomer = member();
		join(newcomer, team);

		JsonNode summary = summaryOf(myTeams(leader), team.id());

		assertThat(counts(summary)).containsExactly(0, 0, 2);
		// 방금 가입한 사람이 보는 목록에서도 같은 요약이다
		assertThat(summaryOf(myTeams(newcomer), team.id())).isEqualTo(summary);
	}

	@Test
	void 여러_팀에_속하면_팀마다_따로_세고_같은_회원은_속한_팀마다_센다() {
		long x1 = member();
		long m = member();
		long p1 = member();
		long q1 = member();
		long x3 = member();
		long r1 = member();
		long r2 = member();
		TeamRef t1 = createTeam(x1, true);
		at("2026-12-09T12:07:20");
		join(m, t1);
		join(p1, t1);
		at("2026-12-09T12:07:30");
		TeamRef t2 = createTeam(m, false);
		join(q1, t2);
		TeamRef t3 = createTeam(x3, true);
		at("2026-12-09T12:07:40");
		join(m, t3);
		join(r1, t3);
		join(r2, t3);
		settleFirstWeek(x1, m, p1, q1, x3, r1, r2);
		personalExemption(r2, MON, "APPROVED", x3);
		makePass(m, t1.id());
		makePass(p1, t1.id());
		makePass(x3, t3.id());
		makePass(r1, t3.id());
		// 내가 속하지 않은 팀
		long stranger = member();
		TeamRef t4 = createTeam(stranger, true);
		settleFirstWeek(stranger);

		JsonNode mine = myTeams(m);

		assertThat(mine).hasSize(3);
		assertThat(mine.get(0).path("id").asLong()).isEqualTo(t1.id());
		assertThat(mine.get(1).path("id").asLong()).isEqualTo(t2.id());
		assertThat(mine.get(2).path("id").asLong()).isEqualTo(t3.id());
		assertThat(counts(summaryOf(mine, t1.id()))).containsExactly(2, 0, 1);
		assertThat(counts(summaryOf(mine, t2.id()))).containsExactly(1, 0, 1);
		assertThat(counts(summaryOf(mine, t3.id()))).containsExactly(3, 0, 0);
		assertThat(summaryOf(mine, t4.id())).isNull();
		// 같은 팀은 누가 봐도 같은 요약이고 팀 조회로 받은 요약과도 같다
		assertThat(summaryOf(myTeams(x1), t1.id())).isEqualTo(summaryOf(mine, t1.id()));
		GraphQlResponse byTeam = graphQl.post(bearerFor(m), TEAM_SUMMARY, Map.of("id", Long.toString(t3.id())));
		assertThat(byTeam.hasErrors()).as(byTeam.rawBody()).isFalse();
		assertThat(byTeam.data().path("team").path("weekSummary")).isEqualTo(summaryOf(mine, t3.id()));
	}

	@Test
	void 팀원이_나가거나_추방되면_요약에서_빠지고_팀이_사라지면_목록에서도_사라진다() {
		long leader = member();
		long passer = member();
		long waiter = member();
		TeamRef a = createTeam(leader, true);
		join(passer, a);
		join(waiter, a);
		TeamRef b = createTeam(leader, true);
		join(passer, b);
		settleFirstWeek(leader, passer, waiter);
		makePass(passer, a.id());

		JsonNode first = myTeams(leader);
		assertThat(counts(summaryOf(first, a.id()))).containsExactly(1, 0, 2);
		assertThat(counts(summaryOf(first, b.id()))).containsExactly(1, 0, 1);

		leaveService.leave(passer, a.id());
		JsonNode afterLeave = myTeams(leader);
		assertThat(counts(summaryOf(afterLeave, a.id()))).containsExactly(0, 0, 2);
		assertThat(counts(summaryOf(afterLeave, b.id()))).containsExactly(1, 0, 1);
		assertThat(summaryOf(myTeams(passer), a.id())).isNull();

		leaveService.kick(leader, a.id(), waiter);
		assertThat(counts(summaryOf(myTeams(leader), a.id()))).containsExactly(0, 0, 1);
		assertThat(myTeams(waiter)).isEmpty();

		leaveService.delete(leader, b.id());
		JsonNode afterDelete = myTeams(leader);
		assertThat(afterDelete).hasSize(1);
		assertThat(afterDelete.get(0).path("id").asLong()).isEqualTo(a.id());
		assertThat(myTeams(passer)).isEmpty();
	}

	@Test
	void 일요일_23시_59분_59초까지는_이번_주로_세고_월요일_0시에는_새_주로_처음부터_센다() {
		long leader = member();
		long passer = member();
		TeamRef team = createTeam(leader, true);
		join(passer, team);
		settleFirstWeek(leader, passer);
		makePass(passer, team.id());
		seedGrass(passer, LocalDate.of(2026, 12, 13), Set.of(MON, TUE, WED));

		at("2026-12-13T23:59:59");
		JsonNode sunday = summaryOf(myTeams(leader), team.id());
		assertThat(sunday.path("weekStart").asText()).isEqualTo("2026-12-07");
		// 주가 끝나기 전에는 못 채운 사람도 미달이 아니라 아직이다
		assertThat(counts(sunday)).containsExactly(1, 0, 1);

		at("2026-12-14T00:00:00");
		JsonNode monday = summaryOf(myTeams(leader), team.id());
		assertThat(monday.path("weekStart").asText()).isEqualTo("2026-12-14");
		assertThat(counts(monday)).containsExactly(0, 0, 2);
	}

}
