package com.jandilog.member.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jandilog.common.config.AdminProperties;
import com.jandilog.member.domain.Member;
import com.jandilog.member.domain.MemberRole;
import com.jandilog.member.domain.MemberStatus;
import com.jandilog.member.repository.MemberRepository;

@Service
public class MemberService {

	private static final Logger log = LoggerFactory.getLogger(MemberService.class);
	private static final int NICKNAME_MAX_LENGTH = 30;

	private final MemberRepository memberRepository;
	private final Clock clock;
	// ADMIN_GITHUB_IDS에 적힌 GitHub 숫자 id. 비어 있으면 아무도 승격되지 않는다
	private final Set<Long> adminGithubIds;

	public MemberService(MemberRepository memberRepository, Clock clock, AdminProperties adminProperties) {
		this.memberRepository = memberRepository;
		this.clock = clock;
		this.adminGithubIds = adminProperties.githubIdSet();
	}

	// GitHub 숫자 id로 회원을 찾아 login·nickname을 갱신하고, 없으면 승인 대기로 만든다.
	// ADMIN_GITHUB_IDS에 있는 id는 새 회원이면 처음부터 활성 관리자, 기존 회원이면 상태와 상관없이 활성 관리자로 올린다.
	// 목록에서 빠져도 기존 관리자를 강등하지 않는다. admin_action_log에는 남기지 않고 로그에만 승격 사실을 남긴다
	@Transactional
	public Member loginWithGithub(long githubId, String githubLogin, String githubName) {
		String nickname = toNickname(githubName, githubLogin);
		boolean listedAdmin = adminGithubIds.contains(githubId);
		Member member = memberRepository.findByGithubId(githubId).orElse(null);
		if (member == null) {
			LocalDateTime now = LocalDateTime.now(clock);
			Member created = memberRepository.saveAndFlush(listedAdmin
					? Member.createAdmin(githubId, githubLogin, nickname, now)
					: Member.createPending(githubId, githubLogin, nickname, now));
			if (listedAdmin) {
				log.info("관리자 목록의 GitHub 계정이라 처음부터 관리자로 만들었어요 (memberId={})", created.getId());
			}
			return created;
		}
		member.syncGithubProfile(githubLogin, nickname);
		if (listedAdmin && !(member.getStatus() == MemberStatus.ACTIVE && member.getRole() == MemberRole.ADMIN)) {
			return promoteToAdmin(member);
		}
		return member;
	}

	// 조건부 UPDATE라 동시에 로그인해도 한 번만 바뀐다. 이미 승인된 회원은 원래 승인 시각을 지킨다
	private Member promoteToAdmin(Member member) {
		long id = member.getId();
		MemberStatus beforeStatus = member.getStatus();
		MemberRole beforeRole = member.getRole();
		LocalDateTime approvedAt = beforeStatus == MemberStatus.ACTIVE && member.getApprovedAt() != null
				? member.getApprovedAt() : LocalDateTime.now(clock);
		int changed = memberRepository.promoteToAdmin(id, MemberStatus.ACTIVE, MemberRole.ADMIN, approvedAt);
		if (changed > 0) {
			log.info("관리자 목록의 GitHub 계정을 관리자로 승격했어요 (memberId={}, 이전 상태={}, 이전 역할={})", id, beforeStatus,
					beforeRole);
		}
		// 다른 로그인이 먼저 올린 경우도 최신 값을 읽도록 잠금 읽기로 다시 읽는다
		return memberRepository.findByIdForShare(id).orElseThrow();
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
