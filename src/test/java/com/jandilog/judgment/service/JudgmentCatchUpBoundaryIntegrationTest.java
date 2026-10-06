package com.jandilog.judgment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.jandilog.judgment.domain.HoldReason;
import com.jandilog.judgment.domain.JudgmentWeek;
import com.jandilog.judgment.dto.GrassDay;
import com.jandilog.judgment.repository.JudgmentHistoryRepository;
import com.jandilog.judgment.repository.JudgmentSourceRepository;
import com.jandilog.judgment.repository.JudgmentSourceRepository.MemberRef;
import com.jandilog.judgment.repository.WeeklyJudgmentRepository;
import com.jandilog.judgment.service.JudgmentCatchUpService.Result;
import com.jandilog.testsupport.auth.AuthIntegrationTest;

// 기동 시 미판정 주차 따라잡기의 경계·예외 통합 (E-34). 정상 경로는 JudgmentCatchUpIntegrationTest가 본다.
// 공유 DB의 다른 회원을 판정하지 않도록 대상 회원을 이 테스트가 만든 회원으로 좁히고, 가장 최근 판정 주차가 전역 최댓값이라
// 다른 테스트 이력과 겹치지 않게 먼 미래(2038년)로 날짜를 잡는다.
// "한 번에 최대 N주"는 구현 기본값이라 값을 적지 않고 JudgmentCatchUpService.MAX_CATCH_UP_WEEKS를 참조한다 (한도_ 메서드)
class JudgmentCatchUpBoundaryIntegrationTest extends AuthIntegrationTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final int MAX = JudgmentCatchUpService.MAX_CATCH_UP_WEEKS;
	private static final LocalDate BASE = JudgmentWeek.mondayOf(LocalDate.of(2038, 3, 3));
	private static final LocalDateTime OLD_TEAM_JOINED = LocalDateTime.of(2036, 1, 1, 9, 0);

	// 요청 구간의 모든 날을 잔디 1칸으로 돌려주는 가짜 GitHub
	private static final Answer<List<GrassDay>> EVERY_DAY_GRASS = invocation -> {
		LocalDate from = invocation.getArgument(1);
		LocalDate to = invocation.getArgument(2);
		List<GrassDay> days = new ArrayList<>();
		for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
			days.add(new GrassDay(day, 1));
		}
		return days;
	};

	private record Person(long id, String login, Long teamId) {
	}

	@MockitoBean
	private GrassClient grassClient;
	@Autowired
	private MemberJudgmentService memberJudgmentService;
	@Autowired
	private GrassCacheService grassCacheService;
	@Autowired
	private WeeklyJudgmentRepository judgmentRepository;
	@Autowired
	private JudgmentHistoryRepository historyRepository;
	@Autowired
	private ApplicationContext applicationContext;

	private final List<Person> people = new ArrayList<>();
	private final List<LocalDate> exemptionWeeks = new ArrayList<>();

	@BeforeEach
	void setUpFakeGrass() {
		doAnswer(EVERY_DAY_GRASS).when(grassClient).fetchDailyContributions(anyString(), any(), any());
	}

	@AfterEach
	void cleanUpScenario() {
		for (Person person : people) {
			long id = person.id();
			jdbc.update("delete from judgment_day where judgment_id in (select id from weekly_judgment where member_id = ?)",
					id);
			jdbc.update("delete from judgment_team where judgment_id in (select id from weekly_judgment where member_id = ?)",
					id);
			jdbc.update("delete from warning_team where warning_id in (select id from warning where member_id = ?)", id);
			jdbc.update("delete from warning where member_id = ?", id);
			jdbc.update("delete from weekly_judgment where member_id = ?", id);
			jdbc.update("delete from post_index where author_id = ?", id);
			jdbc.update("delete from personal_exemption where member_id = ?", id);
			if (person.teamId() != null) {
				jdbc.update("delete from team_member where team_id = ?", person.teamId());
				jdbc.update("delete from team where id = ?", person.teamId());
			}
			Set<String> keys = redis.keys("grass:" + id + ":*");
			if (keys != null && !keys.isEmpty()) {
				redis.delete(keys);
			}
		}
		for (LocalDate week : exemptionWeeks) {
			jdbc.update("delete from exemption_period where week_start = ?", week);
		}
	}

	// ---- 첫 기동 ----

	@Test
	void 판정_이력이_하나도_없는_첫_기동은_회원과_팀이_있어도_판정하지_않고_GitHub도_부르지_않는다() {
		Person member = person(OLD_TEAM_JOINED);
		post(member, week(1).plusDays(2));
		clockAt(week(3), 8, 0, 0);
		JudgmentCatchUpService service = catchUpFor(historyReturning(Optional.empty()), member);

		Result result = service.catchUp();

		assertThat(result.judgedWeeks()).isEmpty();
		assertThat(result.leftWeeks()).isEmpty();
		assertThat(count("weekly_judgment where member_id = ?", member.id())).isZero();
		verifyNoInteractions(grassClient);
	}

	@Test
	void test_프로필_컨텍스트에는_따라잡기_리스너가_등록되지_않는다() {
		assertThat(applicationContext.getBeanNamesForType(JudgmentCatchUpListener.class)).isEmpty();
		assertThat(applicationContext.getBeanNamesForType(JudgmentCatchUpService.class)).hasSize(1);
	}

	// ---- 판정 시각과 진행 중인 주 ----

	@Test
	void 판정_시각_1초_전에는_직전_주를_판정하지_않고_07시_정각에_판정한다() {
		Person member = person(OLD_TEAM_JOINED);
		storedJudgment(member, week(0), "PASS", null, 5, 0);
		post(member, week(1).plusDays(2));
		JudgmentCatchUpService service = catchUpFor(historyRepository, member);

		clockAt(week(2), 6, 59, 59);
		Result before = service.catchUp();

		assertThat(before.judgedWeeks()).isEmpty();
		assertThat(row(member, week(1))).isNull();

		clockAt(week(2), 7, 0, 0);
		Result at = service.catchUp();

		assertThat(at.judgedWeeks()).containsExactly(week(1));
		assertThat(row(member, week(1))).containsEntry("status", "PASS");
	}

	@Test
	void 이번_주는_일요일_밤까지_판정하지_않고_다음_월요일_07시에_판정한다() {
		Person member = person(OLD_TEAM_JOINED);
		storedJudgment(member, week(0), "PASS", null, 5, 0);
		post(member, week(1).plusDays(2));
		post(member, week(2).plusDays(2));
		JudgmentCatchUpService service = catchUpFor(historyRepository, member);

		// 진행 중인 week(2)의 마지막 순간
		clockAt(week(2).plusDays(6), 23, 59, 59);
		Result sundayNight = service.catchUp();

		assertThat(sundayNight.judgedWeeks()).containsExactly(week(1));
		assertThat(row(member, week(2))).isNull();

		clockAt(week(3), 6, 59, 59);
		assertThat(service.catchUp().judgedWeeks()).isEmpty();
		assertThat(row(member, week(2))).isNull();

		clockAt(week(3), 7, 0, 0);
		assertThat(service.catchUp().judgedWeeks()).containsExactly(week(2));
		assertThat(row(member, week(2))).containsEntry("status", "PASS");
	}

	// ---- 이미 판정된 주 ----

	@Test
	void 가장_최근에_확정된_주는_다시_판정하지_않고_그_뒤_주만_판정한다() {
		Person member = person(OLD_TEAM_JOINED);
		storedJudgment(member, week(0), "PASS", null, 5, 0);
		// 확정된 week(1)은 저장값(인증일 5)이 그대로 남아야 한다. 새로 계산하면 인증일 7이 된다
		storedJudgment(member, week(1), "PASS", null, 5, 0);
		post(member, week(1).plusDays(2));
		post(member, week(2).plusDays(2));
		clockAt(week(3), 8, 0, 0);

		List<LocalDate> judged = catchUpAll(catchUpFor(historyRepository, member));

		assertThat(judged).containsExactly(week(2));
		assertThat(row(member, week(1)).get("verified_days")).isEqualTo(5);
		assertThat(count("weekly_judgment where member_id = ? and week_start = ?", member.id(), week(1))).isEqualTo(1);
		assertThat(count("judgment_day d join weekly_judgment j on j.id = d.judgment_id"
				+ " where j.member_id = ? and j.week_start = ?", member.id(), week(1))).isZero();
		assertThat(row(member, week(2))).containsEntry("status", "PASS");
		verify(grassClient, times(1)).fetchDailyContributions(eq(member.login()), eq(week(2)), any());
	}

	@Test
	void 보류_주는_따라잡기가_다시_돌리지_않고_그_뒤_주만_판정한다() {
		Person member = person(OLD_TEAM_JOINED);
		storedJudgment(member, week(0), "PASS", null, 5, 0);
		// 보류의 재시도는 보류 자동 재시도 작업 몫이라 따라잡기는 건드리지 않는다
		jdbc.update("insert into weekly_judgment (member_id, week_start, status, hold_reason, retry_count, corrected)"
				+ " values (?, ?, 'HOLD', 'API_ERROR', 1, false)", member.id(), week(1));
		post(member, week(2).plusDays(2));
		clockAt(week(3), 8, 0, 0);

		List<LocalDate> judged = catchUpAll(catchUpFor(historyRepository, member));

		assertThat(judged).containsExactly(week(2));
		Map<String, Object> hold = row(member, week(1));
		assertThat(hold).containsEntry("status", "HOLD");
		assertThat(((Number) hold.get("retry_count")).intValue()).isEqualTo(1);
		assertThat(count("weekly_judgment where member_id = ? and week_start = ?", member.id(), week(1))).isEqualTo(1);
		assertThat(row(member, week(2))).containsEntry("status", "PASS");
	}

	// ---- 여러 주 ----

	@Test
	void 여러_주가_밀리면_오래된_주부터_판정하고_잔디는_가장_오래된_주부터_덮는_구간으로_한_번만_조회한다() {
		int missed = Math.min(3, MAX);
		Person member = person(OLD_TEAM_JOINED);
		storedJudgment(member, week(0), "PASS", null, 5, 0);
		for (int i = 1; i <= missed; i++) {
			post(member, week(i).plusDays(2));
		}
		clockAt(week(missed + 1), 8, 0, 0);

		Result result = catchUpFor(historyRepository, member).catchUp();

		List<LocalDate> expected = new ArrayList<>();
		for (int i = 1; i <= missed; i++) {
			expected.add(week(i));
		}
		assertThat(result.judgedWeeks()).containsExactlyElementsOf(expected);
		assertThat(result.leftWeeks()).isEmpty();
		// 저장 순서(auto increment)가 주차 오름차순이라 오래된 주부터 판정했다
		List<LocalDate> savedOrder = jdbc.queryForList(
				"select week_start from weekly_judgment where member_id = ? order by id", LocalDate.class, member.id());
		assertThat(savedOrder).startsWith(week(0)).containsSequence(expected);
		for (LocalDate week : expected) {
			assertThat(row(member, week)).containsEntry("status", "PASS");
		}
		verify(grassClient, times(1)).fetchDailyContributions(eq(member.login()), any(), any());
		verify(grassClient).fetchDailyContributions(eq(member.login()), eq(week(1)), any());
	}

	@Test
	void 한도_한도를_넘게_밀리면_한도만큼만_판정하고_다음_기동에서_남은_주를_이어서_판정한다() {
		int missed = MAX + 2;
		Person member = person(OLD_TEAM_JOINED);
		storedJudgment(member, week(0), "PASS", null, 5, 0);
		for (int i = 1; i <= missed; i++) {
			post(member, week(i).plusDays(2));
		}
		clockAt(week(missed + 1), 8, 0, 0);
		JudgmentCatchUpService service = catchUpFor(historyRepository, member);

		Result first = service.catchUp();

		assertThat(first.judgedWeeks()).hasSize(MAX).first().isEqualTo(week(1));
		assertThat(first.leftWeeks()).containsExactly(week(MAX + 1), week(MAX + 2));
		assertThat(count("weekly_judgment where member_id = ?", member.id())).isEqualTo(1 + MAX);
		assertThat(row(member, week(MAX))).containsEntry("status", "PASS");
		assertThat(row(member, week(MAX + 1))).isNull();
		assertThat(row(member, week(MAX + 2))).isNull();

		Result second = service.catchUp();

		assertThat(second.judgedWeeks()).containsExactly(week(MAX + 1), week(MAX + 2));
		assertThat(second.leftWeeks()).isEmpty();
		assertThat(count("weekly_judgment where member_id = ?", member.id())).isEqualTo(1 + missed);
		assertThat(row(member, week(MAX + 2))).containsEntry("status", "PASS");

		// 모두 끝난 뒤의 기동은 아무것도 하지 않는다
		assertThat(service.catchUp().judgedWeeks()).isEmpty();
		assertThat(count("weekly_judgment where member_id = ?", member.id())).isEqualTo(1 + missed);
	}

	// ---- 제외·면제 ----

	@Test
	void 팀이_없던_주와_팀_가입_전_주와_첫_참가_주는_경고_없이_제외로_저장하고_잔디를_조회하지_않는다() {
		Person settled = person(OLD_TEAM_JOINED);
		Person noTeam = person(null);
		// week(2) 수요일에 처음 팀에 들어간 회원: week(1)은 가입 전, week(2)는 첫 참가 주
		Person newcomer = person(week(2).plusDays(2).atTime(12, 0));
		storedJudgment(settled, week(0), "PASS", null, 5, 0);
		post(settled, week(1).plusDays(2));
		post(settled, week(2).plusDays(2));
		clockAt(week(3), 8, 0, 0);

		catchUpAll(catchUpFor(historyRepository, settled, noTeam, newcomer));

		assertThat(row(settled, week(1))).containsEntry("status", "PASS");
		assertThat(row(settled, week(2))).containsEntry("status", "PASS");
		for (LocalDate week : new LocalDate[] {week(1), week(2)}) {
			assertThat(row(noTeam, week)).containsEntry("status", "EXCLUDED").containsEntry("skip_reason", "NO_TEAM");
		}
		assertThat(row(newcomer, week(1))).containsEntry("status", "EXCLUDED").containsEntry("skip_reason", "NO_TEAM");
		assertThat(row(newcomer, week(2))).containsEntry("status", "EXCLUDED").containsEntry("skip_reason",
				"FIRST_WEEK");
		for (Person excluded : new Person[] {noTeam, newcomer}) {
			assertThat(count("warning where member_id = ?", excluded.id())).as("경고").isZero();
			assertThat(count("judgment_day d join weekly_judgment j on j.id = d.judgment_id where j.member_id = ?",
					excluded.id())).as("일자 근거").isZero();
			verify(grassClient, never()).fetchDailyContributions(eq(excluded.login()), any(), any());
		}
		assertThat(count("warning where member_id = ?", settled.id())).isZero();
	}

	@Test
	void 면제_기간과_승인된_개인_면제_주는_잔디를_조회하지_않고_면제로_저장하며_미승인_신청은_면제가_아니다() {
		Person member = person(OLD_TEAM_JOINED);
		long admin = members.admin();
		storedJudgment(member, week(0), "PASS", null, 5, 0);
		jdbc.update("insert into exemption_period (week_start, reason, created_by, created_at) values (?, ?, ?, ?)",
				week(1), "전체 면제 시험", admin, LocalDateTime.of(2038, 1, 1, 9, 0));
		exemptionWeeks.add(week(1));
		personalExemption(member, week(2), "APPROVED");
		// 대기 중인 신청은 면제가 아니라 일반 판정(기록글 없음 → 미달)이다
		personalExemption(member, week(3), "PENDING");
		clockAt(week(4), 8, 0, 0);

		catchUpAll(catchUpFor(historyRepository, member));

		assertThat(row(member, week(1))).containsEntry("status", "EXEMPT").containsEntry("skip_reason",
				"EXEMPTION_PERIOD");
		assertThat(row(member, week(2))).containsEntry("status", "EXEMPT").containsEntry("skip_reason",
				"PERSONAL_EXEMPTION");
		assertThat(row(member, week(3))).containsEntry("status", "FAIL");
		// 면제 주에는 일자 근거도 경고도 없고, 미달 주에만 경고 1개가 있다
		assertThat(count("judgment_day d join weekly_judgment j on j.id = d.judgment_id"
				+ " where j.member_id = ? and j.week_start in (?, ?)", member.id(), week(1), week(2))).isZero();
		assertThat(count("warning where member_id = ?", member.id())).isEqualTo(1);
		assertThat(count("warning where member_id = ? and week_start = ?", member.id(), week(3))).isEqualTo(1);
		// 면제 두 주는 잔디를 부르지 않아 미달인 week(3)에서만 한 번 조회한다
		verify(grassClient, times(1)).fetchDailyContributions(eq(member.login()), any(), any());
		verify(grassClient).fetchDailyContributions(eq(member.login()), eq(week(3)), any());
	}

	// ---- 중간 실패 ----

	@Test
	void 중간_주의_잔디_조회가_실패하면_그_주만_보류하고_경고_없이_다음_주를_계속_판정한다() {
		Person member = person(OLD_TEAM_JOINED);
		storedJudgment(member, week(0), "PASS", null, 5, 0);
		post(member, week(1).plusDays(2));
		post(member, week(2).plusDays(2));
		clockAt(week(3), 8, 0, 0);
		// 첫 조회(week(1))만 GitHub 오류, 이후 조회는 성공
		doAnswer(invocation -> {
			throw new GrassFetchException(HoldReason.API_ERROR, "GitHub 오류");
		}).doAnswer(EVERY_DAY_GRASS).when(grassClient).fetchDailyContributions(anyString(), any(), any());
		JudgmentCatchUpService service = catchUpFor(historyRepository, member);

		Result result = service.catchUp();

		assertThat(result.judgedWeeks()).containsExactly(week(1), week(2));
		Map<String, Object> hold = row(member, week(1));
		assertThat(hold).containsEntry("status", "HOLD").containsEntry("hold_reason", "API_ERROR");
		assertThat(((Number) hold.get("retry_count")).intValue()).isZero();
		assertThat(count("judgment_day d join weekly_judgment j on j.id = d.judgment_id"
				+ " where j.member_id = ? and j.week_start = ?", member.id(), week(1))).isZero();
		assertThat(row(member, week(2))).containsEntry("status", "PASS");
		assertThat(count("warning where member_id = ?", member.id())).isZero();
		verify(grassClient, times(2)).fetchDailyContributions(eq(member.login()), any(), any());

		// 다시 기동해도 보류 주를 따라잡기가 되풀이하지 않는다(보류 재시도는 별도 작업)
		assertThat(service.catchUp().judgedWeeks()).isEmpty();
		assertThat(count("weekly_judgment where member_id = ? and week_start = ?", member.id(), week(1))).isEqualTo(1);
		assertThat(row(member, week(1))).containsEntry("status", "HOLD");
	}

	// ---- 동시 기동 ----

	@Test
	void 두_인스턴스가_동시에_기동해도_판정_일자_근거_소속_경고가_중복되지_않는다() throws Exception {
		Person passer = person(OLD_TEAM_JOINED);
		// 기록글이 없어 두 주 모두 미달: 경고가 주마다 하나씩이어야 한다
		Person failer = person(OLD_TEAM_JOINED);
		for (Person member : List.of(passer, failer)) {
			storedJudgment(member, week(0), "PASS", null, 5, 0);
		}
		post(passer, week(1).plusDays(2));
		post(passer, week(2).plusDays(2));
		clockAt(week(3), 8, 0, 0);
		JudgmentCatchUpService instanceA = catchUpFor(historyRepository, passer, failer);
		JudgmentCatchUpService instanceB = catchUpFor(historyRepository, passer, failer);

		CyclicBarrier start = new CyclicBarrier(2);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<List<LocalDate>> a = pool.submit(() -> {
				start.await(30, TimeUnit.SECONDS);
				return catchUpAll(instanceA);
			});
			Future<List<LocalDate>> b = pool.submit(() -> {
				start.await(30, TimeUnit.SECONDS);
				return catchUpAll(instanceB);
			});
			// 어느 인스턴스도 예외로 끝나지 않는다
			assertThat(a.get(120, TimeUnit.SECONDS)).isNotNull();
			assertThat(b.get(120, TimeUnit.SECONDS)).isNotNull();
		}
		finally {
			pool.shutdownNow();
		}

		for (Person member : List.of(passer, failer)) {
			// week(0) 저장 행 + 새로 판정한 두 주, 주마다 정확히 한 행
			assertThat(count("weekly_judgment where member_id = ?", member.id())).isEqualTo(3);
			for (int i = 1; i <= 2; i++) {
				assertThat(count("weekly_judgment where member_id = ? and week_start = ?", member.id(), week(i)))
						.isEqualTo(1);
			}
			assertThat(count("judgment_day d join weekly_judgment j on j.id = d.judgment_id"
					+ " where j.member_id = ? and j.week_start in (?, ?)", member.id(), week(1), week(2))).isEqualTo(14);
			assertThat(count("judgment_team t join weekly_judgment j on j.id = t.judgment_id"
					+ " where j.member_id = ? and j.week_start in (?, ?)", member.id(), week(1), week(2))).isEqualTo(2);
			// 잔디는 회원당 한 번만 조회한다
			verify(grassClient, times(1)).fetchDailyContributions(eq(member.login()), any(), any());
		}
		assertThat(row(passer, week(1))).containsEntry("status", "PASS");
		assertThat(row(passer, week(2))).containsEntry("status", "PASS");
		assertThat(row(failer, week(1))).containsEntry("status", "FAIL");
		assertThat(row(failer, week(2))).containsEntry("status", "FAIL");
		assertThat(count("warning where member_id = ?", passer.id())).isZero();
		assertThat(count("warning where member_id = ?", failer.id())).isEqualTo(2);
		assertThat(count("warning_team t join warning w on w.id = t.warning_id where w.member_id = ?", failer.id()))
				.isEqualTo(2);
	}

	// ---- 도우미 ----

	private static LocalDate week(int offset) {
		return BASE.plusWeeks(offset);
	}

	private void clockAt(LocalDate date, int hour, int minute, int second) {
		clock.fixAt(date.atTime(hour, minute, second).atZone(KST).toInstant());
	}

	// teamJoinedAt이 null이면 어느 팀에도 속하지 않은 회원
	private Person person(LocalDateTime teamJoinedAt) {
		long id = members.active();
		String login = jdbc.queryForObject("select github_login from member where id = ?", String.class, id);
		Long teamId = null;
		if (teamJoinedAt != null) {
			jdbc.update("update member set first_team_joined_at = ? where id = ?", teamJoinedAt, id);
			jdbc.update("insert into team (name, leader_id, invite_code, is_public, created_at) values (?, ?, ?, true, ?)",
					"경계-" + members.tag(), id, "CB" + UUID.randomUUID().toString().replace("-", "").substring(0, 12)
							.toUpperCase(), teamJoinedAt);
			teamId = jdbc.queryForObject("select max(id) from team where leader_id = ?", Long.class, id);
			jdbc.update("insert into team_member (team_id, member_id, joined_at, joined_by) values (?, ?, ?, 'INVITE_CODE')",
					teamId, id, teamJoinedAt);
		}
		Person person = new Person(id, login, teamId);
		people.add(person);
		return person;
	}

	// 이미 저장된 확정 판정 한 행. 따라잡기 대상의 기준(가장 최근 판정 주차)을 만든다
	private void storedJudgment(Person person, LocalDate week, String status, String skipReason, int verifiedDays,
			int recordCount) {
		jdbc.update("insert into weekly_judgment (member_id, week_start, status, skip_reason, retry_count, verified_days,"
				+ " record_count, corrected, judged_at) values (?, ?, ?, ?, 0, ?, ?, false, ?)", person.id(), week, status,
				skipReason, verifiedDays, recordCount, week.plusWeeks(1).atTime(7, 0));
	}

	private void post(Person person, LocalDate writtenDate) {
		jdbc.update("insert into post_index (mongo_post_id, author_id, post_type, is_record, written_date, created_at)"
				+ " values (?, ?, 'DEVLOG', true, ?, ?)", UUID.randomUUID().toString().replace("-", "").substring(0, 24),
				person.id(), writtenDate, writtenDate.atTime(12, 0));
	}

	private void personalExemption(Person person, LocalDate week, String status) {
		jdbc.update("insert into personal_exemption (member_id, requested_by, week_start, status, reason, created_at)"
				+ " values (?, ?, ?, ?, '개인 면제 시험', ?)", person.id(), person.id(), week, status,
				LocalDateTime.of(2038, 1, 1, 9, 0));
	}

	private Map<String, Object> row(Person person, LocalDate week) {
		List<Map<String, Object>> rows = jdbc.queryForList(
				"select id, status, skip_reason, hold_reason, verified_days, record_count, retry_count"
						+ " from weekly_judgment where member_id = ? and week_start = ?", person.id(), week);
		return rows.isEmpty() ? null : rows.get(0);
	}

	// 대상 회원을 넘겨 준 사람들로 좁힌 따라잡기 서비스. 나머지는 실제 빈을 그대로 쓴다
	private JudgmentCatchUpService catchUpFor(JudgmentHistoryRepository history, Person... targets) {
		List<MemberRef> refs = Arrays.stream(targets).map(p -> new MemberRef(p.id(), p.login())).toList();
		JudgmentSourceRepository onlyTargets = new JudgmentSourceRepository() {
			@Override
			public List<MemberRef> findActiveMembers() {
				return refs;
			}
		};
		WeeklyJudgmentBatchService batch = new WeeklyJudgmentBatchService(onlyTargets, judgmentRepository,
				memberJudgmentService, grassCacheService, clock);
		return new JudgmentCatchUpService(batch, history, clock);
	}

	private static JudgmentHistoryRepository historyReturning(Optional<LocalDate> latest) {
		return () -> latest;
	}

	// 남은 주가 없을 때까지 기동을 되풀이해 판정한 주차를 모은다
	private static List<LocalDate> catchUpAll(JudgmentCatchUpService service) {
		List<LocalDate> judged = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			Result result = service.catchUp();
			judged.addAll(result.judgedWeeks());
			if (result.leftWeeks().isEmpty()) {
				return judged;
			}
		}
		throw new AssertionError("따라잡기가 끝나지 않아요");
	}

	private int count(String fromWhere, Object... args) {
		Integer count = jdbc.queryForObject("select count(*) from " + fromWhere, Integer.class, args);
		return count == null ? 0 : count;
	}

}
