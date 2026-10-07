package com.jandilog.common.security;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import com.jandilog.member.domain.Member;
import com.jandilog.member.domain.MemberStatus;
import com.jandilog.member.service.AuthCodeService;
import com.jandilog.member.service.MemberService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

// GitHub 인증 성공 후: 회원 upsert → 일회용 코드 발급 → 웹/앱으로 복귀
@Component
public class OAuthLoginSuccessHandler implements AuthenticationSuccessHandler {

	private static final Logger log = LoggerFactory.getLogger(OAuthLoginSuccessHandler.class);

	private final MemberService memberService;
	private final AuthCodeService authCodeService;
	private final LoginRedirector redirector;

	public OAuthLoginSuccessHandler(MemberService memberService, AuthCodeService authCodeService,
			LoginRedirector redirector) {
		this.memberService = memberService;
		this.authCodeService = authCodeService;
		this.redirector = redirector;
	}

	@Override
	public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
			Authentication authentication) throws IOException {
		// GitHub 인증 결과는 일회용 코드를 만드는 데만 쓰고 세션에 남기지 않는다
		discardSession(request);
		try {
			if (!(authentication.getPrincipal() instanceof OAuth2User user)
					|| !(user.getAttribute("id") instanceof Number githubId)
					|| !(user.getAttribute("login") instanceof String githubLogin)) {
				log.warn("GitHub 사용자 정보에 id 또는 login이 없어요");
				redirector.sendError(request, response, "failed");
				return;
			}
			String githubName = user.getAttribute("name") instanceof String name ? name : null;

			Member member = loginWithRetry(githubId.longValue(), githubLogin, githubName);
			if (member.getStatus() == MemberStatus.REJECTED) {
				// 거절된 계정은 코드를 만들지 않는다 (E-02)
				redirector.sendError(request, response, "rejected");
				return;
			}
			redirector.sendCode(request, response, authCodeService.issue(member.getId()));
		} catch (RuntimeException e) {
			log.error("로그인 후처리에 실패했어요", e);
			redirector.sendError(request, response, "failed");
		}
	}

	// 첫 로그인이 동시에 두 번 들어오면 github_id UNIQUE 충돌이나 INSERT 데드락이 나므로 한 번 더 조회한다
	private Member loginWithRetry(long githubId, String githubLogin, String githubName) {
		try {
			return memberService.loginWithGithub(githubId, githubLogin, githubName);
		} catch (DataIntegrityViolationException | PessimisticLockingFailureException e) {
			return memberService.loginWithGithub(githubId, githubLogin, githubName);
		}
	}

	private void discardSession(HttpServletRequest request) {
		SecurityContextHolder.clearContext();
		HttpSession session = request.getSession(false);
		if (session != null) {
			session.invalidate();
		}
	}

}
