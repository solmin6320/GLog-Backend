package com.jandilog.common.security;

// 로그인을 시작한 쪽. 웹은 웹 주소로, 앱은 딥링크로 돌려보낸다 (기능명세서 1장)
public enum LoginClient {
	WEB,
	APP;

	// 시작 파라미터 client=web|app. 없거나 모르는 값은 웹
	public static LoginClient from(String value) {
		return "app".equalsIgnoreCase(value) ? APP : WEB;
	}
}
