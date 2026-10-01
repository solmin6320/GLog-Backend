package com.jandilog.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

// Redis는 mock으로 대신하고 코드 형식·키·TTL·소비 규칙만 본다 (E-05, E-06)
@ExtendWith(MockitoExtension.class)
class AuthCodeServiceTest {

	private static final String VALID_CODE = "A".repeat(43);

	@Mock
	private StringRedisTemplate redis;
	@Mock
	private ValueOperations<String, String> ops;

	private AuthCodeService service;

	@BeforeEach
	void setUp() {
		service = new AuthCodeService(redis);
	}

	@Test
	void 발급한_코드는_base64url_43자이고_키_접두어와_TTL_60초로_저장된다() {
		when(redis.opsForValue()).thenReturn(ops);

		String code = service.issue(42L);

		assertThat(code).matches("^[A-Za-z0-9_-]{43}$");
		ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
		verify(ops).set(eq("authcode:" + code), eq("42"), ttl.capture());
		assertThat(ttl.getValue()).isEqualTo(Duration.ofSeconds(60));
	}

	@Test
	void 발급할_때마다_다른_코드가_나온다() {
		when(redis.opsForValue()).thenReturn(ops);

		Set<String> codes = new HashSet<>();
		for (int i = 0; i < 200; i++) {
			codes.add(service.issue(1L));
		}

		assertThat(codes).hasSize(200);
	}

	static Stream<String> 형식이_틀린_코드() {
		return Stream.of(
				null, "", " ", "short",
				// 42자, 44자
				"A".repeat(42), "A".repeat(44),
				// base64 표준 문자, 패딩, 와일드카드, 공백, 한글이 섞인 43자
				"+" + "A".repeat(42), "/" + "A".repeat(42), "=" + "A".repeat(42),
				"*" + "A".repeat(42), " " + "A".repeat(42), "가" + "A".repeat(42));
	}

	@ParameterizedTest
	@MethodSource("형식이_틀린_코드")
	void 형식이_틀린_코드는_Redis를_부르지_않고_빈_값을_돌려준다(String code) {
		assertThat(service.consume(code)).isEmpty();

		verifyNoInteractions(redis);
	}

	@Test
	void 끝에_줄바꿈이_붙은_코드는_형식_불량이다() {
		assertThat(service.consume(VALID_CODE + "\n")).isEmpty();

		verifyNoInteractions(redis);
	}

	@Test
	void 정상_코드는_읽는_동시에_지우고_회원_id를_돌려준다() {
		when(redis.opsForValue()).thenReturn(ops);
		when(ops.getAndDelete("authcode:" + VALID_CODE)).thenReturn("42");

		assertThat(service.consume(VALID_CODE)).isEqualTo(Optional.of(42L));

		// 조회와 삭제는 getAndDelete 한 번이어야 두 번째 교환이 항상 빈 값이다 (E-05)
		verify(ops).getAndDelete(anyString());
		verify(ops, never()).get(any());
	}

	@Test
	void Redis에_없는_코드는_빈_값이다() {
		when(redis.opsForValue()).thenReturn(ops);
		when(ops.getAndDelete("authcode:" + VALID_CODE)).thenReturn(null);

		assertThat(service.consume(VALID_CODE)).isEmpty();
	}

	@Test
	void 값이_숫자가_아니면_빈_값이다() {
		when(redis.opsForValue()).thenReturn(ops);
		when(ops.getAndDelete("authcode:" + VALID_CODE)).thenReturn("not-a-number");

		assertThat(service.consume(VALID_CODE)).isEmpty();
	}

}
