package com.jandilog.common.config;

import java.util.List;
import java.util.Locale;

// JWT_SECRET 기동 검증. 약한 값을 막는 것이 목적이라 오류 문구에 키 값은 싣지 않는다
final class JwtSecretPolicy {

	// 32바이트 무작위 값을 base64로 쓴 최소 길이(openssl rand -base64 48은 64자)
	static final int MIN_LENGTH = 43;
	static final int MIN_DISTINCT_CHARS = 16;
	private static final String HINT = "openssl rand -base64 48 로 만들어 주세요";

	// 예시 문서·튜토리얼에 흔한 값. 영숫자만 남긴 소문자 기준 부분 일치로 거른다
	private static final List<String> KNOWN_WEAK_FRAGMENTS = List.of(
			"your256bitsecret", "yoursecret", "yourjwtsecret", "yourkey",
			"changeme", "changeit", "changethis", "replaceme", "replacethis",
			"password", "passw0rd", "supersecret", "mysecret", "thisisasecret", "thisismysecret");

	private JwtSecretPolicy() {
	}

	static void validate(String secret) {
		if (secret == null || secret.length() < MIN_LENGTH) {
			throw new IllegalArgumentException("JWT_SECRET은 " + MIN_LENGTH + "자 이상이어야 해요. " + HINT);
		}
		if (secret.chars().distinct().count() < MIN_DISTINCT_CHARS) {
			throw new IllegalArgumentException("JWT_SECRET에 서로 다른 문자가 " + MIN_DISTINCT_CHARS
					+ "종 이상 있어야 해요. " + HINT);
		}
		if (isRepeating(secret)) {
			throw new IllegalArgumentException("JWT_SECRET이 같은 문자열의 반복이에요. " + HINT);
		}
		if (containsKnownWeakValue(secret)) {
			throw new IllegalArgumentException("JWT_SECRET이 알려진 예시·약한 값이에요. " + HINT);
		}
	}

	// 길이의 절반 이하 주기로 끝까지 되풀이되면 반복 값이다 (예: abcabcabc...)
	private static boolean isRepeating(String secret) {
		int length = secret.length();
		for (int period = 1; period <= length / 2; period++) {
			boolean repeats = true;
			for (int i = period; i < length && repeats; i++) {
				repeats = secret.charAt(i) == secret.charAt(i - period);
			}
			if (repeats) {
				return true;
			}
		}
		return false;
	}

	private static boolean containsKnownWeakValue(String secret) {
		String normalized = secret.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
		return KNOWN_WEAK_FRAGMENTS.stream().anyMatch(normalized::contains);
	}

}
