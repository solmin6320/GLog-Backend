package com.jandilog.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

// 권한 거부를 계정 상태별로 나눈다 (E-01 승인 대기, E-02 거절, E-03 비로그인, E-09 그 외)와 에러 문구 표
class ErrorCodeTest {

	private static Authentication authenticated(String... authorities) {
		return UsernamePasswordAuthenticationToken.authenticated("principal", "credentials",
				AuthorityUtils.createAuthorityList(authorities));
	}

	@Test
	void 인증_정보가_없으면_비로그인이다() {
		assertThat(ErrorCode.forAccessDenied(null)).isEqualTo(ErrorCode.UNAUTHENTICATED);
	}

	@Test
	void 익명_인증은_비로그인이다() {
		Authentication anonymous = new AnonymousAuthenticationToken("key", "anonymousUser",
				List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));

		assertThat(ErrorCode.forAccessDenied(anonymous)).isEqualTo(ErrorCode.UNAUTHENTICATED);
	}

	@Test
	void 인증이_끝나지_않은_토큰은_비로그인이다() {
		Authentication notYet = UsernamePasswordAuthenticationToken.unauthenticated("principal", "credentials");

		assertThat(ErrorCode.forAccessDenied(notYet)).isEqualTo(ErrorCode.UNAUTHENTICATED);
	}

	@Test
	void 승인_대기_계정은_E01이다() {
		assertThat(ErrorCode.forAccessDenied(authenticated("ROLE_PENDING"))).isEqualTo(ErrorCode.ACCOUNT_PENDING);
	}

	@Test
	void 거절된_계정은_E02다() {
		assertThat(ErrorCode.forAccessDenied(authenticated("ROLE_REJECTED"))).isEqualTo(ErrorCode.ACCOUNT_REJECTED);
	}

	@Test
	void 활성_회원과_관리자가_거부당하면_권한_없음_E09다() {
		assertThat(ErrorCode.forAccessDenied(authenticated("ROLE_MEMBER"))).isEqualTo(ErrorCode.FORBIDDEN);
		assertThat(ErrorCode.forAccessDenied(authenticated("ROLE_MEMBER", "ROLE_ADMIN"))).isEqualTo(ErrorCode.FORBIDDEN);
		assertThat(ErrorCode.forAccessDenied(authenticated())).isEqualTo(ErrorCode.FORBIDDEN);
	}

	@Test
	void 에러_종류별_HTTP_상태와_GraphQL_분류와_화면설계서_문구가_맞다() {
		assertCode(ErrorCode.UNAUTHENTICATED, 401, ErrorType.UNAUTHORIZED, "로그인이 만료됐어요. 다시 로그인해 주세요.");
		assertCode(ErrorCode.ACCOUNT_PENDING, 403, ErrorType.FORBIDDEN,
				"가입 승인을 기다리는 중이에요. 승인되면 바로 이용할 수 있어요.");
		assertCode(ErrorCode.ACCOUNT_REJECTED, 403, ErrorType.FORBIDDEN,
				"가입이 승인되지 않았어요. 동아리 운영진에게 문의해 주세요.");
		assertCode(ErrorCode.FORBIDDEN, 403, ErrorType.FORBIDDEN, "이 페이지에 들어올 권한이 없어요.");
		assertCode(ErrorCode.AUTH_CODE_INVALID, 400, ErrorType.BAD_REQUEST,
				"로그인 정보가 만료됐어요. 처음부터 다시 해주세요.");
		assertCode(ErrorCode.NOT_FOUND, 404, ErrorType.NOT_FOUND, "찾는 내용이 없어요. 삭제됐을 수 있어요.");
		assertCode(ErrorCode.INTERNAL_ERROR, 500, ErrorType.INTERNAL_ERROR,
				"잠시 문제가 생겼어요. 조금 뒤에 다시 시도해 주세요.");
		assertThat(ErrorCode.CONFLICT.httpStatus()).isEqualTo(409);
		assertThat(ErrorCode.CONFLICT.graphQlType()).isEqualTo(ErrorType.BAD_REQUEST);
		assertThat(ErrorCode.INVALID_INPUT.httpStatus()).isEqualTo(400);
		assertThat(ErrorCode.INVALID_INPUT.graphQlType()).isEqualTo(ErrorType.BAD_REQUEST);
		assertCode(ErrorCode.QUERY_TOO_COMPLEX, 400, ErrorType.BAD_REQUEST,
				"요청이 너무 복잡해요. 조건을 줄여서 다시 시도해 주세요.");
	}

	@Test
	void 모든_에러_문구는_비어_있지_않고_내부_정보를_담지_않는다() {
		for (ErrorCode code : ErrorCode.values()) {
			assertThat(code.message()).isNotBlank();
			assertThat(code.message()).doesNotContain("Exception", "SQL", "java.", "stack", "token");
		}
	}

	private static void assertCode(ErrorCode code, int httpStatus, ErrorType type, String message) {
		assertThat(code.httpStatus()).isEqualTo(httpStatus);
		assertThat(code.graphQlType()).isEqualTo(type);
		assertThat(code.message()).isEqualTo(message);
	}

}
