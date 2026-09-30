package com.jandilog.common.security;

import java.util.Collection;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

public class MemberAuthenticationToken extends AbstractAuthenticationToken {

	private final AuthenticatedMember principal;
	private final Jwt jwt;

	public MemberAuthenticationToken(AuthenticatedMember principal, Jwt jwt,
			Collection<? extends GrantedAuthority> authorities) {
		super(authorities);
		this.principal = principal;
		this.jwt = jwt;
		setAuthenticated(true);
	}

	@Override
	public AuthenticatedMember getPrincipal() {
		return principal;
	}

	@Override
	public Jwt getCredentials() {
		return jwt;
	}

	@Override
	public String getName() {
		return Long.toString(principal.id());
	}

}
