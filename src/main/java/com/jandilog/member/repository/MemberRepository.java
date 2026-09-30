package com.jandilog.member.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.jandilog.member.domain.Member;

public interface MemberRepository extends JpaRepository<Member, Long> {

	Optional<Member> findByGithubId(long githubId);

}
