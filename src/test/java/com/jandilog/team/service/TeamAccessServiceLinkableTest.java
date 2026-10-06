package com.jandilog.team.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.team.domain.Team;
import com.jandilog.team.repository.TeamMemberRepository;
import com.jandilog.team.repository.TeamRepository;

// 글에 연결할 수 있는 팀 검사: 지금 내가 소속인 삭제되지 않은 팀만 (기능명세서 2장·3장)
@ExtendWith(MockitoExtension.class)
class TeamAccessServiceLinkableTest {

	private static final long TEAM_ID = 7L;
	private static final long MEMBER_ID = 11L;
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 1, 12, 0);

	@Mock
	private TeamRepository teamRepository;
	@Mock
	private TeamMemberRepository teamMemberRepository;
	@InjectMocks
	private TeamAccessService access;

	private static Team aliveTeam() {
		return Team.create("연결 시험", null, 99L, "CODE234567", true, NOW);
	}

	private static Team deletedTeam() {
		Team team = aliveTeam();
		team.markDeleted(NOW);
		return team;
	}

	private void assertInvalid(Long teamId) {
		assertThatThrownBy(() -> access.requireLinkable(teamId, MEMBER_ID)).isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.POST_TEAM_INVALID));
	}

	@Test
	void 팀_id가_없으면_저장소를_보지_않고_통과한다() {
		assertThatCode(() -> access.requireLinkable(null, MEMBER_ID)).doesNotThrowAnyException();

		verifyNoInteractions(teamRepository, teamMemberRepository);
	}

	@Test
	void 살아_있는_팀의_현재_팀원이면_통과한다() {
		when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(aliveTeam()));
		when(teamMemberRepository.existsByTeamIdAndMemberIdAndLeftAtIsNull(TEAM_ID, MEMBER_ID)).thenReturn(true);

		assertThatCode(() -> access.requireLinkable(TEAM_ID, MEMBER_ID)).doesNotThrowAnyException();
	}

	@Test
	void 없는_팀이면_POST_TEAM_INVALID다() {
		when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.empty());

		assertInvalid(TEAM_ID);
	}

	@Test
	void 삭제된_팀이면_팀원이었어도_POST_TEAM_INVALID다() {
		when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(deletedTeam()));

		assertInvalid(TEAM_ID);
	}

	@Test
	void 지금_소속이_아니면_POST_TEAM_INVALID다() {
		when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(aliveTeam()));
		when(teamMemberRepository.existsByTeamIdAndMemberIdAndLeftAtIsNull(TEAM_ID, MEMBER_ID)).thenReturn(false);

		assertInvalid(TEAM_ID);
	}

}
