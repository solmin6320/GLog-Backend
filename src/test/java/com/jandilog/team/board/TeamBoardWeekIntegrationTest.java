package com.jandilog.team.board;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.jandilog.post.domain.PostType;
import com.jandilog.post.dto.PostResponse;

// 팀 현황판·내 팀 목록 요약의 주 경계 (월요일 00:00 ~ 일요일 23:59:59 KST, DB명세서 1-0)와 첫 참가 주·면제·확정 판정이 상태에 주는 영향.
// 시각은 모두 KST로 적고 주는 2026-12-07(월)~12-13(일). 12-13 23:59:59와 12-14 00:00:00이 한 주의 끝과 다음 주의 시작이다
class TeamBoardWeekIntegrationTest extends TeamBoardTestBase {

	private static final LocalDate MON = LocalDate.of(2026, 12, 7);
	private static final LocalDate TUE = MON.plusDays(1);
	private static final LocalDate WED = MON.plusDays(2);
	private static final LocalDate THU = MON.plusDays(3);
	private static final LocalDate SUN = MON.plusDays(6);
	private static final LocalDate NEXT_MON = MON.plusDays(7);

	private long leader;
	private long mate;
	private TeamRef team;

	@BeforeEach
	void setUpScenario() {
		at("2026-12-09T12:07:13");
		leader = member();
		mate = member();
		team = createTeam(leader, true);
		join(mate, team);
		settleFirstWeek(leader, mate);
	}

	// ----- 주 경계 -----

	@ParameterizedTest
	@CsvSource({
			"2026-12-06T23:59:59, 2026-11-30, 2026-12-06",
			"2026-12-07T00:00:00, 2026-12-07, 2026-12-13",
			"2026-12-07T00:00:01, 2026-12-07, 2026-12-13",
			"2026-12-13T23:59:58, 2026-12-07, 2026-12-13",
			"2026-12-13T23:59:59, 2026-12-07, 2026-12-13",
			"2026-12-14T00:00:00, 2026-12-14, 2026-12-20",
			"2026-12-14T00:00:01, 2026-12-14, 2026-12-20"})
	void 한_주는_월요일_0시_KST에_시작해서_일요일_23시_59분_59초에_끝난다(String now, String weekStart, String weekEnd) {
		at(now);

		JsonNode board = board(leader, team.id());

		assertThat(board.path("weekStart").asText()).isEqualTo(weekStart);
		assertThat(board.path("weekEnd").asText()).isEqualTo(weekEnd);
		assertThat(board.path("members").get(0).path("days").get(0).path("date").asText()).isEqualTo(weekStart);
		assertThat(summaryOf(myTeams(leader), team.id()).path("weekStart").asText()).isEqualTo(weekStart);
	}

	@Test
	void 일요일_23시_59분_59초에_쓴_글은_그_주_기록이고_월요일_0시_정각에_쓴_글은_다음_주_기록이다() {
		PostResponse sunday = postAt("2026-12-13T23:59:59", leader, PostType.DEVLOG, "일요일 글", true, team.id());
		PostResponse monday = postAt("2026-12-14T00:00:00", leader, PostType.DEVLOG, "월요일 글", true, team.id());

		// 작성일은 서버 저장 시각의 KST 날짜다. UTC 날짜였다면 월요일 글이 일요일로 잡힌다
		assertThat(sunday.writtenDate()).isEqualTo("2026-12-13");
		assertThat(monday.writtenDate()).isEqualTo("2026-12-14");

		at("2026-12-13T23:59:59");
		JsonNode thisWeek = rowOf(board(leader, team.id()), leader);
		assertThat(thisWeek.path("recordCount").asInt()).isEqualTo(1);
		assertThat(thisWeek.path("days").get(6).path("hasRecord").asBoolean()).isTrue();
		assertThat(thisWeek.path("days").get(0).path("hasRecord").asBoolean()).isFalse();

		at("2026-12-14T00:00:00");
		JsonNode nextWeekBoard = board(leader, team.id());
		JsonNode nextWeek = rowOf(nextWeekBoard, leader);
		assertThat(nextWeek.path("recordCount").asInt()).isEqualTo(1);
		assertThat(nextWeek.path("days").get(0).path("hasRecord").asBoolean()).isTrue();
		assertThat(nextWeek.path("days").get(6).path("hasRecord").asBoolean()).isFalse();
		// 이 팀의 글 목록은 주와 상관없이 연결된 글 최신순이다
		assertThat(titlesOf(nextWeekBoard)).containsExactly("월요일 글", "일요일 글");
	}

	@Test
	void 일요일에_찍힌_잔디는_그_주_인증일이고_월요일_0시에는_새_주로_처음부터_센다() {
		postAt("2026-12-09T12:30:41", leader, PostType.DEVLOG, "수요일 글", true, team.id());
		// 일요일 새벽 갱신 캐시: 월·화·일 잔디
		seedGrass(leader, SUN, Set.of(MON, TUE, SUN));

		at("2026-12-13T23:59:59");
		JsonNode sunday = board(leader, team.id());
		JsonNode sundayLeader = rowOf(sunday, leader);
		assertThat(sundayLeader.path("verifiedDays").asInt()).isEqualTo(4);
		assertThat(sundayLeader.path("status").asText()).isEqualTo("PASS");
		// 주가 끝나기 전에는 미달로 확정하지 않는다
		assertThat(rowOf(sunday, mate).path("status").asText()).isEqualTo("NOT_YET");
		assertThat(counts(summaryOf(myTeams(leader), team.id()))).containsExactly(1, 0, 1);

		// 월요일 0시: 오늘 캐시가 아직 없어 어제 캐시를 쓰지만, 일요일의 잔디와 지난 주 글은 새 주에 들어오지 않는다
		at("2026-12-14T00:00:00");
		JsonNode monday = board(leader, team.id());
		JsonNode mondayLeader = rowOf(monday, leader);
		assertThat(monday.path("weekStart").asText()).isEqualTo("2026-12-14");
		assertThat(mondayLeader.path("status").asText()).isEqualTo("NOT_YET");
		assertThat(mondayLeader.path("recordCount").asInt()).isZero();
		assertThat(mondayLeader.path("days")).allSatisfy(day -> assertThat(day.path("hasGrass").asBoolean()).isFalse());
		assertThat(counts(summaryOf(myTeams(leader), team.id()))).containsExactly(0, 0, 2);
	}

	@Test
	void 오늘_캐시가_없으면_어제_캐시를_쓰고_오늘_캐시가_생기면_그것으로_바뀐다() {
		seedGrass(leader, SUN, Set.of(MON));

		at("2026-12-14T00:00:00");
		assertThat(board(leader, team.id()).path("grassFetchedAt").asText()).isEqualTo("2026-12-13T06:00:00");

		seedGrass(leader, NEXT_MON, Set.of(NEXT_MON));
		at("2026-12-14T07:00:00");
		JsonNode board = board(leader, team.id());

		assertThat(board.path("grassFetchedAt").asText()).isEqualTo("2026-12-14T06:00:00");
		JsonNode row = rowOf(board, leader);
		assertThat(row.path("days").get(0).path("hasGrass").asBoolean()).isTrue();
		assertThat(row.path("verifiedDays").asInt()).isEqualTo(1);
	}

	// ----- 첫 참가 주 -----

	@Test
	void 첫_참가_주_제외는_일요일_23시_59분_59초_참가까지이고_월요일_0시_정각_참가는_그_주가_첫_참가_주다() {
		long sundayJoiner = member();
		long mondayJoiner = member();

		at("2026-12-13T23:59:59");
		join(sundayJoiner, team);
		assertThat(rowOf(board(leader, team.id()), sundayJoiner).path("status").asText()).isEqualTo("EXCLUDED");

		// 다음 주부터는 판정 대상이라 제외가 풀린다. 월요일 0시에 참가한 사람은 그 주가 첫 참가 주다
		at("2026-12-14T00:00:00");
		join(mondayJoiner, team);
		JsonNode monday = board(leader, team.id());
		assertThat(rowOf(monday, sundayJoiner).path("status").asText()).isEqualTo("NOT_YET");
		assertThat(rowOf(monday, mondayJoiner).path("status").asText()).isEqualTo("EXCLUDED");
		assertThat(counts(summaryOf(myTeams(leader), team.id()))).containsExactly(0, 0, 3);

		at("2026-12-21T00:00:00");
		assertThat(rowOf(board(leader, team.id()), mondayJoiner).path("status").asText()).isEqualTo("NOT_YET");
	}

	// ----- 지난 주·이번 주 판정 행 -----

	@Test
	void 지난_주_확정_판정은_이번_주_현황과_요약에_섞이지_않는다() {
		judgment(leader, MON.minusDays(7), "FAIL", 1, 0, Set.of(MON.minusDays(6)), Set.of());
		judgment(mate, MON.minusDays(7), "PASS", 3, 1, Set.of(), Set.of(MON.minusDays(7), MON.minusDays(6), MON.minusDays(5)));
		postAt("2026-12-09T12:31:42", leader, PostType.DEVLOG, "이번 주 글", true, team.id());
		seedGrass(leader, WED, Set.of(MON, TUE, WED));

		JsonNode board = board(leader, team.id());

		assertThat(rowOf(board, leader).path("status").asText()).isEqualTo("PASS");
		assertThat(rowOf(board, leader).path("verifiedDays").asInt()).isEqualTo(3);
		assertThat(rowOf(board, mate).path("status").asText()).isEqualTo("NOT_YET");
		assertThat(counts(summaryOf(myTeams(leader), team.id()))).containsExactly(1, 0, 1);
	}

	@Test
	void 이번_주_판정이_이미_확정돼_있으면_확정_결과를_쓰고_보류는_무시한다() {
		long exempt = member();
		long excluded = member();
		long hold = member();
		join(exempt, team);
		join(excluded, team);
		join(hold, team);
		settleFirstWeek(exempt, excluded, hold);
		// 확정 행이 있는 주는 지금 계산 값이 달라도 확정 결과가 이긴다: 리더 근거는 잔디 3일+글인데 확정은 미달
		judgment(leader, MON, "FAIL", 1, 0, Set.of(TUE), Set.of());
		judgment(mate, MON, "PASS", 4, 1, Set.of(MON, TUE, WED), Set.of(THU));
		judgment(exempt, MON, "EXEMPT", null, null, Set.of(), Set.of());
		judgment(excluded, MON, "EXCLUDED", null, null, Set.of(), Set.of());
		jdbc.update("insert into weekly_judgment (member_id, week_start, status, hold_reason, retry_count, corrected)"
				+ " values (?, ?, 'HOLD', 'API_ERROR', 0, false)", hold, MON);
		postAt("2026-12-09T12:32:43", leader, PostType.DEVLOG, "리더 글", true, team.id());
		seedGrass(leader, WED, Set.of(MON, TUE, WED));

		JsonNode board = board(leader, team.id());

		JsonNode failed = rowOf(board, leader);
		assertThat(failed.path("status").asText()).isEqualTo("FAIL");
		assertThat(failed.path("verifiedDays").asInt()).isEqualTo(1);
		assertThat(failed.path("recordCount").asInt()).isZero();
		assertThat(failed.path("days").get(1).path("hasGrass").asBoolean()).isTrue();
		assertThat(failed.path("days").get(0).path("hasGrass").asBoolean()).isFalse();
		JsonNode passed = rowOf(board, mate);
		assertThat(passed.path("status").asText()).isEqualTo("PASS");
		assertThat(passed.path("verifiedDays").asInt()).isEqualTo(4);
		assertThat(passed.path("recordCount").asInt()).isEqualTo(1);
		assertThat(rowOf(board, exempt).path("status").asText()).isEqualTo("EXCLUDED");
		assertThat(rowOf(board, excluded).path("status").asText()).isEqualTo("EXCLUDED");
		// 보류는 확정이 아니라 지금 값으로 본다
		assertThat(rowOf(board, hold).path("status").asText()).isEqualTo("NOT_YET");
		// 요약: 면제·제외는 어느 쪽에도 세지 않고 미달은 확정된 사람만
		assertThat(counts(summaryOf(myTeams(leader), team.id()))).containsExactly(1, 1, 1);
	}

	// ----- 개인 면제 -----

	@Test
	void 승인된_이번_주_개인_면제만_제외이고_대기_거절_다른_주_면제는_영향이_없다() {
		long pendingOne = member();
		long rejectedOne = member();
		long nextWeekOne = member();
		long lastWeekOne = member();
		for (long id : new long[] {pendingOne, rejectedOne, nextWeekOne, lastWeekOne}) {
			join(id, team);
		}
		settleFirstWeek(pendingOne, rejectedOne, nextWeekOne, lastWeekOne);
		personalExemption(mate, MON, "APPROVED", leader);
		personalExemption(pendingOne, MON, "PENDING", leader);
		personalExemption(rejectedOne, MON, "REJECTED", leader);
		personalExemption(nextWeekOne, NEXT_MON, "APPROVED", leader);
		personalExemption(lastWeekOne, MON.minusDays(7), "APPROVED", leader);

		JsonNode board = board(leader, team.id());

		assertThat(rowOf(board, mate).path("status").asText()).isEqualTo("EXCLUDED");
		assertThat(rowOf(board, leader).path("status").asText()).isEqualTo("NOT_YET");
		assertThat(rowOf(board, pendingOne).path("status").asText()).isEqualTo("NOT_YET");
		assertThat(rowOf(board, rejectedOne).path("status").asText()).isEqualTo("NOT_YET");
		assertThat(rowOf(board, nextWeekOne).path("status").asText()).isEqualTo("NOT_YET");
		assertThat(rowOf(board, lastWeekOne).path("status").asText()).isEqualTo("NOT_YET");
		assertThat(counts(summaryOf(myTeams(leader), team.id()))).containsExactly(0, 0, 5);

		// 면제 주가 지나면 다음 주는 다시 대상이다
		at("2026-12-14T00:00:00");
		assertThat(rowOf(board(leader, team.id()), mate).path("status").asText()).isEqualTo("NOT_YET");
		assertThat(rowOf(board(leader, team.id()), nextWeekOne).path("status").asText()).isEqualTo("EXCLUDED");
	}

}
