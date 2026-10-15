package com.jandilog.team.board;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.post.domain.PostType;
import com.jandilog.team.dto.UpdateTeamInput;

// 팀 현황판의 팀원 구성 경계: 팀원 1명·여러 명, 공개·비공개 팀, 팀장 위임, 나갔다 돌아온 팀원, 추방·탈퇴 (기능명세서 2장).
// 이번 주는 2026-12-07(월)~12-13(일), 시각은 12-09(수)
class TeamBoardRosterIntegrationTest extends TeamBoardTestBase {

	private static final LocalDate MON = LocalDate.of(2026, 12, 7);
	private static final LocalDate TUE = MON.plusDays(1);
	private static final LocalDate WED = MON.plusDays(2);
	private static final LocalDate PREV_WEEK = MON.minusDays(21);

	private long leader;
	private long second;
	private long third;
	private TeamRef team;

	@BeforeEach
	void setUpScenario() {
		at("2026-12-09T12:07:13");
		leader = member();
		second = member();
		third = member();
		team = createTeam(leader, true);
		// 참가 시각을 달리해서 참가 순서가 분명하게 한다
		at("2026-12-09T12:07:20");
		join(second, team);
		at("2026-12-09T12:07:30");
		join(third, team);
		settleFirstWeek(leader, second, third);
	}

	// ----- 팀원 수 -----

	@Test
	void 팀장_혼자인_팀의_현황판은_한_줄이고_활동이_없으면_모두_0이다() {
		long solo = member();
		TeamRef soloTeam = createTeam(solo, false);
		settleFirstWeek(solo);

		JsonNode board = board(solo, soloTeam.id());

		assertThat(memberIdsOf(board)).containsExactly(solo);
		JsonNode row = rowOf(board, solo);
		assertThat(row.path("isMe").asBoolean()).isTrue();
		assertThat(row.path("status").asText()).isEqualTo("NOT_YET");
		assertThat(row.path("recordCount").asInt()).isZero();
		assertThat(row.path("warningCount").asInt()).isZero();
		assertThat(row.path("days")).hasSize(7);
		assertThat(row.path("days").get(0).path("date").asText()).isEqualTo("2026-12-07");
		assertThat(row.path("days").get(6).path("date").asText()).isEqualTo("2026-12-13");
		assertThat(board.path("recentPosts")).isEmpty();
		// 잔디 캐시가 없으면 인증일을 알 수 없고 배너 대상이다
		assertThat(row.path("verifiedDays").isNull()).isTrue();
		assertThat(board.path("grassNotLoaded").asBoolean()).isTrue();
		assertThat(board.path("grassFetchedAt").isNull()).isTrue();

		// 잔디 캐시가 채워지면 0일로 읽히고 기준 시각이 보인다
		seedGrass(solo, WED, Set.of());
		JsonNode loaded = board(solo, soloTeam.id());
		assertThat(rowOf(loaded, solo).path("verifiedDays").asInt()).isZero();
		assertThat(loaded.path("grassNotLoaded").asBoolean()).isFalse();
		assertThat(loaded.path("grassFetchedAt").asText()).isEqualTo("2026-12-09T06:00:00");
	}

	@Test
	void 팀원이_여럿이면_팀장이_맨_앞이고_나머지는_참가한_순서이며_보는_사람만_내_행이다() {
		for (long viewer : new long[] {leader, second, third}) {
			JsonNode board = board(viewer, team.id());

			assertThat(memberIdsOf(board)).containsExactly(leader, second, third);
			for (JsonNode row : board.path("members")) {
				assertThat(row.path("isMe").asBoolean()).isEqualTo(row.path("memberId").asLong() == viewer);
			}
		}
	}

	// ----- 공개·비공개 -----

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void 공개든_비공개든_소속_팀원에게는_같은_현황판이고_공개_설정을_바꿔도_내용이_그대로다(boolean isPublic) {
		long writer = member();
		TeamRef target = createTeam(writer, isPublic);
		join(second, target);
		settleFirstWeek(writer);
		postAt("2026-12-09T12:10:01", second, PostType.DEVLOG, "공개설정-" + members.tag(), true, target.id());
		seedGrass(second, WED, Set.of(MON, TUE));

		JsonNode before = board(second, target.id());
		JsonNode summaryBefore = summaryOf(myTeams(second), target.id());
		assertThat(memberIdsOf(before)).containsExactly(writer, second);
		assertThat(titlesOf(before)).containsExactly("공개설정-" + members.tag());

		teamService.update(writer, target.id(), new UpdateTeamInput(null, null, !isPublic));

		assertThat(board(second, target.id())).isEqualTo(before);
		assertThat(summaryOf(myTeams(second), target.id())).isEqualTo(summaryBefore);
	}

	// ----- 팀장 위임 -----

	@Test
	void 팀장을_위임하면_새_팀장이_맨_앞으로_오고_이전_팀장은_참가_순서_자리의_일반_팀원이_된다() {
		warning(leader, PREV_WEEK, team.id());

		leaveService.transferLeadership(leader, team.id(), third);

		JsonNode newLeaderView = board(third, team.id());
		assertThat(memberIdsOf(newLeaderView)).containsExactly(third, leader, second);
		assertThat(rowOf(newLeaderView, third).path("isMe").asBoolean()).isTrue();
		assertThat(rowOf(newLeaderView, leader).path("isMe").asBoolean()).isFalse();
		JsonNode oldLeaderView = board(leader, team.id());
		assertThat(memberIdsOf(oldLeaderView)).containsExactly(third, leader, second);
		assertThat(rowOf(oldLeaderView, leader).path("isMe").asBoolean()).isTrue();
		// 위임은 이 팀 경고 수나 상태를 바꾸지 않는다
		assertThat(rowOf(newLeaderView, leader).path("warningCount").asInt()).isEqualTo(1);
		assertThat(rowOf(newLeaderView, leader).path("status").asText()).isEqualTo("NOT_YET");
	}

	@Test
	void 위임한_뒤_이전_팀장이_나가면_현황판에서_빠지고_접근도_막힌다() {
		leaveService.transferLeadership(leader, team.id(), third);
		leaveService.leave(leader, team.id());

		assertThat(memberIdsOf(board(third, team.id()))).containsExactly(third, second);
		assertDenied(boardResponse(leader, team.id()), ErrorCode.FORBIDDEN);
		assertThat(myTeams(leader)).isEmpty();
	}

	// ----- 나감·추방 -----

	@Test
	void 나갔다_다시_참가한_팀원은_맨_뒤에_한_번만_나오고_쓴_글은_그대로다() {
		postAt("2026-12-09T12:07:50", second, PostType.DEVLOG, "second 글", true, team.id());

		at("2026-12-09T12:08:00");
		leaveService.leave(second, team.id());
		JsonNode whileAway = board(leader, team.id());
		assertThat(memberIdsOf(whileAway)).containsExactly(leader, third);
		// 나간 작성자의 글도 이 팀에 연결된 채 목록에 남는다
		assertThat(titlesOf(whileAway)).containsExactly("second 글");

		at("2026-12-09T12:08:10");
		join(second, team);
		JsonNode rejoined = board(leader, team.id());
		assertThat(memberIdsOf(rejoined)).containsExactly(leader, third, second);
		assertThat(titlesOf(rejoined)).containsExactly("second 글");
	}

	@Test
	void 추방된_팀원은_빠지고_남은_팀원의_수치는_그대로다() {
		postAt("2026-12-09T12:07:51", second, PostType.DEVLOG, "second 글", true, team.id());
		seedGrass(second, WED, Set.of(MON, TUE));
		JsonNode before = rowOf(board(leader, team.id()), second);

		leaveService.kick(leader, team.id(), third);

		JsonNode after = board(leader, team.id());
		assertThat(memberIdsOf(after)).containsExactly(leader, second);
		assertThat(rowOf(after, second)).isEqualTo(before);
		assertThat(rowOf(after, third)).isNull();
	}

}
