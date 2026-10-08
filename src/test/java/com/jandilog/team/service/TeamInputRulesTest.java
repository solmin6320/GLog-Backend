package com.jandilog.team.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.team.dto.TeamTimes;

// 팀 이름·소개 입력 규칙과 id 파싱, 차단 키 형식 (Spring 없이). 화면설계서 TM-02 ①②, DB명세서 2장
class TeamInputRulesTest {

	private static void assertError(Runnable call, ErrorCode expected) {
		assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(expected));
	}

	// ----- 팀 이름 1~20자 -----

	@Test
	void 팀_이름은_앞뒤_공백을_지우고_저장한다() {
		assertThat(TeamService.normalizeName("  사이드 프로젝트 A팀  ")).isEqualTo("사이드 프로젝트 A팀");
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = {"", " ", "   ", "\t", "\n"})
	void 팀_이름이_비었으면_이름_입력_오류다(String raw) {
		assertError(() -> TeamService.normalizeName(raw), ErrorCode.TEAM_NAME_REQUIRED);
	}

	@Test
	void 팀_이름_20자는_허용하고_21자는_입력_오류다() {
		String twenty = "가".repeat(TeamService.NAME_MAX_LENGTH);

		assertThat(TeamService.normalizeName(twenty)).isEqualTo(twenty);
		assertError(() -> TeamService.normalizeName(twenty + "나"), ErrorCode.INVALID_INPUT);
	}

	@Test
	void 팀_이름_길이는_공백을_지운_뒤_센다() {
		String twenty = "a".repeat(20);

		assertThat(TeamService.normalizeName("  " + twenty + "  ")).isEqualTo(twenty);
	}

	@Test
	void 팀_이름_길이는_이모지도_한_글자로_센다() {
		String twentyEmoji = "😀".repeat(20);

		assertThat(TeamService.normalizeName(twentyEmoji)).isEqualTo(twentyEmoji);
		assertError(() -> TeamService.normalizeName(twentyEmoji + "😀"), ErrorCode.INVALID_INPUT);
	}

	@Test
	void 팀_이름_가운데_공백은_그대로_둔다() {
		assertThat(TeamService.normalizeName("a  b")).isEqualTo("a  b");
	}

	// ----- 팀 소개 100자 이내(선택) -----

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = {"", " ", "   \n "})
	void 팀_소개가_비었으면_비움으로_저장한다(String raw) {
		assertThat(TeamService.normalizeDescription(raw)).isNull();
	}

	@Test
	void 팀_소개_100자는_허용하고_101자는_입력_오류다() {
		String hundred = "소".repeat(TeamService.DESCRIPTION_MAX_LENGTH);

		assertThat(TeamService.normalizeDescription(hundred)).isEqualTo(hundred);
		assertError(() -> TeamService.normalizeDescription(hundred + "개"), ErrorCode.INVALID_INPUT);
	}

	@Test
	void 팀_소개도_앞뒤_공백을_지운다() {
		assertThat(TeamService.normalizeDescription("  함께 만드는 팀  ")).isEqualTo("함께 만드는 팀");
	}

	// ----- GraphQL ID → 팀 id -----

	@ParameterizedTest
	@ValueSource(strings = {"1", "42", " 7 ", "9223372036854775807"})
	void 숫자_id는_그대로_팀_id가_된다(String raw) {
		assertThat(TeamAccessService.parseTeamId(raw)).isEqualTo(Long.parseLong(raw.strip()));
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = {"", " ", "abc", "1.5", "0", "-1", "12a", "99999999999999999999", "0x10"})
	void 숫자가_아니거나_0_이하인_id는_없는_팀과_같게_처리한다(String raw) {
		assertError(() -> TeamAccessService.parseTeamId(raw), ErrorCode.NOT_FOUND);
	}

	// ----- 추방 차단 키 (DB명세서 2장) -----

	@Test
	void 차단_키는_ban_팀id_회원id_형식이다() {
		assertThat(TeamBanService.key(12, 345)).isEqualTo("ban:12:345");
	}

	@Test
	void 차단_기간은_1년이다() {
		assertThat(TeamBanService.TTL).isEqualTo(Duration.ofDays(365));
	}

	// ----- 시각 표기 -----

	@Test
	void 시각은_오프셋_없는_ISO_문자열로_내린다() {
		assertThat(TeamTimes.format(LocalDateTime.of(2026, 10, 5, 9, 30, 15))).isEqualTo("2026-10-05T09:30:15");
		assertThat(TeamTimes.format(null)).isNull();
	}

}
