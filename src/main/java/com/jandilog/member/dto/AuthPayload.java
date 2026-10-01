package com.jandilog.member.dto;

// 일회용 코드 교환 결과. expiresIn은 초 단위
public record AuthPayload(String accessToken, int expiresIn, MeResponse me) {

	// 토큰 원문이 로그에 찍히지 않게 가린다
	@Override
	public String toString() {
		return "AuthPayload[expiresIn=" + expiresIn + ", me=" + me + "]";
	}

}
