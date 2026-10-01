package com.jandilog.member.service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

// 로그인 후 토큰 교환용 일회용 코드: Redis authcode:{code}, TTL 60초, 값 memberId (DB명세서 2장)
@Service
public class AuthCodeService {

	private static final String KEY_PREFIX = "authcode:";
	private static final Duration TTL = Duration.ofSeconds(60);
	private static final int CODE_BYTES = 32;
	// 32바이트를 패딩 없이 base64url로 인코딩하면 43자
	private static final Pattern CODE_FORMAT = Pattern.compile("^[A-Za-z0-9_-]{43}$");

	private final StringRedisTemplate redis;
	private final SecureRandom random = new SecureRandom();

	public AuthCodeService(StringRedisTemplate redis) {
		this.redis = redis;
	}

	public String issue(long memberId) {
		byte[] bytes = new byte[CODE_BYTES];
		random.nextBytes(bytes);
		String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		redis.opsForValue().set(KEY_PREFIX + code, Long.toString(memberId), TTL);
		return code;
	}

	// 조회와 삭제를 한 번에 해서 같은 코드의 두 번째 교환은 항상 빈 값 (E-05, E-06)
	public Optional<Long> consume(String code) {
		if (code == null || !CODE_FORMAT.matcher(code).matches()) {
			return Optional.empty();
		}
		String value = redis.opsForValue().getAndDelete(KEY_PREFIX + code);
		if (value == null) {
			return Optional.empty();
		}
		try {
			return Optional.of(Long.parseLong(value));
		} catch (NumberFormatException e) {
			return Optional.empty();
		}
	}

}
