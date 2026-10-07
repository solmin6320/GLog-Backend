package com.jandilog.testsupport.exemption;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.databind.JsonNode;
import com.jandilog.judgment.domain.JudgmentWeek;
import com.jandilog.testsupport.admin.AdminIntegrationTest;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;
import com.jandilog.warning.domain.WarningRecalcResult;
import com.jandilog.warning.service.WarningRecalculationService;

// 면제·정정 통합 테스트 공통: GraphQL 문서, 판정 이력 만들기, 변경 여부 비교용 스냅샷, 동시 호출.
// 면제 기간은 주 하나가 전체 회원에 걸리고 week_start가 UNIQUE라 공유 DB의 다른 테스트와 겹치지 않도록
// 2046~2049년 주차만 쓰고, 이 범위의 면제 기간 행은 테스트 전후에 지운다. 서브클래스 정리가 먼저 돌아 admin 회원을 지울 수 있다
public abstract class ExemptionIntegrationTest extends AdminIntegrationTest {

	protected static final ZoneId KST = ZoneId.of("Asia/Seoul");
	protected static final LocalDate PERIOD_RANGE_FROM = LocalDate.of(2046, 1, 1);
	protected static final LocalDate PERIOD_RANGE_TO = LocalDate.of(2049, 12, 31);
	protected static final LocalDateTime JOINED_AT = LocalDateTime.of(2026, 1, 1, 0, 0);

	private static final String IMPACT = "onHold warningCountBefore warningCountAfter entersPenalty leavesPenalty recordOnly affectedWeeks";

	protected static final String CREATE_PERIOD = """
			mutation($input: ExemptionPeriodInput!) {
			  createExemptionPeriod(input: $input) { id weekStart weekEnd reason createdAt updatedAt }
			}
			""";
	protected static final String UPDATE_PERIOD = """
			mutation($id: ID!, $input: ExemptionPeriodInput!) {
			  updateExemptionPeriod(id: $id, input: $input) { id weekStart weekEnd reason createdAt updatedAt }
			}
			""";
	protected static final String DELETE_PERIOD = """
			mutation($id: ID!) { deleteExemptionPeriod(id: $id) }
			""";
	protected static final String LIST_PERIODS = """
			query($after: String) {
			  exemptionPeriods(after: $after) { nextCursor items { id weekStart weekEnd reason updatedAt } }
			}
			""";
	protected static final String PREVIEW_PERIOD = """
			query($weekStart: String!) {
			  previewExemptionPeriod(weekStart: $weekStart) {
			    weekStart retroactive
			    items { member { id } currentStatus impact { %s } }
			  }
			}
			""".formatted(IMPACT);

	protected static final String REQUEST_PERSONAL = """
			mutation($teamId: ID!, $memberId: ID!, $weekStart: String!, $reason: String!) {
			  requestPersonalExemption(teamId: $teamId, memberId: $memberId, weekStart: $weekStart, reason: $reason) {
			    id member { id } requester { id } weekStart weekEnd status reason rejectReason respondedAt createdAt
			    overlapsExemptionPeriod
			  }
			}
			""";
	protected static final String MY_PERSONAL = """
			query($after: String) {
			  myPersonalExemptionRequests(after: $after) {
			    nextCursor
			    items { id member { id } requester { id } weekStart status reason rejectReason overlapsExemptionPeriod }
			  }
			}
			""";
	protected static final String ADMIN_PERSONAL = """
			query($status: PersonalExemptionStatus, $after: String) {
			  personalExemptionRequests(status: $status, after: $after) {
			    nextCursor
			    items { id member { id } requester { id } weekStart status reason rejectReason overlapsExemptionPeriod }
			  }
			}
			""";
	protected static final String PREVIEW_APPROVAL = """
			query($id: ID!) {
			  previewPersonalExemptionApproval(id: $id) {
			    weekStart retroactive
			    items { member { id } currentStatus impact { %s } }
			  }
			}
			""".formatted(IMPACT);
	protected static final String APPROVE = """
			mutation($id: ID!) {
			  approvePersonalExemption(id: $id) { id status respondedAt rejectReason overlapsExemptionPeriod }
			}
			""";
	protected static final String REJECT = """
			mutation($id: ID!, $reason: String!) {
			  rejectPersonalExemption(id: $id, reason: $reason) { id status respondedAt rejectReason }
			}
			""";

	protected static final String PREVIEW_CORRECTION = """
			query($judgmentId: ID!, $status: AdminCorrectionStatus!) {
			  previewJudgmentCorrection(judgmentId: $judgmentId, status: $status) {
			    judgmentId member { id } weekStart currentStatus targetStatus impact { %s }
			  }
			}
			""".formatted(IMPACT);
	protected static final String CORRECT = """
			mutation($input: JudgmentCorrectionInput!) {
			  correctJudgment(input: $input) { judgmentId member { id } weekStart status onHold warningCount penaltyTarget }
			}
			""";
	protected static final String RETRY_HOLD = """
			mutation($memberId: ID!, $weekStart: String!) {
			  retryJudgmentHold(memberId: $memberId, weekStart: $weekStart) {
			    member { id } weekStart status holdReason resolved
			  }
			}
			""";

	@Autowired
	protected WarningRecalculationService recalculation;

	// 한 회원의 판정 이력. 인덱스 i는 firstWeek에서 i주 뒤. warning(i)는 미달 주의 경고 id이고 아니면 0
	public record History(long memberId, LocalDate firstWeek, long[] judgmentIds, long[] warningIds) {

		public LocalDate week(int index) {
			return firstWeek.plusWeeks(index);
		}

		public long judgment(int index) {
			return judgmentIds[index];
		}

		public long warning(int index) {
			return warningIds[index];
		}

	}

	@BeforeEach
	protected void clearLeftoverPeriods() {
		deleteReservedPeriods();
	}

	@AfterEach
	protected void tearDownExemption() {
		deleteReservedPeriods();
	}

	private void deleteReservedPeriods() {
		jdbc.update("delete from exemption_period where week_start between ? and ?", PERIOD_RANGE_FROM, PERIOD_RANGE_TO);
	}

	protected void fixClockAt(LocalDateTime kst) {
		clock.fixAt(kst.atZone(KST).toInstant());
	}

	protected static LocalDate monday(int year, int month, int day) {
		return JudgmentWeek.mondayOf(LocalDate.of(year, month, day));
	}

	// ---- 이력 만들기 ----

	// script 글자: P 통과, F 미달(경고+카테고리 포함), E 면제 기간으로 면제, X 첫 참가 주 제외(팀 스냅샷 있음),
	// N 팀 없음 제외(팀 스냅샷 없음), H 보류(API_ERROR, 팀 스냅샷 있음). 공백은 무시한다
	protected History history(long memberId, LocalDate firstWeek, String script, long... teams) {
		String letters = script.replace(" ", "");
		long[] judgments = new long[letters.length()];
		long[] warnings = new long[letters.length()];
		for (int i = 0; i < letters.length(); i++) {
			LocalDate week = firstWeek.plusWeeks(i);
			switch (letters.charAt(i)) {
				case 'P' -> judgments[i] = data.pass(memberId, week);
				case 'F' -> {
					judgments[i] = data.fail(memberId, week);
					warnings[i] = data.warning(memberId, week, null, null);
					for (long teamId : teams) {
						data.warningTeam(warnings[i], teamId, null);
					}
				}
				case 'E' -> judgments[i] = data.exempt(memberId, week);
				case 'X' -> judgments[i] = data.firstWeek(memberId, week);
				case 'N' -> judgments[i] = data.judgment(memberId, week, "EXCLUDED", "NO_TEAM", null, 0, null, null,
						false, week.plusDays(7).atTime(7, 0));
				case 'H' -> judgments[i] = data.hold(memberId, week, "API_ERROR", 0);
				default -> throw new IllegalArgumentException("알 수 없는 판정 글자: " + letters.charAt(i));
			}
			if (letters.charAt(i) != 'N') {
				for (long teamId : teams) {
					data.judgmentTeam(judgments[i], teamId);
				}
			}
		}
		return new History(memberId, firstWeek, judgments, warnings);
	}

	protected long newTeam(String prefix, long leaderId) {
		long team = data.team(data.uniqueName(prefix), true, leaderId);
		data.teamMember(team, leaderId, JOINED_AT, null);
		return team;
	}

	protected void join(long teamId, long memberId) {
		data.teamMember(teamId, memberId, JOINED_AT, null);
	}

	// ---- GraphQL 호출 ----

	protected static Map<String, Object> correctionInput(long judgmentId, String status, String reason,
			String expectedStatus) {
		Map<String, Object> input = new LinkedHashMap<>();
		input.put("judgmentId", Long.toString(judgmentId));
		input.put("status", status);
		input.put("reason", reason);
		if (expectedStatus != null) {
			input.put("expectedStatus", expectedStatus);
		}
		return Map.of("input", input);
	}

	protected GraphQlResponse correctRaw(long judgmentId, String status, String reason) {
		return asAdmin(CORRECT, correctionInput(judgmentId, status, reason, null));
	}

	protected JsonNode correct(long judgmentId, String status, String reason) {
		return dataOf(correctRaw(judgmentId, status, reason)).path("correctJudgment");
	}

	protected JsonNode previewCorrection(long judgmentId, String status) {
		return dataOf(asAdmin(PREVIEW_CORRECTION, Map.of("judgmentId", Long.toString(judgmentId), "status", status)))
				.path("previewJudgmentCorrection");
	}

	protected static Map<String, Object> periodInput(LocalDate week, String reason) {
		return Map.of("input", Map.of("weekStart", week.toString(), "reason", reason));
	}

	protected GraphQlResponse createPeriodRaw(LocalDate week, String reason) {
		return asAdmin(CREATE_PERIOD, periodInput(week, reason));
	}

	protected JsonNode createPeriod(LocalDate week, String reason) {
		return dataOf(createPeriodRaw(week, reason)).path("createExemptionPeriod");
	}

	protected JsonNode previewPeriod(LocalDate week) {
		return dataOf(asAdmin(PREVIEW_PERIOD, Map.of("weekStart", week.toString()))).path("previewExemptionPeriod");
	}

	protected static Map<String, Object> requestVariables(long teamId, long memberId, LocalDate week, String reason) {
		return Map.of("teamId", Long.toString(teamId), "memberId", Long.toString(memberId), "weekStart",
				week.toString(), "reason", reason);
	}

	protected GraphQlResponse requestPersonalRaw(long requesterId, long teamId, long memberId, LocalDate week,
			String reason) {
		return asMember(requesterId, REQUEST_PERSONAL, requestVariables(teamId, memberId, week, reason));
	}

	protected JsonNode requestPersonal(long requesterId, long teamId, long memberId, LocalDate week, String reason) {
		return dataOf(requestPersonalRaw(requesterId, teamId, memberId, week, reason)).path("requestPersonalExemption");
	}

	protected JsonNode approve(long id) {
		return dataOf(asAdmin(APPROVE, Map.of("id", Long.toString(id)))).path("approvePersonalExemption");
	}

	protected static Map<String, Object> idVariables(long id) {
		return Map.of("id", Long.toString(id));
	}

	// 그 주 요약에서 회원의 영향 항목. 없으면 실패
	protected static JsonNode impactOf(JsonNode preview, long memberId) {
		for (JsonNode item : preview.path("items")) {
			if (item.path("member").path("id").asLong() == memberId) {
				return item;
			}
		}
		throw new AssertionError("미리보기에 회원이 없어요: " + memberId + " / " + preview);
	}

	// ---- 상태 읽기 ----

	protected WarningRecalcResult.Calculated state(long memberId) {
		WarningRecalcResult result = recalculation.calculate(memberId);
		assertThat(result).isInstanceOf(WarningRecalcResult.Calculated.class);
		return (WarningRecalcResult.Calculated) result;
	}

	protected String judgmentStatus(long judgmentId) {
		return jdbc.queryForObject("select status from weekly_judgment where id = ?", String.class, judgmentId);
	}

	protected String skipReason(long judgmentId) {
		return jdbc.queryForObject("select skip_reason from weekly_judgment where id = ?", String.class, judgmentId);
	}

	protected boolean corrected(long judgmentId) {
		Boolean corrected = jdbc.queryForObject("select corrected from weekly_judgment where id = ?", Boolean.class,
				judgmentId);
		return corrected != null && corrected;
	}

	protected int periodCount(LocalDate week) {
		Integer count = jdbc.queryForObject("select count(*) from exemption_period where week_start = ?",
				Integer.class, week);
		return count == null ? 0 : count;
	}

	protected int correctionCount(long judgmentId) {
		Integer count = jdbc.queryForObject("select count(*) from judgment_correction where judgment_id = ?",
				Integer.class, judgmentId);
		return count == null ? 0 : count;
	}

	protected int warningRowCount(long memberId) {
		Integer count = jdbc.queryForObject("select count(*) from warning where member_id = ?", Integer.class,
				memberId);
		return count == null ? 0 : count;
	}

	protected int personalExemptionCount(long memberId) {
		Integer count = jdbc.queryForObject("select count(*) from personal_exemption where member_id = ?",
				Integer.class, memberId);
		return count == null ? 0 : count;
	}

	protected String personalExemptionStatus(long id) {
		return jdbc.queryForObject("select status from personal_exemption where id = ?", String.class, id);
	}

	protected int actionLogCount(long adminId) {
		Integer count = jdbc.queryForObject("select count(*) from admin_action_log where admin_id = ?",
				Integer.class, adminId);
		return count == null ? 0 : count;
	}

	// 회원들의 판정·경고·카테고리·정정 이력·개인 면제·이행 기록과 관리자 로그 수를 한 문자열로 모은다.
	// 미리보기 전후를 비교해 DB가 하나도 바뀌지 않았는지 확인하는 데 쓴다
	protected String snapshot(long... memberIds) {
		StringBuilder out = new StringBuilder();
		for (long memberId : memberIds) {
			out.append("member ").append(memberId).append('\n');
			append(out, "select id, week_start, status, skip_reason, hold_reason, retry_count, corrected, verified_days,"
					+ " record_count from weekly_judgment where member_id = ? order by week_start", memberId);
			append(out, "select id, week_start, deleted_at, delete_reason from warning where member_id = ?"
					+ " order by week_start", memberId);
			append(out, "select wt.warning_id, wt.team_id, wt.removed_at from warning_team wt join warning w"
					+ " on w.id = wt.warning_id where w.member_id = ? order by wt.warning_id, wt.team_id", memberId);
			append(out, "select jt.judgment_id, jt.team_id from judgment_team jt join weekly_judgment j"
					+ " on j.id = jt.judgment_id where j.member_id = ? order by jt.judgment_id, jt.team_id", memberId);
			append(out, "select c.id, c.judgment_id, c.after_status from judgment_correction c join weekly_judgment j"
					+ " on j.id = c.judgment_id where j.member_id = ? order by c.id", memberId);
			append(out, "select id, week_start, status, reject_reason, responded_at from personal_exemption"
					+ " where member_id = ? order by id", memberId);
			append(out, "select id, fulfilled_at from penalty_fulfillment where member_id = ? order by id", memberId);
		}
		append(out, "select week_start, reason, updated_at from exemption_period where week_start between ? and ?"
				+ " order by week_start", PERIOD_RANGE_FROM, PERIOD_RANGE_TO);
		append(out, "select count(*) from admin_action_log where admin_id = ?", adminId);
		return out.toString();
	}

	private void append(StringBuilder out, String sql, Object... args) {
		for (Map<String, Object> row : jdbc.queryForList(sql, args)) {
			out.append(row).append('\n');
		}
	}

	// ---- 동시 호출 ----

	// 모든 작업이 준비된 뒤 한꺼번에 시작시키고 결과를 입력 순서로 돌려준다
	@SafeVarargs
	protected final <T> List<T> runTogether(Callable<T>... tasks) {
		ExecutorService pool = Executors.newFixedThreadPool(tasks.length);
		try {
			CountDownLatch ready = new CountDownLatch(tasks.length);
			CountDownLatch go = new CountDownLatch(1);
			List<Future<T>> futures = new ArrayList<>();
			for (Callable<T> task : tasks) {
				futures.add(pool.submit(() -> {
					ready.countDown();
					go.await();
					return task.call();
				}));
			}
			ready.await(10, TimeUnit.SECONDS);
			go.countDown();
			List<T> results = new ArrayList<>();
			for (Future<T> future : futures) {
				results.add(future.get(60, TimeUnit.SECONDS));
			}
			return results;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("동시 호출 중단", e);
		} catch (ExecutionException | java.util.concurrent.TimeoutException e) {
			throw new IllegalStateException("동시 호출 실패", e);
		} finally {
			pool.shutdownNow();
		}
	}

}
