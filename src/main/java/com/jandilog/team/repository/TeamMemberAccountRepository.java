package com.jandilog.team.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.jandilog.member.domain.Member;
import com.jandilog.member.domain.MemberStatus;

// 팀 기능이 member 테이블에서 쓰는 조회·갱신. MemberRepository는 건드리지 않으려고 따로 둔다
public interface TeamMemberAccountRepository extends Repository<Member, Long> {

	// GitHub 아이디는 대소문자를 구분하지 않는다. 같은 문자열이 둘이면(예전 아이디가 남은 경우) 최근 가입자부터
	@Query("select m from Member m where lower(m.githubLogin) = lower(:login) and m.status = :status order by m.id desc")
	List<Member> findByLoginAndStatus(@Param("login") String login, @Param("status") MemberStatus status);

	// 처음 팀에 참가한 시각은 계정 생애 최초 1회만 기록한다. 이미 있으면 바꾸지 않는다 (DB명세서 1-1)
	@Modifying(flushAutomatically = true)
	@Query("update Member m set m.firstTeamJoinedAt = :now where m.id = :id and m.firstTeamJoinedAt is null")
	int markFirstTeamJoined(@Param("id") long id, @Param("now") LocalDateTime now);

}
