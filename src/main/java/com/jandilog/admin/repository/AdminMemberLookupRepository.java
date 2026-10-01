package com.jandilog.admin.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.jandilog.member.domain.Member;

// 관리자 검색이 작성자를 닉네임 · GitHub 아이디로 찾는 조회. MemberRepository는 건드리지 않으려고 따로 둔다
public interface AdminMemberLookupRepository extends Repository<Member, Long> {

	// pattern은 소문자 LIKE 패턴, 이스케이프 문자는 '!'
	@Query("""
			select m.id from Member m
			where lower(m.nickname) like :pattern escape '!' or lower(m.githubLogin) like :pattern escape '!'
			order by m.id
			""")
	List<Long> findIdsByKeyword(@Param("pattern") String pattern, Pageable pageable);

}
