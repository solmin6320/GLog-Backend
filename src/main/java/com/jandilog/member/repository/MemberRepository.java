package com.jandilog.member.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.jandilog.member.domain.Member;
import com.jandilog.member.domain.MemberStatus;

public interface MemberRepository extends JpaRepository<Member, Long> {

	Optional<Member> findByGithubId(long githubId);

	// 가입 승인 목록: id 오름차순 키셋 페이징. pattern은 소문자 LIKE 패턴, 이스케이프 문자는 '!'
	@Query("""
			select m from Member m
			where m.status in :statuses
			  and m.id > :afterId
			  and (lower(m.nickname) like :pattern escape '!' or lower(m.githubLogin) like :pattern escape '!')
			order by m.id asc
			""")
	List<Member> findApprovalPage(@Param("statuses") Collection<MemberStatus> statuses,
			@Param("pattern") String pattern, @Param("afterId") long afterId, Pageable pageable);

	@Query("""
			select count(m) from Member m
			where m.status in :statuses
			  and (lower(m.nickname) like :pattern escape '!' or lower(m.githubLogin) like :pattern escape '!')
			""")
	long countApproval(@Param("statuses") Collection<MemberStatus> statuses, @Param("pattern") String pattern);

	// 현재 상태가 from일 때만 바꾼다. 관리자 둘이 동시에 처리해도 한 번만 적용된다
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update Member m set m.status = :to where m.id = :id and m.status = :from")
	int changeStatus(@Param("id") long id, @Param("from") MemberStatus from, @Param("to") MemberStatus to);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update Member m set m.status = :to, m.approvedAt = :approvedAt where m.id = :id and m.status = :from")
	int approve(@Param("id") long id, @Param("from") MemberStatus from, @Param("to") MemberStatus to,
			@Param("approvedAt") LocalDateTime approvedAt);

}
