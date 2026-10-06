package com.jandilog.judgment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.jandilog.judgment.domain.JudgmentWeek;
import com.jandilog.judgment.dto.GrassDay;
import com.jandilog.judgment.repository.JudgmentHistoryRepository;
import com.jandilog.judgment.repository.JudgmentSourceRepository;
import com.jandilog.judgment.repository.JudgmentSourceRepository.MemberRef;
import com.jandilog.judgment.repository.WeeklyJudgmentRepository;
import com.jandilog.testsupport.auth.AuthIntegrationTest;

// 기동 시 미판정 주차 따라잡기의 정상 경로 (E-34). 판정 시각에 서버가 꺼져 있던 것처럼 한 주를 건너뛴 이력을 만들고,
// 그 다음 월요일 오전에 기동했다고 보고 빠진 주를 판정한다. 공유 DB의 다른 회원을 판정하지 않도록 대상 회원을 이 테스트의 회원 하나로 좁힌다.
// 날짜는 다른 테스트 이력과 겹치지 않게 먼 미래(2037년)로 잡는다
class JudgmentCatchUpIntegrationTest extends AuthIntegrationTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final LocalDate JUDGED_WEEK = JudgmentWeek.mondayOf(LocalDate.of(2037, 3, 4));
	private static final LocalDate MISSED_WEEK = JUDGED_WEEK.plusWeeks(1);
	private static final LocalDate CURRENT_WEEK = JUDGED_WEEK.plusWeeks(2);

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

	private long memberId;
	private long teamId;
	private String githubLogin;

	@BeforeEach
	void setUpScenario() {
		// 현재 주 월요일 08:00 KST: 직전 주(MISSED_WEEK)의 판정 시각(07:00)이 지났다
		clock.fixAt(CURRENT_WEEK.atTime(8, 0).atZone(KST).toInstant());
		memberId = members.active();
		githubLogin = jdbc.queryForObject("select github_login from member where id = ?", String.class, memberId);
		jdbc.update("update member set first_team_joined_at = ? where id = ?", LocalDateTime.of(2036, 1, 1, 9, 0),
				memberId);

		jdbc.update("insert into team (name, leader_id, invite_code, is_public, created_at) values (?, ?, ?, true, ?)",
				"따라잡기-" + members.tag(), memberId, "CU" + UUID.randomUUID().toString().replace("-", "")
						.substring(0, 12).toUpperCase(), LocalDateTime.of(2036, 1, 1, 9, 0));
		teamId = jdbc.queryForObject("select max(id) from team where leader_id = ?", Long.class, memberId);
		jdbc.update("insert into team_member (team_id, member_id, joined_at, joined_by) values (?, ?, ?, 'INVITE_CODE')",
				teamId, memberId, LocalDateTime.of(2036, 1, 1, 9, 0));

		// 앞선 주는 판정을 마쳤고, 그 다음 주는 서버가 꺼져 있어 판정이 없다
		jdbc.update("insert into weekly_judgment (member_id, week_start, status, retry_count, verified_days,"
				+ " record_count, corrected, judged_at) values (?, ?, 'PASS', 0, 7, 1, false, ?)", memberId,
				JUDGED_WEEK, JUDGED_WEEK.plusWeeks(1).atTime(7, 0));
		jdbc.update("insert into post_index (mongo_post_id, author_id, post_type, is_record, written_date, created_at)"
				+ " values (?, ?, 'DEVLOG', true, ?, ?)", UUID.randomUUID().toString().replace("-", "").substring(0, 24),
				memberId, MISSED_WEEK.plusDays(2), MISSED_WEEK.plusDays(2).atTime(12, 0));

		// 잔디 캐시가 비어 있으면 한 번 조회한다: 요청 구간의 모든 날을 1칸으로 돌려주는 가짜
		when(grassClient.fetchDailyContributions(anyString(), any(), any())).thenAnswer(invocation -> {
			LocalDate from = invocation.getArgument(1);
			LocalDate to = invocation.getArgument(2);
			List<GrassDay> days = new ArrayList<>();
			for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
				days.add(new GrassDay(day, 1));
			}
			return days;
		});
	}

	@AfterEach
	void cleanUpScenario() {
		jdbc.update("delete from judgment_day where judgment_id in (select id from weekly_judgment where member_id = ?)",
				memberId);
		jdbc.update("delete from judgment_team where judgment_id in (select id from weekly_judgment where member_id = ?)",
				memberId);
		jdbc.update("delete from warning_team where warning_id in (select id from warning where member_id = ?)",
				memberId);
		jdbc.update("delete from warning where member_id = ?", memberId);
		jdbc.update("delete from weekly_judgment where member_id = ?", memberId);
		jdbc.update("delete from post_index where author_id = ?", memberId);
		jdbc.update("delete from team_member where team_id = ?", teamId);
		jdbc.update("delete from team where id = ?", teamId);
		Set<String> keys = redis.keys("grass:" + memberId + ":*");
		if (keys != null) {
			redis.delete(keys);
		}
	}

	@Test
	void 판정_시각에_꺼져_있던_주차를_기동할_때_판정하고_다시_돌려도_중복되지_않는다() {
		JudgmentCatchUpService catchUp = catchUpFor(memberId, githubLogin);

		JudgmentCatchUpService.Result result = catchUp.catchUp();

		assertThat(result.judgedWeeks()).containsExactly(MISSED_WEEK);
		assertThat(result.leftWeeks()).isEmpty();
		Map<String, Object> row = jdbc.queryForMap(
				"select status, verified_days, record_count from weekly_judgment where member_id = ? and week_start = ?",
				memberId, MISSED_WEEK);
		assertThat(row.get("status")).isEqualTo("PASS");
		assertThat(((Number) row.get("verified_days")).intValue()).isEqualTo(7);
		assertThat(((Number) row.get("record_count")).intValue()).isEqualTo(1);
		assertThat(count("judgment_day d join weekly_judgment j on j.id = d.judgment_id"
				+ " where j.member_id = ? and j.week_start = ?", memberId, MISSED_WEEK)).isEqualTo(7);
		assertThat(count("judgment_team t join weekly_judgment j on j.id = t.judgment_id"
				+ " where j.member_id = ? and j.week_start = ? and t.team_id = ?", memberId, MISSED_WEEK, teamId))
				.isEqualTo(1);
		// 아직 끝나지 않은 현재 주는 판정하지 않는다
		assertThat(count("weekly_judgment where member_id = ? and week_start = ?", memberId, CURRENT_WEEK)).isZero();
		// 잔디는 기존 캐시 경로로 한 번만 조회했다
		verify(grassClient, times(1)).fetchDailyContributions(eq(githubLogin), any(), any());

		// 같은 시각에 다시 기동해도 빠진 주가 없어 아무것도 하지 않는다
		JudgmentCatchUpService.Result again = catchUp.catchUp();

		assertThat(again.judgedWeeks()).isEmpty();
		assertThat(count("weekly_judgment where member_id = ?", memberId)).isEqualTo(2);
		assertThat(historyRepository.findLatestWeekStart()).contains(MISSED_WEEK);
	}

	// 판정 대상 회원을 이 테스트의 회원 하나로 좁힌 따라잡기 서비스. 나머지는 실제 빈을 그대로 쓴다
	private JudgmentCatchUpService catchUpFor(long onlyMemberId, String login) {
		JudgmentSourceRepository onlyMember = new JudgmentSourceRepository() {
			@Override
			public List<MemberRef> findActiveMembers() {
				return List.of(new MemberRef(onlyMemberId, login));
			}
		};
		WeeklyJudgmentBatchService batch = new WeeklyJudgmentBatchService(onlyMember, judgmentRepository,
				memberJudgmentService, grassCacheService, clock);
		return new JudgmentCatchUpService(batch, historyRepository, clock);
	}

	private int count(String fromWhere, Object... args) {
		Integer count = jdbc.queryForObject("select count(*) from " + fromWhere, Integer.class, args);
		return count == null ? 0 : count;
	}

}
