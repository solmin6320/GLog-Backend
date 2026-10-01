package com.jandilog.warning.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.jandilog.member.domain.Member;

import jakarta.persistence.LockModeType;

// 회원 행 SELECT ... FOR UPDATE. 같은 회원의 판정 저장·재계산·정정·복구를 한 번에 하나씩만 돌린다 (Q-08: MariaDB 비관적 락).
// 락은 트랜잭션이 끝날 때 풀리므로 반드시 변경과 같은 트랜잭션 안에서 건다. MemberRepository는 건드리지 않으려고 따로 둔다
public interface MemberLockRepository extends Repository<Member, Long> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select m from Member m where m.id = :id")
	Optional<Member> findLockedById(@Param("id") long id);

}
