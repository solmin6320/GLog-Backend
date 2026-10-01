package com.jandilog.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

// client=web|app 파라미터 해석: 앱만 app이고 나머지는 전부 웹 (기능명세서 1장)
class LoginClientTest {

	@ParameterizedTest
	@ValueSource(strings = {"app", "APP", "App"})
	void app은_대소문자_상관없이_앱이다(String value) {
		assertThat(LoginClient.from(value)).isEqualTo(LoginClient.APP);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"web", "WEB", "ios", " app", "apps"})
	void 그_밖의_값은_웹이다(String value) {
		assertThat(LoginClient.from(value)).isEqualTo(LoginClient.WEB);
	}

}
