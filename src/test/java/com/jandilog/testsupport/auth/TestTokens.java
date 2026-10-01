package com.jandilog.testsupport.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

// 검증 규칙을 시험하려고 일부러 틀리게 만든 JWT를 손으로 조립한다 (알고리즘·키·클레임을 마음대로 고른다)
public final class TestTokens {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

	private TestTokens() {
	}

	// null인 값은 클레임에서 뺀다. 시각은 epoch 초
	public static Map<String, Object> claims(String sub, String iss, Instant iat, Instant exp) {
		Map<String, Object> claims = new LinkedHashMap<>();
		if (iss != null) {
			claims.put("iss", iss);
		}
		if (sub != null) {
			claims.put("sub", sub);
		}
		if (iat != null) {
			claims.put("iat", iat.getEpochSecond());
		}
		if (exp != null) {
			claims.put("exp", exp.getEpochSecond());
		}
		return claims;
	}

	// HS256·HS384·HS512. 키 길이 제한 없이 서명한다
	public static String hmac(String jwsAlgorithm, byte[] key, Map<String, Object> claims) {
		String signingInput = encode(Map.of("alg", jwsAlgorithm, "typ", "JWT")) + "." + encode(claims);
		String macName = switch (jwsAlgorithm) {
			case "HS256" -> "HmacSHA256";
			case "HS384" -> "HmacSHA384";
			case "HS512" -> "HmacSHA512";
			default -> throw new IllegalArgumentException("지원하지 않는 알고리즘: " + jwsAlgorithm);
		};
		try {
			Mac mac = Mac.getInstance(macName);
			mac.init(new SecretKeySpec(key, macName));
			byte[] signature = mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII));
			return signingInput + "." + B64.encodeToString(signature);
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	public static String hs256(byte[] key, Map<String, Object> claims) {
		return hmac("HS256", key, claims);
	}

	// alg=none: 서명이 빈 토큰
	public static String none(Map<String, Object> claims) {
		return encode(Map.of("alg", "none", "typ", "JWT")) + "." + encode(claims) + ".";
	}

	public static String rs256(PrivateKey key, Map<String, Object> claims) {
		String signingInput = encode(Map.of("alg", "RS256", "typ", "JWT")) + "." + encode(claims);
		try {
			Signature signature = Signature.getInstance("SHA256withRSA");
			signature.initSign(key);
			signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
			return signingInput + "." + B64.encodeToString(signature.sign());
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	// 서명과 헤더는 두고 본문만 바꾼다
	public static String withPayload(String token, Map<String, Object> claims) {
		String[] parts = token.split("\\.", -1);
		return parts[0] + "." + encode(claims) + "." + parts[2];
	}

	private static String encode(Map<String, Object> json) {
		try {
			return B64.encodeToString(MAPPER.writeValueAsBytes(json));
		} catch (JsonProcessingException e) {
			throw new IllegalStateException(e);
		}
	}

}
