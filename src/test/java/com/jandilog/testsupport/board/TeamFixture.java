package com.jandilog.testsupport.board;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.jdbc.core.JdbcTemplate;

import com.jandilog.team.dto.CreateTeamInput;
import com.jandilog.team.service.TeamJoinService;
import com.jandilog.team.service.TeamLeaveService;
import com.jandilog.team.service.TeamService;

// 글 팀 연결 시험용 팀 준비. 만들기·참가·탈퇴·삭제는 모두 team 서비스를 거치고, 끝에는 내가 만든 팀만 지운다
public class TeamFixture {

	private final TeamService teamService;
	private final TeamJoinService joinService;
	private final TeamLeaveService leaveService;
	private final JdbcTemplate jdbc;
	private final List<Long> teamIds = new ArrayList<>();
	private final AtomicInteger sequence = new AtomicInteger();

	public TeamFixture(TeamService teamService, TeamJoinService joinService, TeamLeaveService leaveService,
			JdbcTemplate jdbc) {
		this.teamService = teamService;
		this.joinService = joinService;
		this.leaveService = leaveService;
		this.jdbc = jdbc;
	}

	// 공개 팀. 만든 사람이 팀장이자 첫 팀원이다
	public long create(long leaderId) {
		return create(leaderId, true);
	}

	public long create(long leaderId, boolean isPublic) {
		String name = (isPublic ? "공개팀" : "비공개팀") + "-" + sequence.incrementAndGet() + "-"
				+ Long.toString(leaderId, 36);
		long id = teamService.create(leaderId, new CreateTeamInput(name, null, isPublic)).team().id();
		teamIds.add(id);
		return id;
	}

	// 초대코드로 참가한다
	public void join(long teamId, long memberId) {
		joinService.joinByCode(memberId, inviteCode(teamId));
	}

	// 일반 팀원의 자진 탈퇴
	public void leave(long teamId, long memberId) {
		leaveService.leave(memberId, teamId);
	}

	// 팀장이 팀을 삭제한다 (Mongo·post_index의 팀 연결도 함께 끊긴다)
	public void delete(long teamId, long leaderId) {
		leaveService.delete(leaderId, teamId);
	}

	public String inviteCode(long teamId) {
		return jdbc.queryForObject("select invite_code from team where id = ?", String.class, teamId);
	}

	public String name(long teamId) {
		return jdbc.queryForObject("select name from team where id = ?", String.class, teamId);
	}

	// 내가 만든 팀의 소속·초대·팀 행. 회원 삭제보다 먼저 불러야 FK에 걸리지 않는다
	public void cleanup() {
		for (Long id : teamIds) {
			jdbc.update("delete from team_invitation where team_id = ?", id);
			jdbc.update("delete from team_member where team_id = ?", id);
			jdbc.update("delete from team where id = ?", id);
		}
		teamIds.clear();
	}

}
