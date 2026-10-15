package com.jandilog.testsupport.judgment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

import com.jandilog.judgment.domain.HoldReason;
import com.jandilog.judgment.domain.JudgmentWeek;
import com.jandilog.judgment.dto.GrassDay;
import com.jandilog.judgment.repository.JudgmentSourceRepository;
import com.jandilog.judgment.repository.JudgmentSourceRepository.MemberRef;
import com.jandilog.judgment.repository.WeeklyJudgmentRepository;
import com.jandilog.judgment.service.GrassCacheService;
import com.jandilog.judgment.service.GrassFetchException;
import com.jandilog.judgment.service.MemberJudgmentService;
import com.jandilog.judgment.service.WeeklyJudgmentBatchService;
import com.jandilog.judgment.service.WeeklyJudgmentBatchService.JudgmentSummary;
import com.jandilog.testsupport.judgment.JudgmentFixture.JudgmentRow;
import com.jandilog.warning.domain.WarningRecalcResult;
import com.jandilog.warning.service.WarningRecalculationService;

// 판정 파이프라인 시나리오 통합 테스트 공통 바탕 (기능명세서 11장 6주차 "판정 시나리오 통합 테스트").
// 스케줄러가 부르는 WeeklyJudgmentBatchService를 시계를 고정해 월요일마다 돌린다: 06:00 잔디 갱신 → 07:00 직전 주 판정.
// 공유 DB의 다른 회원을 판정하지 않도록 대상 회원을 이 테스트가 만든 사람으로 좁히고, 날짜는 먼 미래(2040년)로 잡는다.
// 경고 수·연속 통과는 저장값이 아니라 WarningRecalculationService가 매번 처음부터 계산한 값을 읽는다
public abstract class JudgmentPipelineIntegrationTest extends JudgmentIntegrationTest {

	protected static final ZoneId KST = ZoneId.of("Asia/Seoul");
	// 시나리오 0주차 월요일. 다른 테스트가 쓰는 연도(2036~2038, 2041~2049)와 겹치지 않는다
	protected static final LocalDate BASE = JudgmentWeek.mondayOf(LocalDate.of(2040, 3, 7));
	// 시나리오 주차보다 훨씬 전에 팀에 처음 참가한 회원의 참가 시각 (첫 참가 주 제외에 걸리지 않는다)
	protected static final LocalDateTime LONG_AGO = LocalDateTime.of(2035, 1, 1, 9, 0);
	private static final int RESERVED_WEEKS = 80;

	// label은 단언 실패 메시지에만 쓴다
	public record Person(long id, String label) {

		@Override
		public String toString() {
			return label;
		}

	}

	// 회원 로그인별로 일자별 잔디 수와 조회 실패를 지정하는 가짜 GitHub 응답
	public static final class GrassScript implements FakeGrassClient.Responder {

		private final Map<String, Map<LocalDate, Integer>> counts = new ConcurrentHashMap<>();
		private final Map<String, HoldReason> failures = new ConcurrentHashMap<>();

		public void put(String login, LocalDate day, int count) {
			counts.computeIfAbsent(login, key -> new ConcurrentHashMap<>()).put(day, count);
		}

		public void failFor(String login, HoldReason reason) {
			failures.put(login, reason);
		}

		public void recover(String login) {
			failures.remove(login);
		}

		// 아이디를 바꾼 회원의 새 로그인이 같은 기여 기록을 돌려주게 한다
		public void copyData(String fromLogin, String toLogin) {
			counts.put(toLogin, new ConcurrentHashMap<>(counts.getOrDefault(fromLogin, Map.of())));
		}

		@Override
		public List<GrassDay> respond(String githubLogin, LocalDate from, LocalDate to, int callNumber) {
			HoldReason failure = failures.get(githubLogin);
			if (failure != null) {
				throw new GrassFetchException(failure, "시나리오 잔디 조회 실패 " + failure);
			}
			Map<LocalDate, Integer> byDay = counts.getOrDefault(githubLogin, Map.of());
			List<GrassDay> days = new ArrayList<>();
			for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
				days.add(new GrassDay(day, byDay.getOrDefault(day, 0)));
			}
			return days;
		}

	}

	@Autowired
	protected MemberJudgmentService memberJudgmentService;
	@Autowired
	protected GrassCacheService grassCacheService;
	@Autowired
	protected WeeklyJudgmentRepository judgmentRepository;
	@Autowired
	protected WarningRecalculationService recalculation;

	protected GrassScript grass;
	// 이 테스트가 만든 회원만 판정하는 일괄 작업. 나머지 협력 객체는 실제 빈이다
	protected WeeklyJudgmentBatchService batch;

	private final List<Person> people = new CopyOnWriteArrayList<>();
	private final List<Long> extraMemberIds = new ArrayList<>();
	private final List<Long> teamIds = new ArrayList<>();

	@BeforeEach
	void setUpPipeline() {
		deleteReservedPeriods();
		grass = new GrassScript();
		fakeGrass.respondWith(grass);
		batch = newBatch();
	}

	@AfterEach
	void tearDownPipeline() {
		List<Long> members = new ArrayList<>(extraMemberIds);
		people.forEach(person -> members.add(person.id()));
		for (Long id : members) {
			jdbc.update("delete from judgment_correction where admin_id = ? or judgment_id in"
					+ " (select id from weekly_judgment where member_id = ?)", id, id);
			jdbc.update("delete from admin_action_log where admin_id = ?", id);
			jdbc.update("delete from personal_exemption where member_id = ? or requested_by = ?", id, id);
			jdbc.update("delete from post_index where author_id = ?", id);
			jdbc.update("delete from team_invitation where invitee_id = ?", id);
			jdbc.update("delete from team_member where member_id = ?", id);
			jdbc.update("delete from exemption_period where created_by = ?", id);
		}
		for (Long teamId : teamIds) {
			jdbc.update("delete from team_invitation where team_id = ?", teamId);
			jdbc.update("delete from team_member where team_id = ?", teamId);
			Set<String> keys = redis.keys("ban:" + teamId + ":*");
			if (keys != null && !keys.isEmpty()) {
				redis.delete(keys);
			}
		}
		deleteReservedPeriods();
	}

	private void deleteReservedPeriods() {
		jdbc.update("delete from exemption_period where week_start between ? and ?", BASE,
				BASE.plusWeeks(RESERVED_WEEKS));
	}

	// 대상을 people로 좁힌 일괄 작업. 판정·잔디 갱신 대상 목록만 바꾸고 나머지는 실제 빈을 쓴다
	protected WeeklyJudgmentBatchService newBatch() {
		JudgmentSourceRepository onlyPeople = new JudgmentSourceRepository() {
			@Override
			public List<MemberRef> findActiveMembers() {
				return people.stream().map(person -> new MemberRef(person.id(), fixture.loginOf(person.id()))).toList();
			}
		};
		return new WeeklyJudgmentBatchService(onlyPeople, judgmentRepository, memberJudgmentService,
				grassCacheService, clock);
	}

	// ---------- 시간 ----------

	protected static LocalDate week(int offset) {
		return BASE.plusWeeks(offset);
	}

	// week주차 월요일에서 dayOffset일 뒤 hour:minute (KST)
	protected static LocalDateTime at(int week, int dayOffset, int hour, int minute) {
		return week(week).plusDays(dayOffset).atTime(hour, minute);
	}

	protected void clockAt(LocalDateTime kst) {
		clock.fixAt(kst.atZone(KST).toInstant());
	}

	// ---------- 회원·팀 ----------

	// 판정 대상이 되는 회원. firstTeamJoinedAt이 null이면 팀에 한 번도 참가하지 않은 회원
	protected Person person(String label, LocalDateTime firstTeamJoinedAt) {
		Person person = new Person(fixture.member(firstTeamJoinedAt), label);
		people.add(person);
		return person;
	}

	// 시나리오 훨씬 전부터 주어진 팀들에 속한 회원
	protected Person settled(String label, long... teams) {
		Person person = person(label, LONG_AGO);
		for (long teamId : teams) {
			join(person, teamId, LONG_AGO);
		}
		return person;
	}

	// 판정 대상이 아닌 회원(팀장, 관리자). 끝에 함께 정리한다
	protected long outsider() {
		long id = fixture.member();
		extraMemberIds.add(id);
		return id;
	}

	protected long admin() {
		long id = outsider();
		jdbc.update("update member set role = 'ADMIN' where id = ?", id);
		return id;
	}

	protected long team(long leaderId) {
		long id = fixture.team(leaderId);
		teamIds.add(id);
		return id;
	}

	protected void join(Person person, long teamId, LocalDateTime joinedAt) {
		jdbc.update("insert into team_member (team_id, member_id, joined_at, joined_by) values (?, ?, ?, 'INVITE_CODE')",
				teamId, person.id(), joinedAt);
	}

	protected String login(Person person) {
		return fixture.loginOf(person.id());
	}

	// ---------- 주차별 활동 입력 ----------

	// 0주차부터 한 글자씩. P 통과(잔디 월~수 + 기록글 목), R 기록글만 3일(월·수·금), G 잔디 7일 기록글 없음,
	// F 잔디 월 + 기록글 화(인증일 2), - 활동 없음. 공백은 무시한다
	protected void plan(Person person, String codes) {
		String letters = codes.replace(" ", "");
		for (int week = 0; week < letters.length(); week++) {
			activity(person, week, letters.charAt(week));
		}
	}

	protected void activity(Person person, int week, char code) {
		switch (code) {
			case 'P' -> {
				grassOn(person, week, 0, 1, 2);
				recordOn(person, week, 3);
			}
			case 'R' -> recordOn(person, week, 0, 2, 4);
			case 'G' -> grassOn(person, week, 0, 1, 2, 3, 4, 5, 6);
			case 'F' -> {
				grassOn(person, week, 0);
				recordOn(person, week, 1);
			}
			case '-' -> {
			}
			default -> throw new IllegalArgumentException("알 수 없는 활동 글자: " + code);
		}
	}

	// dayIndexes: 0=월 ... 6=일
	protected void grassOn(Person person, int week, int... dayIndexes) {
		String login = login(person);
		for (int index : dayIndexes) {
			grass.put(login, week(week).plusDays(index), 1);
		}
	}

	protected void recordOn(Person person, int week, int... dayIndexes) {
		for (int index : dayIndexes) {
			recordPost(person, week(week).plusDays(index), true, null);
		}
	}

	// 글 색인 한 행. isRecord=false는 필수 항목이 빈 글, deletedAt이 있으면 삭제된 글. post_index.id를 돌려준다
	protected long recordPost(Person person, LocalDate writtenDate, boolean isRecord, LocalDateTime deletedAt) {
		String mongoId = new ObjectId().toHexString();
		jdbc.update("insert into post_index (mongo_post_id, author_id, post_type, is_record, written_date, created_at,"
				+ " deleted_at) values (?, ?, 'DEVLOG', ?, ?, ?, ?)", mongoId, person.id(), isRecord, writtenDate,
				writtenDate.atTime(12, 0), deletedAt);
		Long id = jdbc.queryForObject("select id from post_index where mongo_post_id = ?", Long.class, mongoId);
		return id == null ? 0 : id;
	}

	// ---------- 스케줄러 실행 ----------

	// judgedWeek 주를 판정하는 월요일: 06:00 잔디 갱신, 07:00 직전 주 판정 (JudgmentScheduler와 같은 순서)
	protected JudgmentSummary mondayRun(int judgedWeek) {
		clockAt(at(judgedWeek + 1, 0, 6, 0));
		batch.refreshGrass();
		clockAt(at(judgedWeek + 1, 0, 7, 0));
		return batch.judgeLastWeek();
	}

	// fromJudged~toJudged(포함) 주를 각각의 월요일에 판정한다
	protected void mondayRuns(int fromJudged, int toJudged) {
		for (int week = fromJudged; week <= toJudged; week++) {
			mondayRun(week);
		}
	}

	// judgedWeek 판정 월요일 07:minute에 보류 자동 재시도 한 번 (10분 간격 틱)
	protected void retryTick(int judgedWeek, int minute) {
		clockAt(at(judgedWeek + 1, 0, 7, minute));
		batch.retryHolds();
	}

	// ---------- 읽기·단언 ----------

	protected JudgmentRow row(Person person, int week) {
		return fixture.findJudgment(person.id(), week(week))
				.orElseThrow(() -> new AssertionError(person + " " + week + "주차 판정 행이 없어요"));
	}

	protected Optional<JudgmentRow> rowIfPresent(Person person, int week) {
		return fixture.findJudgment(person.id(), week(week));
	}

	protected void assertStatus(Person person, int week, String status) {
		assertThat(row(person, week).status()).as("%s %d주차 판정", person, week).isEqualTo(status);
	}

	// 지금 시점에 처음부터 다시 계산한 값. 보류가 남아 있으면 실패한다
	protected WarningRecalcResult.Calculated state(Person person) {
		WarningRecalcResult result = recalculation.calculate(person.id());
		assertThat(result).as("%s 재계산 결과", person).isInstanceOf(WarningRecalcResult.Calculated.class);
		return (WarningRecalcResult.Calculated) result;
	}

	protected void assertState(String when, Person person, int warnings, int streak) {
		WarningRecalcResult.Calculated state = state(person);
		assertThat(state.warningCount()).as("%s %s 경고 수", person, when).isEqualTo(warnings);
		assertThat(state.streak()).as("%s %s 연속 통과", person, when).isEqualTo(streak);
		assertThat(state.penaltyTarget()).as("%s %s 벌칙 대상", person, when).isEqualTo(warnings >= 3);
	}

	protected WarningRecalcResult.OnHold onHold(Person person) {
		WarningRecalcResult result = recalculation.calculate(person.id());
		assertThat(result).as("%s 재계산 결과", person).isInstanceOf(WarningRecalcResult.OnHold.class);
		return (WarningRecalcResult.OnHold) result;
	}

	// 삭제 표시되지 않은 경고의 주차 (월요일 LocalDate)
	protected List<LocalDate> aliveWarningWeeks(Person person) {
		return fixture.warnings(person.id()).stream().filter(warning -> warning.deletedAt() == null)
				.map(JudgmentFixture.WarningRow::weekStart).toList();
	}

	protected List<LocalDate> warningWeeks(Person person) {
		return fixture.warnings(person.id()).stream().map(JudgmentFixture.WarningRow::weekStart).toList();
	}

	protected JudgmentFixture.WarningRow warningOf(Person person, int week) {
		return fixture.warnings(person.id()).stream().filter(warning -> warning.weekStart().equals(week(week)))
				.findFirst().orElseThrow(() -> new AssertionError(person + " " + week + "주차 경고가 없어요"));
	}

	// 이 회원에 대해 가짜 GitHub가 불린 횟수
	protected long callsOf(Person person) {
		String login = login(person);
		return fakeGrass.calls().stream().filter(call -> call.githubLogin().equals(login)).count();
	}

	// 회원들의 판정·일자 근거·소속 스냅샷·경고·카테고리·개인 면제·이행 기록을 한 문자열로 모은다.
	// 재실행·미리보기 전후를 비교해 DB가 하나도 바뀌지 않았는지 확인하는 데 쓴다
	protected String fingerprint(Person... persons) {
		StringBuilder out = new StringBuilder();
		for (Person person : persons) {
			out.append("member ").append(person).append('\n');
			append(out, "select id, week_start, status, skip_reason, hold_reason, retry_count, verified_days,"
					+ " record_count, corrected, judged_at from weekly_judgment where member_id = ? order by week_start",
					person.id());
			append(out, "select d.judgment_id, d.day, d.has_grass, d.has_record from judgment_day d join weekly_judgment j"
					+ " on j.id = d.judgment_id where j.member_id = ? order by d.judgment_id, d.day", person.id());
			append(out, "select jt.judgment_id, jt.team_id from judgment_team jt join weekly_judgment j"
					+ " on j.id = jt.judgment_id where j.member_id = ? order by jt.judgment_id, jt.team_id", person.id());
			append(out, "select id, week_start, deleted_at, delete_reason, created_at from warning where member_id = ?"
					+ " order by week_start", person.id());
			append(out, "select wt.warning_id, wt.team_id, wt.removed_at from warning_team wt join warning w"
					+ " on w.id = wt.warning_id where w.member_id = ? order by wt.warning_id, wt.team_id", person.id());
			append(out, "select id, week_start, status, responded_at from personal_exemption where member_id = ?"
					+ " order by id", person.id());
			append(out, "select id, fulfilled_at, reached_week from penalty_fulfillment where member_id = ? order by id",
					person.id());
			append(out, "select c.id, c.judgment_id, c.after_status from judgment_correction c join weekly_judgment j"
					+ " on j.id = c.judgment_id where j.member_id = ? order by c.id", person.id());
		}
		append(out, "select week_start, reason from exemption_period where week_start between ? and ?"
				+ " order by week_start", BASE, BASE.plusWeeks(RESERVED_WEEKS));
		return out.toString();
	}

	private void append(StringBuilder out, String sql, Object... args) {
		for (Map<String, Object> row : jdbc.queryForList(sql, args)) {
			out.append(row).append('\n');
		}
	}

}
