package com.jandilog.team.query;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

// 팀 테이블을 읽기 전용으로 보는 조회 창구. 팀 도메인(생성·참가·추방 등)은 team 모듈 몫이라
// 면제 요청의 팀장 확인, 관리자 벌칙 화면의 팀 이름, 프로필의 공개 팀·팀별 경고에 필요한 조회만 둔다 (V1 team · team_member)
@Component
public class TeamReadModel {

	// deleted: 팀이 삭제됨(행은 남는다), publicTeam: 공개 설정
	public record TeamName(long id, String name, boolean publicTeam, boolean deleted) {
	}

	private final JdbcClient jdbc;

	public TeamReadModel(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	// 삭제된 팀도 포함해 id별 이름을 읽는다
	public Map<Long, TeamName> findByIds(Collection<Long> teamIds) {
		Map<Long, TeamName> result = new HashMap<>();
		if (teamIds.isEmpty()) {
			return result;
		}
		jdbc.sql("select id, name, is_public, deleted_at is not null as deleted from team where id in (:ids)")
				.param("ids", teamIds)
				.query((rs, rowNum) -> new TeamName(rs.getLong("id"), rs.getString("name"), rs.getBoolean("is_public"),
						rs.getBoolean("deleted")))
				.list()
				.forEach(team -> result.put(team.id(), team));
		return result;
	}

	// 삭제되지 않은 팀의 팀장 id
	public Optional<Long> findLeaderIdOfAliveTeam(long teamId) {
		return jdbc.sql("select leader_id from team where id = :teamId and deleted_at is null")
				.param("teamId", teamId)
				.query(Long.class)
				.optional();
	}

	// 지금 그 팀 소속인가 (나간 시각이 없는 행)
	public boolean isCurrentMember(long teamId, long memberId) {
		Integer count = jdbc.sql("select count(*) from team_member where team_id = :teamId and member_id = :memberId and left_at is null")
				.param("teamId", teamId)
				.param("memberId", memberId)
				.query(Integer.class)
				.single();
		return count > 0;
	}

	// 지금 소속인 삭제되지 않은 팀들, 참가한 순서
	public List<TeamName> findCurrentTeams(long memberId) {
		return jdbc.sql("""
				select t.id, t.name, t.is_public, false as deleted
				from team_member tm join team t on t.id = tm.team_id
				where tm.member_id = :memberId and tm.left_at is null and t.deleted_at is null
				order by tm.joined_at, t.id
				""")
				.param("memberId", memberId)
				.query((rs, rowNum) -> new TeamName(rs.getLong("id"), rs.getString("name"), rs.getBoolean("is_public"),
						rs.getBoolean("deleted")))
				.list();
	}

}
