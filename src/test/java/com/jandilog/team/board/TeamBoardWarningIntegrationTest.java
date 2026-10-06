package com.jandilog.team.board;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.jandilog.common.exception.ErrorCode;

// 팀 현황판 경고 열(기능명세서 2장·6장, 화면설계서 TM-05 ⑥): 그 팀 카테고리가 붙은 살아 있고 차감되지 않은 경고만 센다.
// 전체 누적·벌칙 대상 여부는 보이지 않는다. 경고 규칙 자체는 경고 재계산 테스트가 맡고, 여기서는 현황판에 어떻게 나오는지만 본다.
// 판정 주: W1=2026-11-16, W2=11-23, W3=11-30 (모두 이번 주 12-07 이전)
class TeamBoardWarningIntegrationTest extends TeamBoardTestBase {

	private static final LocalDate W1 = LocalDate.of(2026, 11, 16);
	private static final LocalDate W2 = W1.plusDays(7);
	private static final LocalDate W3 = W1.plusDays(14);

	private long leader;
	private long otherLeader;
	private long alpha;
	private long beta;
	private long gamma;
	private TeamRef team;
	private TeamRef otherTeam;

	@BeforeEach
	void setUpScenario() {
		at("2026-12-09T12:07:13");
		leader = member();
		otherLeader = member();
		alpha = member();
		beta = member();
		gamma = member();
		team = createTeam(leader, true);
		otherTeam = createTeam(otherLeader, false);
		for (long id : new long[] {alpha, beta, gamma}) {
			join(id, team);
			join(id, otherTeam);
		}
		settleFirstWeek(leader, otherLeader, alpha, beta, gamma);
	}

	private int warningCount(long viewer, long teamId, long memberId) {
		return rowOf(board(viewer, teamId), memberId).path("warningCount").asInt(-1);
	}

	@Test
	void 두_주_연속_통과로_차감된_경고는_세지_않고_한_주만_통과했으면_그대로_센다() {
		warning(alpha, W1, team.id());
		judgmentRow(alpha, W2, "PASS");
		judgmentRow(alpha, W3, "PASS");
		warning(beta, W1, team.id());
		judgmentRow(beta, W2, "PASS");

		assertThat(warningCount(leader, team.id(), alpha)).isZero();
		assertThat(warningCount(leader, team.id(), beta)).isEqualTo(1);
		// 보는 사람이 본인이어도 같은 값이다
		assertThat(warningCount(alpha, team.id(), alpha)).isZero();
		assertThat(warningCount(beta, team.id(), beta)).isEqualTo(1);
	}

	@Test
	void 경고는_그_팀_카테고리가_붙은_것만_세고_다른_팀_경고는_벌칙_대상이어도_보이지_않는다() {
		// gamma: 세 주 연속 미달이 모두 다른 팀 카테고리뿐 (그 팀에서는 벌칙 대상)
		warning(gamma, W1, otherTeam.id());
		warning(gamma, W2, otherTeam.id());
		warning(gamma, W3, otherTeam.id());
		// alpha: 한 경고에 두 팀 카테고리가 같이 붙음
		warning(alpha, W1, team.id(), otherTeam.id());

		assertThat(warningCount(leader, team.id(), gamma)).isZero();
		assertThat(warningCount(otherLeader, otherTeam.id(), gamma)).isEqualTo(3);
		assertThat(warningCount(leader, team.id(), alpha)).isEqualTo(1);
		assertThat(warningCount(otherLeader, otherTeam.id(), alpha)).isEqualTo(1);
		// 다른 팀 경고 때문에 이 팀의 상태가 바뀌지도 않는다
		assertThat(rowOf(board(leader, team.id()), gamma).path("status").asText()).isEqualTo("NOT_YET");
	}

	@Test
	void 팀이_삭제되면_그_카테고리만_빠져서_다른_팀_카테고리가_남은_경고는_계속_센다() {
		warning(alpha, W1, team.id(), otherTeam.id());
		warning(beta, W2, otherTeam.id());

		leaveService.delete(otherLeader, otherTeam.id());

		assertThat(warningCount(leader, team.id(), alpha)).isEqualTo(1);
		assertThat(warningCount(leader, team.id(), beta)).isZero();
		assertDenied(boardResponse(otherLeader, otherTeam.id()), ErrorCode.NOT_FOUND);
	}

	@Test
	void 그_팀_카테고리가_빠진_경고와_삭제_표시된_경고는_세지_않는다() {
		warning(alpha, W1, team.id(), otherTeam.id());
		warning(beta, W1, team.id());
		long alphaWarning = jdbc.queryForObject("select id from warning where member_id = ?", Long.class, alpha);
		long betaWarning = jdbc.queryForObject("select id from warning where member_id = ?", Long.class, beta);
		assertThat(warningCount(leader, team.id(), alpha)).isEqualTo(1);
		assertThat(warningCount(leader, team.id(), beta)).isEqualTo(1);

		// alpha: 이 팀 카테고리만 빠지고 다른 팀 카테고리는 남음 / beta: 경고 자체가 삭제 표시됨
		jdbc.update("update warning_team set removed_at = ? where warning_id = ? and team_id = ?",
				LocalDateTime.of(2026, 12, 8, 10, 0), alphaWarning, team.id());
		jdbc.update("update warning set deleted_at = ?, delete_reason = 'KICKED' where id = ?",
				LocalDateTime.of(2026, 12, 8, 10, 0), betaWarning);

		assertThat(warningCount(leader, team.id(), alpha)).isZero();
		assertThat(warningCount(leader, team.id(), beta)).isZero();
		assertThat(warningCount(otherLeader, otherTeam.id(), alpha)).isEqualTo(1);
	}

	@Test
	void 벌칙_이행_이전_주의_경고는_세지_않고_이행_뒤_주의_경고만_센다() {
		warning(alpha, W1, team.id());
		warning(alpha, W3, team.id());
		assertThat(warningCount(leader, team.id(), alpha)).isEqualTo(2);

		// W1이 끝난 뒤(11-23)이고 W3가 끝나기 전(12-07)인 시각에 이행 처리
		jdbc.update("insert into penalty_fulfillment (member_id, fulfilled_at, admin_id, reached_week) values (?, ?, ?, ?)",
				alpha, LocalDateTime.of(2026, 11, 30, 9, 0), leader, W1);

		assertThat(warningCount(leader, team.id(), alpha)).isEqualTo(1);
	}

	@Test
	void 판정_보류가_남은_팀원의_경고_수는_알_수_없어_null이고_풀리면_숫자로_돌아온다() {
		warning(beta, W1, team.id());
		warning(gamma, W1, team.id());
		judgmentRow(gamma, W3, "HOLD");

		JsonNode held = rowOf(board(leader, team.id()), gamma);
		assertThat(held.path("warningCount").isNull()).isTrue();
		// 다른 팀원의 값에는 영향이 없다
		assertThat(warningCount(leader, team.id(), beta)).isEqualTo(1);

		jdbc.update("update weekly_judgment set status = 'PASS', hold_reason = null, judged_at = ?"
				+ " where member_id = ? and week_start = ?", W3.plusDays(7).atTime(7, 0), gamma, W3);

		assertThat(warningCount(leader, team.id(), gamma)).isEqualTo(1);
	}

	@Test
	void 추방됐다가_아이디_초대로_돌아오면_경고_수가_복원되고_자진_탈퇴_뒤_코드_재참가는_그대로다() {
		warning(alpha, W1, team.id());
		warning(beta, W1, team.id());

		leaveService.kick(leader, team.id(), alpha);
		assertThat(rowOf(board(leader, team.id()), alpha)).isNull();
		assertThat(warningCount(leader, team.id(), beta)).isEqualTo(1);

		long invitationId = invitationService.invite(leader, team.id(), loginOf(alpha)).id();
		invitationService.accept(alpha, invitationId);
		assertThat(warningCount(leader, team.id(), alpha)).isEqualTo(1);

		leaveService.leave(beta, team.id());
		join(beta, team);
		assertThat(warningCount(leader, team.id(), beta)).isEqualTo(1);
	}

}
