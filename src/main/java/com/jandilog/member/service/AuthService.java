package com.jandilog.member.service;

import org.springframework.stereotype.Service;

import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.common.security.JwtTokenService;
import com.jandilog.common.security.JwtTokenService.IssuedToken;
import com.jandilog.member.domain.Member;
import com.jandilog.member.domain.MemberStatus;
import com.jandilog.member.dto.AuthPayload;
import com.jandilog.member.dto.MeResponse;
import com.jandilog.member.repository.MemberRepository;

@Service
public class AuthService {

	private final AuthCodeService authCodeService;
	private final MemberRepository memberRepository;
	private final JwtTokenService jwtTokenService;

	public AuthService(AuthCodeService authCodeService, MemberRepository memberRepository,
			JwtTokenService jwtTokenService) {
		this.authCodeService = authCodeService;
		this.memberRepository = memberRepository;
		this.jwtTokenService = jwtTokenService;
	}

	// 일회용 코드를 JWT로 바꾼다. 코드는 읽는 즉시 지워진다 (E-05, E-06)
	public AuthPayload exchange(String code) {
		long memberId = authCodeService.consume(code)
				.orElseThrow(() -> new ApiException(ErrorCode.AUTH_CODE_INVALID));
		Member member = memberRepository.findById(memberId)
				.orElseThrow(() -> new ApiException(ErrorCode.AUTH_CODE_INVALID));
		// 코드 발급 뒤 거절된 경우까지 막는다. 토큰 미발급 (E-02)
		if (member.getStatus() == MemberStatus.REJECTED) {
			throw new ApiException(ErrorCode.ACCOUNT_REJECTED);
		}
		IssuedToken token = jwtTokenService.issue(member.getId());
		return new AuthPayload(token.value(), (int) token.expiresInSeconds(), MeResponse.from(member));
	}

	public MeResponse me(long memberId) {
		return memberRepository.findById(memberId)
				.map(MeResponse::from)
				.orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
	}

}
