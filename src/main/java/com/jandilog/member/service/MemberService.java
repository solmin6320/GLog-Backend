package com.jandilog.member.service;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jandilog.member.domain.Member;
import com.jandilog.member.repository.MemberRepository;

@Service
public class MemberService {

	private static final int NICKNAME_MAX_LENGTH = 30;

	private final MemberRepository memberRepository;
	private final Clock clock;

	public MemberService(MemberRepository memberRepository, Clock clock) {
		this.memberRepository = memberRepository;
		this.clock = clock;
	}

	// GitHub 숫자 id로 회원을 찾아 login·nickname을 갱신하고, 없으면 승인 대기로 만든다
	@Transactional
	public Member loginWithGithub(long githubId, String githubLogin, String githubName) {
		String nickname = toNickname(githubName, githubLogin);
		return memberRepository.findByGithubId(githubId)
				.map(member -> {
					member.syncGithubProfile(githubLogin, nickname);
					return member;
				})
				.orElseGet(() -> memberRepository.saveAndFlush(
						Member.createPending(githubId, githubLogin, nickname, LocalDateTime.now(clock))));
	}

	// GitHub 표시 이름이 비었으면 아이디를 쓰고, 컬럼 길이(30자)에 맞춘다
	static String toNickname(String githubName, String githubLogin) {
		String base = (githubName == null || githubName.isBlank()) ? githubLogin : githubName.strip();
		if (base.codePointCount(0, base.length()) > NICKNAME_MAX_LENGTH) {
			base = base.substring(0, base.offsetByCodePoints(0, NICKNAME_MAX_LENGTH));
		}
		return base;
	}

}
