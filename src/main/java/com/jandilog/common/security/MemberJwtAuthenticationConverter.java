package com.jandilog.common.security;

import java.util.List;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;

import com.jandilog.member.domain.Member;
import com.jandilog.member.domain.MemberRole;
import com.jandilog.member.repository.MemberRepository;

// 검증된 JWT의 sub(memberId)로 요청마다 회원을 읽어 권한을 정한다.
// 상태·역할을 토큰에 넣지 않아서 승인·거절·권한 변경이 다음 요청부터 바로 반영된다 (E-01, E-02)
@Component
public class MemberJwtAuthenticationConverter implements Converter<Jwt, MemberAuthenticationToken> {

	private static final List<GrantedAuthority> MEMBER = List.of(new SimpleGrantedAuthority(Authorities.MEMBER));
	private static final List<GrantedAuthority> ADMIN = List.of(
			new SimpleGrantedAuthority(Authorities.MEMBER), new SimpleGrantedAuthority(Authorities.ADMIN));
	private static final List<GrantedAuthority> PENDING = List.of(new SimpleGrantedAuthority(Authorities.PENDING));
	private static final List<GrantedAuthority> REJECTED = List.of(new SimpleGrantedAuthority(Authorities.REJECTED));

	private final MemberRepository memberRepository;

	public MemberJwtAuthenticationConverter(MemberRepository memberRepository) {
		this.memberRepository = memberRepository;
	}

	@Override
	public MemberAuthenticationToken convert(Jwt jwt) {
		long memberId = parseMemberId(jwt.getSubject());
		Member member = memberRepository.findById(memberId)
				.orElseThrow(() -> new InvalidBearerTokenException("존재하지 않는 회원"));
		AuthenticatedMember principal = new AuthenticatedMember(memberId, member.getStatus(), member.getRole());
		return new MemberAuthenticationToken(principal, jwt, authoritiesOf(member));
	}

	// ACTIVE만 MEMBER를 받는다. 승인 대기·거절은 me만 가능 (PENDING/REJECTED 전용 권한)
	private List<GrantedAuthority> authoritiesOf(Member member) {
		return switch (member.getStatus()) {
			case ACTIVE -> member.getRole() == MemberRole.ADMIN ? ADMIN : MEMBER;
			case PENDING -> PENDING;
			case REJECTED -> REJECTED;
		};
	}

	private long parseMemberId(String subject) {
		try {
			return Long.parseLong(subject);
		} catch (NumberFormatException e) {
			throw new InvalidBearerTokenException("sub 형식 오류");
		}
	}

}
