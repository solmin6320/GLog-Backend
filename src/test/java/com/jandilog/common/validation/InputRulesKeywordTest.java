package com.jandilog.common.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;

// 관리자 검색어 검증과 LIKE 패턴 (판정 결과 · 게시물 관리 툴바 공통)
class InputRulesKeywordTest {

	@Test
	@DisplayName("비었거나 공백뿐이면 검색어 없음으로 보고 앞뒤 공백은 뺀다")
	void blankMeansNoKeyword() {
		assertThat(InputRules.keyword(null)).isNull();
		assertThat(InputRules.keyword("")).isNull();
		assertThat(InputRules.keyword("   ")).isNull();
		assertThat(InputRules.keyword("  octocat ")).isEqualTo("octocat");
	}

	@Test
	@DisplayName("50자까지 허용하고 넘으면 잘못된 입력이다 (이모지도 한 글자로 센다)")
	void limitsLengthTo50CodePoints() {
		assertThat(InputRules.keyword("가".repeat(50))).hasSize(50);
		assertThat(InputRules.keyword("😀".repeat(50))).isNotNull();
		assertThatThrownBy(() -> InputRules.keyword("가".repeat(51))).isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
	}

	@Test
	@DisplayName("LIKE 패턴은 소문자로 맞추고 와일드카드와 이스케이프 문자를 글자 그대로 찾는다")
	void likePatternEscapesWildcards() {
		assertThat(InputRules.likePattern(null)).isEqualTo("%");
		assertThat(InputRules.likePattern("OctoCat")).isEqualTo("%octocat%");
		assertThat(InputRules.likePattern("100%")).isEqualTo("%100!%%");
		assertThat(InputRules.likePattern("a_b")).isEqualTo("%a!_b%");
		assertThat(InputRules.likePattern("wow!")).isEqualTo("%wow!!%");
	}

}
