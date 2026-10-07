package com.jandilog.team;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.team.dto.CreateTeamInput;
import com.jandilog.team.dto.CreateTeamPayload;
import com.jandilog.team.service.TeamJoinService;
import com.jandilog.team.service.TeamLeaveService;
import com.jandilog.team.service.TeamService;
import com.jandilog.testsupport.auth.AuthIntegrationTest;

// 초대코드 참가와 팀 삭제가 겹칠 때 삭제된 팀에 소속이 생기지 않는다 (기능명세서 2장, E-10)
class TeamJoinDeleteRaceIntegrationTest extends AuthIntegrationTest {

	@Autowired
	private TeamService teamService;
	@Autowired
	private TeamJoinService joinService;
	@Autowired
	private TeamLeaveService leaveService;
	@Autowired
	private PlatformTransactionManager txManager;

	private final List<Long> teamIds = new ArrayList<>();

	@AfterEach
	void cleanTeams() {
		for (Long teamId : teamIds) {
			jdbc.update("delete from team_invitation where team_id = ?", teamId);
			jdbc.update("delete from team_member where team_id = ?", teamId);
			jdbc.update("delete from team where id = ?", teamId);
		}
		teamIds.clear();
	}

	@Test
	void 삭제가_커밋되기_전에_코드_참가가_팀_잠금을_기다리면_삭제된_팀에_참가하지_못한다() throws Exception {
		long leader = members.active();
		long joiner = members.active();
		CreatePayload created = create(leader);

		TransactionTemplate tx = new TransactionTemplate(txManager);
		tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
		CountDownLatch deleted = new CountDownLatch(1);
		CountDownLatch joinStarted = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			// 삭제 트랜잭션을 커밋 직전에 붙잡아 둔다
			Future<?> deleter = pool.submit(() -> tx.executeWithoutResult(status -> {
				leaveService.delete(leader, created.teamId());
				deleted.countDown();
				try {
					joinStarted.await(10, TimeUnit.SECONDS);
					// 참가 쪽이 첫 조회를 마치고 팀 행 잠금에서 기다릴 시간을 준다
					Thread.sleep(2500);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}));
			assertThat(deleted.await(20, TimeUnit.SECONDS)).isTrue();
			Future<Object> join = pool.submit(() -> {
				joinStarted.countDown();
				try {
					return joinService.joinByCode(joiner, created.inviteCode());
				} catch (ApiException e) {
					return e;
				}
			});
			deleter.get(30, TimeUnit.SECONDS);
			Object result = join.get(30, TimeUnit.SECONDS);

			assertThat(result).isInstanceOfSatisfying(ApiException.class,
					e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TEAM_CODE_NOT_FOUND));
			assertThat(activeMembership(created.teamId(), joiner)).isZero();
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void 삭제가_끝난_팀의_초대코드로는_참가하지_못한다() {
		long leader = members.active();
		long joiner = members.active();
		CreatePayload created = create(leader);
		leaveService.delete(leader, created.teamId());

		Object result;
		try {
			result = joinService.joinByCode(joiner, created.inviteCode());
		} catch (ApiException e) {
			result = e;
		}

		assertThat(result).isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TEAM_CODE_NOT_FOUND));
		assertThat(activeMembership(created.teamId(), joiner)).isZero();
	}

	private CreatePayload create(long leader) {
		CreateTeamPayload payload = teamService.create(leader, new CreateTeamInput("경합-" + members.tag(), null, true));
		long teamId = payload.team().id();
		teamIds.add(teamId);
		return new CreatePayload(teamId, payload.inviteCode());
	}

	private int activeMembership(long teamId, long memberId) {
		Integer count = jdbc.queryForObject(
				"select count(*) from team_member where team_id = ? and member_id = ? and left_at is null", Integer.class,
				teamId, memberId);
		return count == null ? 0 : count;
	}

	private record CreatePayload(long teamId, String inviteCode) {
	}

}
