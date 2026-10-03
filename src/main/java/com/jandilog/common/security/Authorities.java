package com.jandilog.common.security;

// 권한 문자열. ACTIVE 회원만 MEMBER(관리자는 ADMIN 추가)를 받고, 승인 대기·거절 계정은 me만 열어 둔다
public final class Authorities {

	public static final String MEMBER = "ROLE_MEMBER";
	public static final String ADMIN = "ROLE_ADMIN";
	public static final String PENDING = "ROLE_PENDING";
	public static final String REJECTED = "ROLE_REJECTED";

	private Authorities() {
	}

}
