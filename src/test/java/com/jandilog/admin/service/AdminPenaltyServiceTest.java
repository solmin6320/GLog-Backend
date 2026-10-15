package com.jandilog.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.jandilog.admin.dto.AdminPenaltyTarget;
import com.jandilog.admin.dto.AdminPenaltyTargetPage;
import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.common.pagination.CursorCodec;
import com.jandilog.member.domain.Member;
import com.jandilog.member.repository.MemberRepository;
import com.jandilog.team.query.TeamReadModel;
import com.jandilog.warning.domain.WarningRecalcResult;
import com.jandilog.warning.repository.WarningRepository;
import com.jandilog.warning.repository.WarningTeamRepository;
import com.jandilog.warning.service.PenaltyFulfillmentService;
import com.jandilog.warning.service.PenaltyTargetService;
import com.jandilog.warning.service.PenaltyTargetService.Target;
import com.jandilog.warning.service.WarningRecalculationService;

// 벌칙 대상 목록의 커서 (Q-06): 도달 주 · 회원 id 순으로 20명씩 나뉘고 같은 주에 도달한 회원도 빠짐없이 이어진다
class AdminPenaltyServiceTest {

	private static final LocalDate W1 = LocalDate.of(2026, 8, 3);
	private static final LocalDate W2 = LocalDate.of(2026, 8, 10);

	private PenaltyTargetService targetService;
	private AdminPenaltyService service;

	@BeforeEach
	void setUp() {
		targetService = mock(PenaltyTargetService.class);
		MemberRepository memberRepository = mock(MemberRepository.class);
		WarningRecalculationService recalculation = mock(WarningRecalculationService.class);
		service = new AdminPenaltyService(targetService, mock(PenaltyFulfillmentService.class), recalculation,
				mock(WarningRepository.class), mock(WarningTeamRepository.class), memberRepository,
				mock(TeamReadModel.class));

		List<Member> members = new ArrayList<>();
		for (long id = 1; id <= 25; id++) {
			Member member = mock(Member.class);
			when(member.getId()).thenReturn(id);
			when(member.getNickname()).thenReturn("회원" + id);
			when(member.getGithubLogin()).thenReturn("user" + id);
			members.add(member);
			// 팀을 읽을 필요가 없도록 경고 이력이 비어 있는 보류 상태로 둔다
			when(recalculation.calculate(id)).thenReturn(new WarningRecalcResult.OnHold(List.of(W2)));
		}
		when(memberRepository.findAllById(anyIterable())).thenAnswer(call -> {
			List<Long> wanted = new ArrayList<>();
			call.<Iterable<Long>>getArgument(0).forEach(wanted::add);
			return members.stream().filter(member -> wanted.contains(member.getId())).toList();
		});
	}

	@Test
	@DisplayName("25명이면 20명 + 5명으로 나뉘고 끝 페이지에 nextCursor가 없다")
	void pagesTwentyAtATime() {
		List<Target> targets = new ArrayList<>();
		// 1~12는 W1, 13~25는 W2에 도달 (같은 주 안에서는 id 순)
		for (long id = 1; id <= 25; id++) {
			targets.add(new Target(id, 3, id <= 12 ? W1 : W2));
		}
		when(targetService.findTargets()).thenReturn(targets);

		AdminPenaltyTargetPage first = service.list(null);
		assertThat(first.items()).hasSize(20);
		assertThat(first.nextCursor()).isNotNull();
		assertThat(first.totalCount()).isEqualTo(25);

		AdminPenaltyTargetPage second = service.list(first.nextCursor());
		assertThat(second.items()).hasSize(5);
		assertThat(second.nextCursor()).isNull();

		List<Long> seen = new ArrayList<>();
		first.items().forEach(item -> seen.add(item.member().id()));
		second.items().forEach(item -> seen.add(item.member().id()));
		assertThat(seen).containsExactlyElementsOf(targets.stream().map(Target::memberId).toList());
		assertThat(second.items()).extracting(AdminPenaltyTarget::reachedWeek).containsOnly(W2.toString());
	}

	@Test
	@DisplayName("정확히 20명이면 다음 페이지가 없다")
	void exactlyOnePageHasNoNextCursor() {
		List<Target> targets = new ArrayList<>();
		for (long id = 1; id <= 20; id++) {
			targets.add(new Target(id, 3, W1));
		}
		when(targetService.findTargets()).thenReturn(targets);

		AdminPenaltyTargetPage page = service.list(null);

		assertThat(page.items()).hasSize(20);
		assertThat(page.nextCursor()).isNull();
	}

	@Test
	@DisplayName("형식이 틀린 커서는 잘못된 입력이다")
	void rejectsMalformedCursor() {
		when(targetService.findTargets()).thenReturn(List.of());

		for (String cursor : List.of("%%%", CursorCodec.encode("not-a-cursor"), CursorCodec.encode("2026-08-03:abc"),
				CursorCodec.encode("garbage:1"))) {
			assertThatThrownBy(() -> service.list(cursor)).isInstanceOfSatisfying(ApiException.class,
					e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
		}
	}

}
