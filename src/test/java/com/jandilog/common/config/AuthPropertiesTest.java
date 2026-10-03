package com.jandilog.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.jandilog.common.config.AuthProperties.Jwt;

// 기동 때 잘못된 로그인 설정을 막는 규칙 (JWT 키 43자·문자 다양성·약한 값, 만료 범위, 복귀 주소 형식)
class AuthPropertiesTest {

	// 서로 다른 문자 62종. 앞에서 n자를 잘라 길이별 키를 만든다
	private static final String DIVERSE = "Qv7mW2pL8nR4tY6uB1cD5eF0gHaZsXoViNqMlPwKjTyUrEdCbA3hGfJx9kOz-_";
	private static final String SECRET = DIVERSE;
	private static final String APP_LINK = "com.jandilog.app://auth";

	private static Jwt jwt() {
		return new Jwt(SECRET, "jandilog", 60);
	}

	private static String key(int length) {
		return DIVERSE.substring(0, length);
	}

	// 고정 시드로 뽑은 무작위 문자열. 같은 알파벳에서 반복 없이 길이만 늘린다
	private static String randomFrom(String alphabet, int length, long seed) {
		Random random = new Random(seed);
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < length; i++) {
			sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
		}
		return sb.toString();
	}

	@Test
	void 정상_설정이면_만들어지고_웹_주소_끝의_슬래시는_뗀다() {
		AuthProperties properties = new AuthProperties("https://jandi.example.com/", APP_LINK, jwt());

		assertThat(properties.webBaseUrl()).isEqualTo("https://jandi.example.com");
		assertThat(properties.appDeepLink()).isEqualTo(APP_LINK);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"localhost:5173", "ftp://example.com", "javascript:alert(1)", "//example.com"})
	void 웹_주소는_http나_https로_시작해야_한다(String webBaseUrl) {
		assertThatThrownBy(() -> new AuthProperties(webBaseUrl, APP_LINK, jwt()))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"   ", "jandilog", "jandilog:/auth"})
	void 앱_딥링크는_scheme_host_형태여야_한다(String deepLink) {
		assertThatThrownBy(() -> new AuthProperties("https://jandi.example.com", deepLink, jwt()))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void 앱_딥링크는_환경변수로_다른_스킴으로_바꿀_수_있다() {
		assertThat(new AuthProperties("https://jandi.example.com", "kr.club.jandi://auth", jwt()).appDeepLink())
				.isEqualTo("kr.club.jandi://auth");
		assertThat(new AuthProperties("https://jandi.example.com", "jandilog://auth", jwt()).appDeepLink())
				.isEqualTo("jandilog://auth");
	}

	@Test
	void JWT_설정이_없으면_만들_수_없다() {
		assertThatThrownBy(() -> new AuthProperties("https://jandi.example.com", APP_LINK, null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	// ----- JWT 키 검증 -----

	@Test
	void JWT_키는_43자_이상이어야_한다() {
		assertThatThrownBy(() -> new Jwt(null, "jandilog", 60)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Jwt("", "jandilog", 60)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Jwt(key(32), "jandilog", 60)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Jwt(key(42), "jandilog", 60)).isInstanceOf(IllegalArgumentException.class);
		assertThatCode(() -> new Jwt(key(43), "jandilog", 60)).doesNotThrowAnyException();
	}

	@Test
	void 예전_기준인_32바이트_반복_키는_이제_거부한다() {
		assertThatThrownBy(() -> new Jwt("x".repeat(32), "jandilog", 60)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void JWT_키_길이는_바이트_수가_아니라_글자_수로_센다() {
		// 한글 15글자는 45바이트라 바이트로 세면 통과하지만, 글자로 세면 43자에 못 미친다
		String hangul = "가나다라마바사아자차카타파하거";

		assertThatThrownBy(() -> new Jwt(hangul, "jandilog", 60)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void openssl_rand_base64_48_형태의_64자_키는_통과한다() {
		String generated = "hnTQB1F6xMCXPwqpqkE9Ru39rt7j5jHTzZddvTlabiazHBqa4Ylhg7a9b4/BDePp";

		assertThat(generated).hasSize(64);
		assertThatCode(() -> new Jwt(generated, "jandilog", 60)).doesNotThrowAnyException();
	}

	@Test
	void 서로_다른_문자가_16종_미만이면_거부한다() {
		String fifteen = randomFrom("abcdefghijklmno", 200, 1);
		String sixteen = randomFrom("abcdefghijklmnop", 200, 1);

		assertThat(fifteen.chars().distinct().count()).isEqualTo(15);
		assertThat(sixteen.chars().distinct().count()).isEqualTo(16);
		assertThatThrownBy(() -> new Jwt(fifteen, "jandilog", 60)).isInstanceOf(IllegalArgumentException.class);
		assertThatCode(() -> new Jwt(sixteen, "jandilog", 60)).doesNotThrowAnyException();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"0000000000000000000000000000000000000000000000000000000000000000",
			"abababababababababababababababababababababababababababab"})
	void 한두_문자를_되풀이한_키는_거부한다(String secret) {
		assertThatThrownBy(() -> new Jwt(secret, "jandilog", 60)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void 문자_종류가_많아도_같은_문자열을_되풀이한_키는_거부한다() {
		String unit = "abcdefghijklmnopQRSTUV0123456789"; // 32자, 서로 다른 문자 32종

		assertThatThrownBy(() -> new Jwt(unit + unit, "jandilog", 60)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Jwt(unit + unit + unit.substring(0, 20), "jandilog", 60))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void 반복처럼_보여도_끝이_다르면_반복_키로_보지_않는다() {
		String unit = "abcdefghijklmnopQRSTUV0123456789";

		assertThatCode(() -> new Jwt(unit + unit + unit.substring(0, 20) + "!", "jandilog", 60)).doesNotThrowAnyException();
	}

	@ParameterizedTest
	@ValueSource(strings = {"your-256-bit-secret", "ChangeMe", "change_me", "change-it", "Password123", "my-secret-key",
			"supersecret", "replace.me", "YOUR_SECRET"})
	void 알려진_예시와_약한_값이_들어_있으면_거부한다(String weak) {
		String secret = key(24) + weak + key(24);

		assertThatThrownBy(() -> new Jwt(secret, "jandilog", 60)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void 오류_문구에는_키_값이_드러나지_않는다() {
		String weak = key(24) + "changeme" + key(24);
		String repeated = "x".repeat(64);
		String shortKey = key(20);

		assertThatThrownBy(() -> new Jwt(weak, "jandilog", 60)).hasMessageNotContaining(weak)
				.hasMessageNotContaining("changeme");
		assertThatThrownBy(() -> new Jwt(repeated, "jandilog", 60)).hasMessageNotContaining(repeated);
		assertThatThrownBy(() -> new Jwt(shortKey, "jandilog", 60)).hasMessageNotContaining(shortKey);
	}

	@Test
	void 오류_문구는_키를_만드는_방법을_알려준다() {
		assertThatThrownBy(() -> new Jwt(key(10), "jandilog", 60)).hasMessageContaining("openssl rand -base64 48");
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"  "})
	void 발급자가_비어_있으면_만들_수_없다(String issuer) {
		assertThatThrownBy(() -> new Jwt(SECRET, issuer, 60)).isInstanceOf(IllegalArgumentException.class);
	}

	@ParameterizedTest
	@ValueSource(longs = {Long.MIN_VALUE, -1, 0, 43_201, Long.MAX_VALUE})
	void 만료_시간은_1분에서_30일_사이만_허용한다(long minutes) {
		assertThatThrownBy(() -> new Jwt(SECRET, "jandilog", minutes)).isInstanceOf(IllegalArgumentException.class);
	}

	@ParameterizedTest
	@ValueSource(longs = {1, 720, 1440, 43_200})
	void 만료_시간_경계값은_허용한다(long minutes) {
		assertThatCode(() -> new Jwt(SECRET, "jandilog", minutes)).doesNotThrowAnyException();
	}

	@Test
	void 문자열로_찍어도_JWT_키는_드러나지_않는다() {
		String secret = "this-secret-must-not-appear-in-logs-0123456789";
		AuthProperties properties = new AuthProperties("https://jandi.example.com", APP_LINK,
				new Jwt(secret, "jandilog", 60));

		assertThat(properties.toString()).doesNotContain(secret);
		assertThat(properties.jwt().toString()).doesNotContain(secret).contains("****");
	}

}
