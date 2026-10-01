package com.jandilog.member.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

// 닉네임 산출 규칙: GitHub 표시 이름, 없으면 아이디, 컬럼 길이 30자 (DB명세서 1-1)
class MemberNicknameTest {

	@Test
	void 표시_이름이_있으면_그대로_쓴다() {
		assertThat(MemberService.toNickname("홍길동", "octocat")).isEqualTo("홍길동");
	}

	@Test
	void 표시_이름이_없으면_GitHub_아이디를_쓴다() {
		assertThat(MemberService.toNickname(null, "octocat")).isEqualTo("octocat");
	}

	@Test
	void 표시_이름이_공백뿐이면_GitHub_아이디를_쓴다() {
		assertThat(MemberService.toNickname("   ", "octocat")).isEqualTo("octocat");
		assertThat(MemberService.toNickname("", "octocat")).isEqualTo("octocat");
	}

	@Test
	void 앞뒤_공백은_잘라낸다() {
		assertThat(MemberService.toNickname("  홍길동  ", "octocat")).isEqualTo("홍길동");
	}

	@Test
	void 정확히_30자는_그대로_둔다() {
		String name = "가".repeat(30);

		assertThat(MemberService.toNickname(name, "octocat")).isEqualTo(name);
	}

	@Test
	void 서른한_글자부터는_30자로_자른다() {
		assertThat(MemberService.toNickname("a".repeat(31), "octocat")).isEqualTo("a".repeat(30));
		assertThat(MemberService.toNickname("가".repeat(100), "octocat")).isEqualTo("가".repeat(30));
	}

	@Test
	void 이모지는_글자_단위로_세어서_반쪽이_남지_않는다() {
		// 서로게이트 쌍 글자 31개(UTF-16으로는 62칸)
		String name = "😀".repeat(31);

		String nickname = MemberService.toNickname(name, "octocat");

		assertThat(nickname.codePointCount(0, nickname.length())).isEqualTo(30);
		assertThat(nickname).isEqualTo("😀".repeat(30));
	}

	@Test
	void 표시_이름이_없을_때_긴_GitHub_아이디도_30자로_자른다() {
		String login = "a".repeat(39);

		assertThat(MemberService.toNickname(null, login)).isEqualTo("a".repeat(30));
	}

}
