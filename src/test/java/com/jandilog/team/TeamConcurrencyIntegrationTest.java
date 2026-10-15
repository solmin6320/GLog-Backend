package com.jandilog.team;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.team.dto.SentInvitationResponse;
import com.jandilog.team.service.AcceptInvitationResult;
import com.jandilog.team.support.TeamIntegrationTest;

// 같은 팀에 대한 동시 요청이 팀 행 잠금으로 직렬화되어 한 건만 성립한다 (서비스 주석의 "동시에 두 번 보내도 한 건만").
// 예상 밖 예외(데드락·유니크 위반 등)가 나오면 실패로 본다. 초대코드 참가와 팀 삭제 경합은 TeamJoinDeleteRaceIntegrationTest가 맡는다
class TeamConcurrencyIntegrationTest extends TeamIntegrationTest {

	private static final String OK = "OK";
	private static final int ROUNDS = 5;

	@FunctionalInterface
	private interface Task {

		void run() throws Exception;

	}

	// 모든 작업을 같은 순간에 출발시켜 결과를 모은다. 정상 종료는 OK, 업무 오류는 오류 코드 이름, 그 밖은 UNEXPECTED:클래스
	private List<String> race(List<Task> tasks) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
		CountDownLatch ready = new CountDownLatch(tasks.size());
		CountDownLatch go = new CountDownLatch(1);
		try {
			List<Future<String>> futures = new ArrayList<>();
			for (Task task : tasks) {
				futures.add(pool.submit(() -> {
					ready.countDown();
					go.await(10, TimeUnit.SECONDS);
					try {
						task.run();
						return OK;
					} catch (ApiException e) {
						return e.getErrorCode().name();
					} catch (Throwable e) {
						return "UNEXPECTED:" + e.getClass().getName() + ":" + e.getMessage();
					}
				}));
			}
			assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
			go.countDown();
			List<String> results = new ArrayList<>();
			for (Future<String> future : futures) {
				results.add(future.get(60, TimeUnit.SECONDS));
			}
			return results;
		} finally {
			pool.shutdownNow();
		}
	}

	private static void assertNoUnexpected(List<String> results) {
		assertThat(results).noneMatch(r -> r.startsWith("UNEXPECTED"));
	}

	@Test
	void 같은_사람이_같은_코드로_동시에_두_번_참가해도_소속은_하나다() throws Exception {
		CreatedTeam team = newTeam(member());
		for (int round = 0; round < ROUNDS; round++) {
			long joiner = member();

			List<String> results = race(List.of(() -> joinService.joinByCode(joiner, team.inviteCode()),
					() -> joinService.joinByCode(joiner, team.inviteCode())));

			assertNoUnexpected(results);
			assertThat(results).containsExactlyInAnyOrder(OK, ErrorCode.TEAM_ALREADY_JOINED.name());
			assertThat(membershipRows(team.id(), joiner)).hasSize(1);
		}
	}

	@Test
	void 같은_사람을_동시에_두_번_초대해도_대기_초대는_하나다() throws Exception {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		for (int round = 0; round < ROUNDS; round++) {
			long invitee = member();
			String login = loginOf(invitee);

			List<String> results = race(List.of(() -> invitationService.invite(leader, team.id(), login),
					() -> invitationService.invite(leader, team.id(), login)));

			assertNoUnexpected(results);
			assertThat(results).containsExactlyInAnyOrder(OK, ErrorCode.INVITATION_DUPLICATE.name());
			assertThat(invitationRows(team.id(), invitee)).hasSize(1);
		}
	}

	@Test
	void 같은_초대를_동시에_두_번_수락해도_참가는_한_번이다() throws Exception {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		for (int round = 0; round < ROUNDS; round++) {
			long invitee = member();
			SentInvitationResponse sent = invitationService.invite(leader, team.id(), loginOf(invitee));

			List<String> results = race(List.of(() -> acceptOrThrow(invitee, sent.id()), () -> acceptOrThrow(invitee, sent.id())));

			assertNoUnexpected(results);
			assertThat(results).containsExactlyInAnyOrder(OK, ErrorCode.NOT_FOUND.name());
			assertThat(membershipRows(team.id(), invitee)).hasSize(1);
		}
	}

	// accept는 사유를 값으로 돌려주므로, 사유가 있으면 같은 오류로 던져 race 결과에 담는다
	private void acceptOrThrow(long memberId, long invitationId) {
		AcceptInvitationResult result = invitationService.accept(memberId, invitationId);
		if (result.error() != null) {
			throw new ApiException(result.error());
		}
	}

	@Test
	void 같은_팀원을_동시에_두_번_추방해도_한_번만_추방된다() throws Exception {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		for (int round = 0; round < ROUNDS; round++) {
			long target = joinedMember(team);
			long warningId = warnings.warning(target, LocalDate.of(2026, 9, 7).plusWeeks(round), team.id());

			List<String> results = race(List.of(() -> leaveService.kick(leader, team.id(), target),
					() -> leaveService.kick(leader, team.id(), target)));

			assertNoUnexpected(results);
			assertThat(results).containsExactlyInAnyOrder(OK, ErrorCode.TEAM_MEMBER_NOT_FOUND.name());
			assertThat(membershipRows(team.id(), target)).hasSize(1);
			assertThat(membershipRows(team.id(), target).get(0).get("leave_type")).isEqualTo("KICKED");
			assertThat(redis.hasKey(banKey(team.id(), target))).isTrue();
			assertThat(warnings.warning(warningId).alive()).isFalse();
		}
	}

	@Test
	void 추방과_자진_탈퇴가_겹치면_하나만_성립하고_차단_키는_추방일_때만_있다() throws Exception {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		for (int round = 0; round < ROUNDS; round++) {
			long target = joinedMember(team);

			List<String> results = race(List.of(() -> leaveService.kick(leader, team.id(), target),
					() -> leaveService.leave(target, team.id())));

			assertNoUnexpected(results);
			assertThat(results).filteredOn(OK::equals).hasSize(1);
			Object leaveType = membershipRows(team.id(), target).get(0).get("leave_type");
			assertThat(leaveType).isIn("KICKED", "SELF");
			assertThat(redis.hasKey(banKey(team.id(), target))).isEqualTo("KICKED".equals(leaveType));
		}
	}

	@Test
	void 수락과_철회가_겹치면_둘_중_하나만_이긴다() throws Exception {
		long leader = member();
		CreatedTeam team = newTeam(leader);
		for (int round = 0; round < ROUNDS; round++) {
			long invitee = member();
			SentInvitationResponse sent = invitationService.invite(leader, team.id(), loginOf(invitee));

			List<String> results = race(List.of(() -> acceptOrThrow(invitee, sent.id()),
					() -> invitationService.cancel(leader, sent.id())));

			assertNoUnexpected(results);
			assertThat(results).containsExactlyInAnyOrder(OK, ErrorCode.NOT_FOUND.name());
			boolean joined = isActiveMember(team.id(), invitee);
			List<Map<String, Object>> rows = invitationRows(team.id(), invitee);
			// 수락이 이기면 초대는 ACCEPTED로 남고, 철회가 이기면 초대가 지워지고 참가하지 못한다
			if (joined) {
				assertThat(rows).extracting(r -> r.get("status")).containsExactly("ACCEPTED");
			} else {
				assertThat(rows).isEmpty();
			}
		}
	}

	@Test
	void 위임과_추방이_겹쳐도_팀장이_추방된_상태로_남지_않는다() throws Exception {
		long leader = member();
		for (int round = 0; round < ROUNDS; round++) {
			CreatedTeam team = newTeam(leader);
			long successor = joinedMember(team);

			List<String> results = race(List.of(() -> leaveService.transferLeadership(leader, team.id(), successor),
					() -> leaveService.kick(leader, team.id(), successor)));

			assertNoUnexpected(results);
			long leaderId = ((Number) teamRow(team.id()).get("leader_id")).longValue();
			// 위임이 먼저면 이전 팀장의 추방은 권한 오류, 추방이 먼저면 위임은 대상 없음 오류다
			assertThat(results).filteredOn(OK::equals).hasSize(1);
			if (leaderId == successor) {
				assertThat(isActiveMember(team.id(), successor)).isTrue();
				assertThat(results).contains(ErrorCode.FORBIDDEN.name());
			} else {
				assertThat(leaderId).isEqualTo(leader);
				assertThat(isActiveMember(team.id(), successor)).isFalse();
				assertThat(results).contains(ErrorCode.TEAM_MEMBER_NOT_FOUND.name());
			}
		}
	}

	@Test
	void 팀_삭제와_동시_초대가_겹쳐도_삭제된_팀에_새_초대가_생기지_않는다() throws Exception {
		for (int round = 0; round < ROUNDS; round++) {
			long leader = member();
			CreatedTeam team = newTeam(leader);
			long invitee = member();
			String login = loginOf(invitee);

			List<String> results = race(List.of(() -> leaveService.delete(leader, team.id()),
					() -> invitationService.invite(leader, team.id(), login)));

			assertNoUnexpected(results);
			assertThat(teamRow(team.id()).get("deleted_at")).isNotNull();
			// 삭제가 먼저면 초대는 없는 팀 오류이고, 초대가 먼저면 삭제 전에 만들어진 대기 초대 한 건만 남는다
			if (results.contains(ErrorCode.NOT_FOUND.name())) {
				assertThat(invitationRows(team.id(), invitee)).isEmpty();
			} else {
				assertThat(invitationRows(team.id(), invitee)).hasSize(1);
			}
			assertThat(allReceived(invitee)).isEmpty();
		}
	}

}
