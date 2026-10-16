package com.jandilog.exemption;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.jandilog.judgment.domain.HoldReason;
import com.jandilog.judgment.service.JudgmentRecordService.Outcome;
import com.jandilog.judgment.service.JudgmentRecordService.Type;
import com.jandilog.judgment.service.MemberJudgmentService;
import com.jandilog.testsupport.exemption.ExemptionIntegrationTest;

// 판정이 잔디를 조회하는 동안 같은 주의 면제가 승인·등록되는 경쟁 (기능명세서 5장 "면제 주간은 판정하지 않는다", 7장).
// 순서는 잔디 조회 스텁이 시작되는 순간 면제를 커밋하게 해서 고정한다: 면제 확인은 조회 전에 끝났고 판정 행은 아직 없다.
// 면제 기간은 전체 회원에 걸리므로 공유 DB의 다른 테스트와 겹치지 않는 2048년 주차만 쓴다
class JudgmentExemptionRaceIntegrationTest extends ExemptionIntegrationTest {

	private static final LocalDate WEEK = monday(2048, 6, 3);

	@Autowired
	private MemberJudgmentService memberJudgment;
	@Autowired
	private DataSource dataSource;

	@BeforeEach
	void fixClock() {
		fixClockAt(WEEK.plusWeeks(10).atTime(10, 0));
	}

	@Test
	@DisplayName("조회 중에 개인 면제가 승인되면 미달 대신 면제로 저장하고 경고를 만들지 않는다")
	void personalExemptionApprovedDuringLookupIsSavedAsExempt() {
		long leader = activeMember();
		long team = newTeam("경쟁개인팀", leader);
		long member = activeMember();
		join(team, member);
		long request = requestPersonal(leader, team, member, WEEK, "시험").path("id").asLong();
		grassClient.beforeFetch(() -> approve(request));

		Outcome outcome = memberJudgment.judge(member, WEEK);

		// 조회가 실제로 일어났고 그 안에서 승인이 커밋됐다
		assertThat(grassClient.calls()).isEqualTo(1);
		assertThat(personalExemptionStatus(request)).isEqualTo("APPROVED");
		assertThat(outcome.type()).isEqualTo(Type.CREATED);
		assertThat(outcome.judgment().getStatus().name()).isEqualTo("EXEMPT");
		assertThat(outcome.judgment().getSkipReason().name()).isEqualTo("PERSONAL_EXEMPTION");
		long judgmentId = outcome.judgment().getId();
		assertThat(judgmentStatus(judgmentId)).isEqualTo("EXEMPT");
		assertThat(skipReason(judgmentId)).isEqualTo("PERSONAL_EXEMPTION");
		assertThat(count("judgment_day where judgment_id = ?", judgmentId)).isZero();
		assertThat(count("judgment_team where judgment_id = ?", judgmentId)).isEqualTo(1);
		assertThat(warningRowCount(member)).isZero();
		assertThat(state(member).warningCount()).isZero();

		// 같은 주를 다시 돌려도 확정된 주라 건너뛴다
		assertThat(memberJudgment.judge(member, WEEK).type()).isEqualTo(Type.SKIPPED_CONFIRMED);
		assertThat(grassClient.calls()).isEqualTo(1);
	}

	@Test
	@DisplayName("조회 중에 면제 기간이 등록되면 미달 대신 면제로 저장하고 경고를 만들지 않는다")
	void exemptionPeriodRegisteredDuringLookupIsSavedAsExempt() {
		long leader = activeMember();
		long team = newTeam("경쟁기간팀", leader);
		long member = activeMember();
		join(team, member);
		grassClient.beforeFetch(() -> createPeriod(WEEK, "중간고사"));

		Outcome outcome = memberJudgment.judge(member, WEEK);

		assertThat(grassClient.calls()).isEqualTo(1);
		assertThat(periodCount(WEEK)).isEqualTo(1);
		assertThat(outcome.type()).isEqualTo(Type.CREATED);
		long judgmentId = outcome.judgment().getId();
		assertThat(judgmentStatus(judgmentId)).isEqualTo("EXEMPT");
		assertThat(skipReason(judgmentId)).isEqualTo("EXEMPTION_PERIOD");
		assertThat(count("judgment_day where judgment_id = ?", judgmentId)).isZero();
		assertThat(warningRowCount(member)).isZero();
		assertThat(state(member).warningCount()).isZero();
	}

	@Test
	@DisplayName("통과했을 주도 조회 중에 면제가 등록되면 통과가 아니라 면제로 저장한다")
	void wouldBePassWeekIsAlsoSavedAsExempt() {
		long leader = activeMember();
		long team = newTeam("경쟁통과팀", leader);
		long member = activeMember();
		join(team, member);
		// 스텁 잔디는 매일 찍히므로 기록글 1개면 통과였을 주
		data.postIndexRecord(member, WEEK.plusDays(1));
		grassClient.beforeFetch(() -> createPeriod(WEEK, "기말고사"));

		Outcome outcome = memberJudgment.judge(member, WEEK);

		assertThat(outcome.judgment().getStatus().name()).isEqualTo("EXEMPT");
		assertThat(skipReason(outcome.judgment().getId())).isEqualTo("EXEMPTION_PERIOD");
		assertThat(outcome.judgment().getVerifiedDays()).isNull();
		assertThat(outcome.judgment().getRecordCount()).isNull();
		assertThat(count("judgment_day where judgment_id = ?", outcome.judgment().getId())).isZero();
	}

	@Test
	@DisplayName("보류 자동 재시도 중에 면제가 승인되면 보류로 남기지 않고 면제로 확정하며 재시도 횟수를 올리지 않는다")
	void holdRetryWithExemptionApprovedDuringLookupIsConfirmedAsExempt() {
		long leader = activeMember();
		long team = newTeam("경쟁보류팀", leader);
		long member = activeMember();
		join(team, member);
		long holdId = data.hold(member, WEEK, "API_ERROR", 1);
		data.judgmentTeam(holdId, team);
		long request = requestPersonal(leader, team, member, WEEK, "병가").path("id").asLong();
		// 이번 조회도 실패하지만 그 사이 승인이 끝났다
		grassClient.failFor(members.find(member).orElseThrow().githubLogin(), HoldReason.API_ERROR);
		grassClient.beforeFetch(() -> approve(request));

		Outcome outcome = memberJudgment.judge(member, WEEK, true);

		assertThat(outcome.type()).isEqualTo(Type.UPDATED);
		assertThat(outcome.judgment().getId()).isEqualTo(holdId);
		assertThat(judgmentStatus(holdId)).isEqualTo("EXEMPT");
		assertThat(skipReason(holdId)).isEqualTo("PERSONAL_EXEMPTION");
		assertThat(jdbc.queryForObject("select hold_reason from weekly_judgment where id = ?", String.class, holdId))
				.isNull();
		assertThat(jdbc.queryForObject("select retry_count from weekly_judgment where id = ?", Integer.class, holdId))
				.isEqualTo(1);
		assertThat(warningRowCount(member)).isZero();
	}

	@Test
	@DisplayName("조회 중에 아무 면제도 생기지 않으면 평소처럼 미달로 저장하고 경고를 만든다")
	void withoutExemptionTheWeekIsStillJudged() {
		long leader = activeMember();
		long team = newTeam("경쟁대조팀", leader);
		long member = activeMember();
		join(team, member);
		AtomicInteger fetched = new AtomicInteger();
		grassClient.beforeFetch(fetched::incrementAndGet);

		Outcome outcome = memberJudgment.judge(member, WEEK);

		assertThat(fetched.get()).isEqualTo(1);
		assertThat(outcome.judgment().getStatus().name()).isEqualTo("FAIL");
		assertThat(warningRowCount(member)).isEqualTo(1);
		assertThat(count("judgment_day where judgment_id = ?", outcome.judgment().getId())).isEqualTo(7);
	}

	@Test
	@DisplayName("승인 트랜잭션이 회원 락을 쥔 채 커밋 전이면 판정 저장은 락에서 기다렸다가 커밋된 승인을 보고 면제로 저장한다")
	void recordWaitsForApprovingTransactionAndSeesItsApproval() throws Exception {
		long leader = activeMember();
		long team = newTeam("경쟁락팀", leader);
		long member = activeMember();
		join(team, member);
		data.personalExemptionPending(member, leader, WEEK);
		long request = jdbc.queryForObject("select id from personal_exemption where member_id = ? and week_start = ?",
				Long.class, member, WEEK);
		AtomicReference<Thread> judgingThread = new AtomicReference<>();
		ExecutorService pool = Executors.newSingleThreadExecutor(task -> {
			Thread thread = new Thread(task, "judging-under-test");
			judgingThread.set(thread);
			return thread;
		});
		try (Connection approver = dataSource.getConnection()) {
			approver.setAutoCommit(false);
			// 승인 쪽: 회원 락을 잡고 승인으로 바꾸되 커밋하지 않는다. 판정은 이 변경을 아직 볼 수 없다
			try (PreparedStatement lock = approver.prepareStatement("select id from member where id = ? for update")) {
				lock.setLong(1, member);
				lock.executeQuery().close();
			}
			try (PreparedStatement approve = approver
					.prepareStatement("update personal_exemption set status = 'APPROVED', responded_at = ? where id = ?")) {
				approve.setTimestamp(1, Timestamp.valueOf(LocalDateTime.now()));
				approve.setLong(2, request);
				approve.executeUpdate();
			}

			Future<Outcome> judging = pool.submit(() -> memberJudgment.judge(member, WEEK));
			awaitLockWait(judgingThread.get());
			assertThat(judging.isDone()).isFalse();
			approver.commit();

			Outcome outcome = judging.get(30, TimeUnit.SECONDS);
			assertThat(outcome.judgment().getStatus().name()).isEqualTo("EXEMPT");
			assertThat(outcome.judgment().getSkipReason().name()).isEqualTo("PERSONAL_EXEMPTION");
			assertThat(warningRowCount(member)).isZero();
		}
		finally {
			pool.shutdownNow();
		}
	}

	// 판정 저장이 회원 락을 잡으러 들어갈 때까지 기다린다. 승인 쪽이 락을 쥐고 있어 들어간 스레드는 락 대기에 머문다
	private static void awaitLockWait(Thread thread) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (System.nanoTime() < deadline) {
			for (StackTraceElement frame : thread.getStackTrace()) {
				if (frame.getClassName().contains("WarningRecalculationService") && frame.getMethodName().equals("lockMember")) {
					return;
				}
			}
			Thread.sleep(20);
		}
		throw new AssertionError("판정 저장이 회원 락을 잡으러 들어가지 않아요");
	}

	private int count(String fromWhere, Object... args) {
		Integer count = jdbc.queryForObject("select count(*) from " + fromWhere, Integer.class, args);
		return count == null ? 0 : count;
	}

}
