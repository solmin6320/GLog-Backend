package com.jandilog.common.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import com.jandilog.common.config.AuthProperties;

// 액세스 토큰만 발급한다. Refresh Token은 기획서에 없다 (만료 시 재로그인, E-03)
@Component
public class JwtTokenService {

	private final JwtEncoder encoder;
	private final AuthProperties.Jwt properties;
	private final Clock clock;

	public JwtTokenService(JwtEncoder encoder, AuthProperties properties, Clock clock) {
		this.encoder = encoder;
		this.properties = properties.jwt();
		this.clock = clock;
	}

	public IssuedToken issue(long memberId) {
		Instant now = clock.instant();
		Duration lifetime = Duration.ofMinutes(properties.expiresMinutes());
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer(properties.issuer())
				.subject(Long.toString(memberId))
				.issuedAt(now)
				.expiresAt(now.plus(lifetime))
				.build();
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
		String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
		return new IssuedToken(value, lifetime.toSeconds());
	}

	public record IssuedToken(String value, long expiresInSeconds) {

		// 토큰 원문이 로그에 찍히지 않게 가린다
		@Override
		public String toString() {
			return "IssuedToken[expiresInSeconds=" + expiresInSeconds + "]";
		}

	}

}
