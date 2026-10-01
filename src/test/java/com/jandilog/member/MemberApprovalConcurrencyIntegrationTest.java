package com.jandilog.member;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.member.domain.MemberStatus;
import com.jandilog.member.service.MemberApprovalService;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;

// 관리자 둘 이상이 같은 회원을 동시에 처리해도 한 번만 적용된다 (조건부 UPDATE + 같은 트랜잭션의 로그)
class MemberApprovalConcurrencyIntegrationTest extends ApprovalIntegrationTest {

	private enum Outcome {
		SUCCESS, CONFLICT, OTHER
	}

	@Autowired
	private MemberApprovalService approvalService;

	// 모든 작업이 배리어를 같이 넘은 뒤 동시에 시작한다
	private List<Outcome> runTogether(List<Callable<Object>> tasks) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
		try {
			CyclicBarrier barrier = new CyclicBarrier(tasks.size());
			List<Future<Outcome>> futures = new ArrayList<>();
			for (Callable<Object> task : tasks) {
				futures.add(pool.submit(() -> {
					barrier.await(10, TimeUnit.SECONDS);
					try {
						task.call();
						return Outcome.SUCCESS;
					} catch (ApiException e) {
						return e.getErrorCode() == ErrorCode.CONFLICT ? Outcome.CONFLICT : Outcome.OTHER;
					}
				}));
			}
			List<Outcome> outcomes = new ArrayList<>();
			for (Future<Outcome> future : futures) {
				outcomes.add(future.get(60, TimeUnit.SECONDS));
			}
			return outcomes;
		} finally {
			pool.shutdownNow();
		}
	}

	private static long count(List<Outcome> outcomes, Outcome kind) {
		return outcomes.stream().filter(o -> o == kind).count();
	}

	@Test
	void 같은_회원을_동시에_승인하면_한_번만_성공한다() throws Exception {
		for (int round = 0; round < 5; round++) {
			long id = members.pending();
			List<Callable<Object>> tasks = new ArrayList<>();
			for (int i = 0; i < 8; i++) {
				tasks.add(() -> approvalService.approve(Long.toString(id)));
			}

			List<Outcome> outcomes = runTogether(tasks);

			assertThat(count(outcomes, Outcome.SUCCESS)).isEqualTo(1);
			assertThat(count(outcomes, Outcome.CONFLICT)).isEqualTo(7);
			assertThat(members.find(id).orElseThrow().status()).isEqualTo(MemberStatus.ACTIVE);
		}
	}

	@Test
	void 같은_회원을_동시에_거절하면_성공_1건에_로그_1건이다() throws Exception {
		long otherAdmin = members.admin();
		for (int round = 0; round < 5; round++) {
			long id = members.pending();
			List<Callable<Object>> tasks = new ArrayList<>();
			for (int i = 0; i < 8; i++) {
				long admin = i % 2 == 0 ? adminId : otherAdmin;
				tasks.add(() -> approvalService.reject(admin, Long.toString(id)));
			}

			List<Outcome> outcomes = runTogether(tasks);

			assertThat(count(outcomes, Outcome.SUCCESS)).isEqualTo(1);
			assertThat(count(outcomes, Outcome.CONFLICT)).isEqualTo(7);
			assertThat(members.find(id).orElseThrow().status()).isEqualTo(MemberStatus.REJECTED);
			assertThat(members.countActionLogs(id)).isEqualTo(1);
		}
	}

	@Test
	void 승인과_거절이_겹치면_둘_중_하나만_성공하고_로그는_거절이_이긴_때만_1건이다() throws Exception {
		int rejectWins = 0;
		int approveWins = 0;
		for (int round = 0; round < 30; round++) {
			long id = members.pending();
			List<Callable<Object>> tasks = List.of(
					() -> approvalService.approve(Long.toString(id)),
					() -> approvalService.reject(adminId, Long.toString(id)));

			List<Outcome> outcomes = runTogether(tasks);

			assertThat(count(outcomes, Outcome.SUCCESS)).as("round %d", round).isEqualTo(1);
			assertThat(count(outcomes, Outcome.CONFLICT)).as("round %d", round).isEqualTo(1);
			MemberStatus status = members.find(id).orElseThrow().status();
			if (outcomes.get(0) == Outcome.SUCCESS) {
				approveWins++;
				assertThat(status).isEqualTo(MemberStatus.ACTIVE);
				assertThat(members.countActionLogs(id)).isZero();
				assertThat(members.find(id).orElseThrow().approvedAt()).isNotNull();
			} else {
				rejectWins++;
				assertThat(status).isEqualTo(MemberStatus.REJECTED);
				assertThat(members.countActionLogs(id)).isEqualTo(1);
				assertThat(members.find(id).orElseThrow().approvedAt()).isNull();
			}
		}
		// 어느 한쪽만 이기는 구조가 아닌지(순서가 고정되지 않는지)는 참고용으로만 본다
		assertThat(approveWins + rejectWins).isEqualTo(30);
	}

	@Test
	void HTTP로도_동시_승인과_거절은_한쪽만_성공한다() throws Exception {
		String otherAdminToken = tokenOf(members.admin());
		for (int round = 0; round < 5; round++) {
			long id = members.pending();
			List<Callable<Object>> tasks = List.of(
					() -> require(approve(adminToken, id)),
					() -> require(reject(otherAdminToken, id)),
					() -> require(approve(otherAdminToken, id)),
					() -> require(reject(adminToken, id)));

			List<Outcome> outcomes = runTogether(tasks);

			assertThat(count(outcomes, Outcome.SUCCESS)).as("round %d", round).isEqualTo(1);
			assertThat(count(outcomes, Outcome.CONFLICT)).as("round %d", round).isEqualTo(3);
			MemberStatus status = members.find(id).orElseThrow().status();
			int logs = members.countActionLogs(id);
			assertThat(logs).isEqualTo(status == MemberStatus.REJECTED ? 1 : 0);
		}
	}

	// GraphQL 응답을 ApiException으로 바꿔 runTogether가 같은 방식으로 세게 한다
	private Object require(GraphQlResponse response) {
		if (!response.hasErrors()) {
			return response;
		}
		String code = response.errorCode();
		ErrorCode errorCode = code == null ? ErrorCode.INTERNAL_ERROR : ErrorCode.valueOf(code);
		throw new ApiException(errorCode);
	}

	@Test
	void 거절과_되돌리기가_겹쳐도_로그는_성공한_거절_수만큼만_남는다() throws Exception {
		for (int round = 0; round < 10; round++) {
			long id = members.pending();
			// 되돌리기는 REJECTED일 때만 성공한다. 거절이 먼저 성공했을 때만 되돌리기도 성공할 수 있다
			List<Callable<Object>> tasks = List.of(
					() -> approvalService.reject(adminId, Long.toString(id)),
					() -> approvalService.revertToPending(Long.toString(id)));

			List<Outcome> outcomes = runTogether(tasks);

			assertThat(count(outcomes, Outcome.OTHER)).isZero();
			assertThat(outcomes.get(0)).isEqualTo(Outcome.SUCCESS);
			assertThat(members.countActionLogs(id)).isEqualTo(1);
			MemberStatus status = members.find(id).orElseThrow().status();
			// 되돌리기가 거절 뒤에 실행됐으면 PENDING, 거절보다 먼저 시도해서 실패했으면 REJECTED
			assertThat(status).isEqualTo(outcomes.get(1) == Outcome.SUCCESS ? MemberStatus.PENDING : MemberStatus.REJECTED);
		}
	}

}
