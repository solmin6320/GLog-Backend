package com.jandilog.judgment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.judgment.domain.DayResult;
import com.jandilog.judgment.domain.HoldReason;
import com.jandilog.judgment.domain.JudgmentDay;
import com.jandilog.judgment.domain.JudgmentResult;
import com.jandilog.judgment.domain.JudgmentStatus;
import com.jandilog.judgment.domain.SkipReason;
import com.jandilog.judgment.domain.WeeklyJudgment;
import com.jandilog.judgment.dto.ActivityBanner;
import com.jandilog.judgment.dto.ActivityDayResponse;
import com.jandilog.judgment.dto.GrassDay;
import com.jandilog.judgment.dto.GrassSnapshot;
import com.jandilog.judgment.dto.WeeklyActivityResponse;
import com.jandilog.judgment.repository.JudgmentDayRepository;
import com.jandilog.judgment.repository.JudgmentSourceRepository;
import com.jandilog.judgment.repository.WeeklyJudgmentRepository;
import com.jandilog.member.domain.Member;
import com.jandilog.member.repository.MemberRepository;

// 내 주간 활동 조회 (CM-01 이번 주 진행, AC-01): 주차 검증, 저장된 판정값과 실시간 계산의 선택,
// 인증일·예상 통과 계산, 배너 우선순위 (E-39 → E-32 → E-36 → E-46)
class WeeklyActivityServiceTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	// 2026-10-14 수요일 12:00 KST. 이번 주 월요일은 10-12
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-14T03:00:00Z"), KST);
	private static final LocalDate THIS_WEEK = LocalDate.of(2026, 10, 12);
	private static final LocalDate LAST_WEEK = LocalDate.of(2026, 10, 5);
	private static final LocalDateTime JUDGED_AT = LocalDateTime.of(2026, 10, 12, 7, 0);
	private static final long MEMBER_ID = 7L;
	private static final long JUDGMENT_ID = 10L;

	private MemberRepository memberRepository;
	private WeeklyJudgmentRepository judgmentRepository;
	private JudgmentDayRepository dayRepository;
	private JudgmentSourceRepository sourceRepository;
	private GrassCacheService grassCacheService;
	private WeeklyActivityService service;
	private Member member;

	@BeforeEach
	void setUp() {
		memberRepository = mock(MemberRepository.class);
		judgmentRepository = mock(WeeklyJudgmentRepository.class);
		dayRepository = mock(JudgmentDayRepository.class);
		sourceRepository = mock(JudgmentSourceRepository.class);
		grassCacheService = mock(GrassCacheService.class);
		service = new WeeklyActivityService(memberRepository, judgmentRepository, dayRepository, sourceRepository,
				grassCacheService, CLOCK);

		member = Member.createPending(1000L, "octocat", "옥토캣", LocalDateTime.of(2026, 1, 1, 0, 0));
		ReflectionTestUtils.setField(member, "id", MEMBER_ID);
		when(memberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));
	}

	// from(지난 주 월요일)~오늘(10-14)을 채운 스냅샷. counts에 없는 날은 0
	private static GrassSnapshot snapshot(Map<LocalDate, Integer> counts) {
		LocalDate to = LocalDate.of(2026, 10, 14);
		List<GrassDay> days = LAST_WEEK.datesUntil(to.plusDays(1))
				.map(date -> new GrassDay(date, counts.getOrDefault(date, 0))).toList();
		return new GrassSnapshot(MEMBER_ID, LAST_WEEK, to, LocalDateTime.of(2026, 10, 14, 6, 0), days);
	}

	private void cache(GrassSnapshot snapshot) {
		when(grassCacheService.findLatest(MEMBER_ID)).thenReturn(Optional.of(snapshot));
	}

	private void records(Map<LocalDate, Integer> posts) {
		when(sourceRepository.countRecordPostsByDay(MEMBER_ID, THIS_WEEK, THIS_WEEK.plusDays(6))).thenReturn(posts);
	}

	private static List<DayResult> sevenDays(LocalDate weekStart) {
		return IntStream.range(0, 7).mapToObj(i -> new DayResult(weekStart.plusDays(i), i % 2 == 0, i == 1)).toList();
	}

	private static WeeklyJudgment judgment(LocalDate weekStart, JudgmentResult result) {
		WeeklyJudgment judgment = WeeklyJudgment.of(MEMBER_ID, weekStart, result);
		ReflectionTestUtils.setField(judgment, "id", JUDGMENT_ID);
		return judgment;
	}

	private static void assertInvalid(Runnable call) {
		assertThatThrownBy(call::run).isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).getErrorCode())
				.isEqualTo(ErrorCode.INVALID_INPUT);
	}

	// ----- 주차 검증 -----

	@Test
	void 주차를_비우면_이번_주다() {
		for (String text : new String[] {null, "", "   "}) {
			WeeklyActivityResponse response = service.weeklyActivity(MEMBER_ID, text);

			assertThat(response.weekStart()).isEqualTo("2026-10-12");
			assertThat(response.weekEnd()).isEqualTo("2026-10-18");
			assertThat(response.currentWeek()).isTrue();
			assertThat(response.requiredVerifiedDays()).isEqualTo(3);
			assertThat(response.requiredRecordCount()).isEqualTo(1);
			assertThat(response.days()).extracting(ActivityDayResponse::date).containsExactly("2026-10-12", "2026-10-13",
					"2026-10-14", "2026-10-15", "2026-10-16", "2026-10-17", "2026-10-18");
		}
	}

	@Test
	void 앞뒤_공백은_무시하고_읽는다() {
		WeeklyActivityResponse response = service.weeklyActivity(MEMBER_ID, "  2026-10-05  ");

		assertThat(response.weekStart()).isEqualTo("2026-10-05");
		assertThat(response.currentWeek()).isFalse();
	}

	@ParameterizedTest
	@ValueSource(strings = {"2026-10-14", "2026-10-19", "2026-12-28", "abc", "2026/10/12", "20261012"})
	void 월요일이_아니거나_미래이거나_형식이_틀린_주는_INVALID_INPUT이다(String text) {
		assertInvalid(() -> service.weeklyActivity(MEMBER_ID, text));
	}

	@Test
	void 회원이_없으면_NOT_FOUND다() {
		assertThatThrownBy(() -> service.weeklyActivity(99L, null)).isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
	}

	@Test
	void 팀_소속_여부를_담는다() {
		when(sourceRepository.hasCurrentTeam(MEMBER_ID)).thenReturn(true);

		assertThat(service.weeklyActivity(MEMBER_ID, null).inTeam()).isTrue();
	}

	// ----- 판정 시점에 저장한 값 -----

	@Test
	void 통과한_주는_판정_시점에_저장한_일자를_그대로_보여준다() {
		WeeklyJudgment pass = judgment(LAST_WEEK, JudgmentResult.judged(true, 4, 2, sevenDays(LAST_WEEK), JUDGED_AT));
		when(judgmentRepository.findByMemberIdAndWeekStart(MEMBER_ID, LAST_WEEK)).thenReturn(Optional.of(pass));
		when(dayRepository.findByJudgmentIdOrderByDay(JUDGMENT_ID)).thenReturn(
				sevenDays(LAST_WEEK).stream().map(day -> JudgmentDay.of(JUDGMENT_ID, day)).toList());

		WeeklyActivityResponse response = service.weeklyActivity(MEMBER_ID, "2026-10-05");

		assertThat(response.days()).hasSize(7);
		assertThat(response.days().get(0)).isEqualTo(new ActivityDayResponse("2026-10-05", true, null, false, null));
		assertThat(response.days().get(1)).isEqualTo(new ActivityDayResponse("2026-10-06", false, null, true, null));
		assertThat(response.verifiedDays()).isEqualTo(4);
		assertThat(response.recordCount()).isEqualTo(2);
		assertThat(response.grassAvailable()).isTrue();
		assertThat(response.grassFetchedAt()).isNull();
		assertThat(response.passExpected()).isNull();
		assertThat(response.banner()).isNull();
		assertThat(response.judgment().status()).isEqualTo(JudgmentStatus.PASS);
		// 저장된 값만 쓰므로 잔디 캐시도 기록글 실시간 집계도 부르지 않는다
		verifyNoInteractions(grassCacheService);
		verify(sourceRepository, never()).countRecordPostsByDay(anyLong(), org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any());
	}

	@Test
	void 저장된_일자가_7개가_아니면_실시간으로_계산한다() {
		WeeklyJudgment pass = judgment(LAST_WEEK, JudgmentResult.judged(true, 4, 2, sevenDays(LAST_WEEK), JUDGED_AT));
		when(judgmentRepository.findByMemberIdAndWeekStart(MEMBER_ID, LAST_WEEK)).thenReturn(Optional.of(pass));
		when(dayRepository.findByJudgmentIdOrderByDay(JUDGMENT_ID)).thenReturn(
				sevenDays(LAST_WEEK).stream().limit(6).map(day -> JudgmentDay.of(JUDGMENT_ID, day)).toList());

		WeeklyActivityResponse response = service.weeklyActivity(MEMBER_ID, "2026-10-05");

		// 캐시가 없으니 인증일을 알 수 없다. 판정 정보는 그대로 붙는다
		assertThat(response.verifiedDays()).isNull();
		assertThat(response.grassAvailable()).isFalse();
		assertThat(response.judgment().status()).isEqualTo(JudgmentStatus.PASS);
		verify(sourceRepository).countRecordPostsByDay(MEMBER_ID, LAST_WEEK, LAST_WEEK.plusDays(6));
	}

	@Test
	void 면제된_주는_저장값이_남아_있어도_일자를_읽지_않고_새로_계산한다() {
		JudgmentResult stale = new JudgmentResult(JudgmentStatus.EXEMPT, SkipReason.PERSONAL_EXEMPTION, null, 4, 2,
				sevenDays(LAST_WEEK), JUDGED_AT);
		when(judgmentRepository.findByMemberIdAndWeekStart(MEMBER_ID, LAST_WEEK))
				.thenReturn(Optional.of(judgment(LAST_WEEK, stale)));
		cache(snapshot(Map.of(LAST_WEEK, 5)));

		WeeklyActivityResponse response = service.weeklyActivity(MEMBER_ID, "2026-10-05");

		verify(dayRepository, never()).findByJudgmentIdOrderByDay(anyLong());
		assertThat(response.days().get(0).hasGrass()).isTrue();
		assertThat(response.days().get(0).grassCount()).isEqualTo(5);
		assertThat(response.verifiedDays()).isEqualTo(1);
		assertThat(response.judgment().status()).isEqualTo(JudgmentStatus.EXEMPT);
		assertThat(response.judgment().verifiedDays()).isNull();
	}

	@Test
	void 보류인_주도_일자를_읽지_않고_새로_계산한다() {
		when(judgmentRepository.findByMemberIdAndWeekStart(MEMBER_ID, THIS_WEEK))
				.thenReturn(Optional.of(judgment(THIS_WEEK, JudgmentResult.hold(HoldReason.API_ERROR))));
		cache(snapshot(Map.of(THIS_WEEK, 1)));

		WeeklyActivityResponse response = service.weeklyActivity(MEMBER_ID, null);

		verify(dayRepository, never()).findByJudgmentIdOrderByDay(anyLong());
		assertThat(response.judgment().status()).isEqualTo(JudgmentStatus.HOLD);
		assertThat(response.verifiedDays()).isEqualTo(1);
	}

	// ----- 실시간 계산 -----

	@Test
	void 잔디나_기록글이_있는_날이_인증일이고_기록글_수를_더한다() {
		cache(snapshot(Map.of(THIS_WEEK, 3, THIS_WEEK.plusDays(2), 1)));
		// 화요일은 잔디가 0이어도 기록글이 있어 인증일이다
		records(Map.of(THIS_WEEK.plusDays(1), 2));

		WeeklyActivityResponse response = service.weeklyActivity(MEMBER_ID, null);

		assertThat(response.verifiedDays()).isEqualTo(3);
		assertThat(response.recordCount()).isEqualTo(2);
		assertThat(response.passExpected()).isTrue();
		assertThat(response.grassAvailable()).isTrue();
		assertThat(response.grassFetchedAt()).isEqualTo("2026-10-14T06:00:00");
		assertThat(response.judgment()).isNull();
		assertThat(response.days().get(0)).isEqualTo(new ActivityDayResponse("2026-10-12", true, 3, false, 0));
		assertThat(response.days().get(1)).isEqualTo(new ActivityDayResponse("2026-10-13", false, 0, true, 2));
		assertThat(response.days().get(2)).isEqualTo(new ActivityDayResponse("2026-10-14", true, 1, false, 0));
		// 아직 오지 않은 날은 잔디를 알 수 없어 null이다
		assertThat(response.days().get(3)).isEqualTo(new ActivityDayResponse("2026-10-15", null, null, false, 0));
	}

	@Test
	void 인증일이_모자라면_예상_통과가_아니다() {
		cache(snapshot(Map.of(THIS_WEEK, 1, THIS_WEEK.plusDays(1), 1)));
		records(Map.of(THIS_WEEK, 1));

		WeeklyActivityResponse response = service.weeklyActivity(MEMBER_ID, null);

		assertThat(response.verifiedDays()).isEqualTo(2);
		assertThat(response.passExpected()).isFalse();
	}

	@Test
	void 기록글이_없으면_인증일이_충분해도_예상_통과가_아니다() {
		cache(snapshot(Map.of(THIS_WEEK, 1, THIS_WEEK.plusDays(1), 1, THIS_WEEK.plusDays(2), 1)));

		WeeklyActivityResponse response = service.weeklyActivity(MEMBER_ID, null);

		assertThat(response.verifiedDays()).isEqualTo(3);
		assertThat(response.recordCount()).isZero();
		assertThat(response.passExpected()).isFalse();
	}

	@Test
	void 잔디_캐시가_없으면_인증일과_예상_통과를_알_수_없다() {
		records(Map.of(THIS_WEEK, 1));

		WeeklyActivityResponse response = service.weeklyActivity(MEMBER_ID, null);

		assertThat(response.verifiedDays()).isNull();
		assertThat(response.passExpected()).isNull();
		assertThat(response.grassAvailable()).isFalse();
		assertThat(response.grassFetchedAt()).isNull();
		// 기록글 수는 캐시와 무관하게 실시간으로 센다
		assertThat(response.recordCount()).isEqualTo(1);
		assertThat(response.days().get(0)).isEqualTo(new ActivityDayResponse("2026-10-12", null, null, true, 1));
		assertThat(response.banner()).isEqualTo(ActivityBanner.GRASS_NOT_LOADED);
	}

	@Test
	void 캐시가_그_주_월요일_뒤에서_시작하면_없는_것으로_본다() {
		cache(snapshot(Map.of(LAST_WEEK, 3)));

		WeeklyActivityResponse response = service.weeklyActivity(MEMBER_ID, "2026-09-28");

		assertThat(response.currentWeek()).isFalse();
		assertThat(response.verifiedDays()).isNull();
		assertThat(response.grassAvailable()).isFalse();
		assertThat(response.days().get(0).hasGrass()).isNull();
	}

	@Test
	void 지난_주는_예상_통과도_배너도_없다() {
		cache(snapshot(Map.of(LAST_WEEK, 1, LAST_WEEK.plusDays(1), 1, LAST_WEEK.plusDays(2), 1)));
		when(sourceRepository.isExemptionPeriod(LAST_WEEK)).thenReturn(true);

		WeeklyActivityResponse response = service.weeklyActivity(MEMBER_ID, "2026-10-05");

		assertThat(response.verifiedDays()).isEqualTo(3);
		assertThat(response.passExpected()).isNull();
		assertThat(response.banner()).isNull();
	}

	@Test
	void 지난_주에_캐시가_없어도_배너는_없다() {
		WeeklyActivityResponse response = service.weeklyActivity(MEMBER_ID, "2026-10-05");

		assertThat(response.grassAvailable()).isFalse();
		assertThat(response.banner()).isNull();
	}

	// ----- 배너 우선순위 (이번 주) -----

	@Test
	void 잔디가_전부_0인데_기록글이_있으면_기여_비공개_배너가_가장_먼저다() {
		cache(snapshot(Map.of()));
		records(Map.of(THIS_WEEK, 1));
		// 아래 조건이 모두 참이어도 기여 비공개가 이긴다
		ReflectionTestUtils.setField(member, "firstTeamJoinedAt", LocalDateTime.of(2026, 10, 13, 10, 0));
		when(sourceRepository.isExemptionPeriod(THIS_WEEK)).thenReturn(true);

		assertThat(service.weeklyActivity(MEMBER_ID, null).banner()).isEqualTo(ActivityBanner.CONTRIBUTIONS_HIDDEN);
	}

	@Test
	void 잔디와_기록글이_모두_0이면_기여_비공개_배너가_아니다() {
		cache(snapshot(Map.of()));

		assertThat(service.weeklyActivity(MEMBER_ID, null).banner()).isNull();
	}

	@Test
	void 팀에_처음_참가한_주면_첫_주_배너이고_면제_배너보다_앞선다() {
		cache(snapshot(Map.of(THIS_WEEK, 1)));
		ReflectionTestUtils.setField(member, "firstTeamJoinedAt", LocalDateTime.of(2026, 10, 13, 10, 0));
		when(sourceRepository.isExemptionPeriod(THIS_WEEK)).thenReturn(true);

		assertThat(service.weeklyActivity(MEMBER_ID, null).banner()).isEqualTo(ActivityBanner.FIRST_WEEK);
	}

	@Test
	void 처음_참가한_주가_이번_주가_아니면_첫_주_배너가_아니다() {
		cache(snapshot(Map.of(THIS_WEEK, 1)));
		ReflectionTestUtils.setField(member, "firstTeamJoinedAt", LocalDateTime.of(2026, 10, 9, 10, 0));

		assertThat(service.weeklyActivity(MEMBER_ID, null).banner()).isNull();
	}

	@Test
	void 면제_기간이면_면제_배너다() {
		cache(snapshot(Map.of(THIS_WEEK, 1)));
		when(sourceRepository.isExemptionPeriod(THIS_WEEK)).thenReturn(true);

		assertThat(service.weeklyActivity(MEMBER_ID, null).banner()).isEqualTo(ActivityBanner.EXEMPTION_WEEK);
	}

	@Test
	void 승인된_개인_면제가_있으면_면제_배너다() {
		cache(snapshot(Map.of(THIS_WEEK, 1)));
		when(sourceRepository.hasApprovedPersonalExemption(MEMBER_ID, THIS_WEEK)).thenReturn(true);

		assertThat(service.weeklyActivity(MEMBER_ID, null).banner()).isEqualTo(ActivityBanner.EXEMPTION_WEEK);
	}

	@Test
	void 해당_없으면_배너가_없다() {
		cache(snapshot(Map.of(THIS_WEEK, 1)));

		assertThat(service.weeklyActivity(MEMBER_ID, null).banner()).isNull();
	}

	// ----- 판정 전 지난 주: 일자별 값과 합계가 같은 계산에서 나온다 (유스케이스 점검 B3) -----

	// 날짜별 잔디·기록글로 센 인증일과 응답의 verifiedDays가 같은지 본다
	private static long verifiedOf(WeeklyActivityResponse response) {
		return response.days().stream()
				.filter(day -> Boolean.TRUE.equals(day.hasGrass()) || day.hasRecord())
				.count();
	}

	@Test
	void 판정_전_지난_주는_날짜별_잔디만큼_인증일을_센다() {
		cache(snapshot(Map.of(LAST_WEEK, 2, LAST_WEEK.plusDays(2), 1, LAST_WEEK.plusDays(6), 4)));
		when(sourceRepository.countRecordPostsByDay(MEMBER_ID, LAST_WEEK, LAST_WEEK.plusDays(6)))
				.thenReturn(Map.of(LAST_WEEK.plusDays(3), 1));

		WeeklyActivityResponse response = service.weeklyActivity(MEMBER_ID, "2026-10-05");

		assertThat(response.judgment()).isNull();
		assertThat(response.days()).extracting(ActivityDayResponse::hasGrass)
				.containsExactly(true, false, true, false, false, false, true);
		assertThat(response.verifiedDays()).isEqualTo(4);
		assertThat((long) response.verifiedDays()).isEqualTo(verifiedOf(response));
		assertThat(response.recordCount()).isEqualTo(1);
	}

	@Test
	void 판정_전_지난_주가_첫_참가_주여도_인증일을_0으로_두지_않는다() {
		ReflectionTestUtils.setField(member, "firstTeamJoinedAt", LAST_WEEK.plusDays(2).atTime(10, 0));
		when(sourceRepository.hasCurrentTeam(MEMBER_ID)).thenReturn(true);
		cache(snapshot(Map.of(LAST_WEEK, 1, LAST_WEEK.plusDays(1), 1)));

		WeeklyActivityResponse response = service.weeklyActivity(MEMBER_ID, "2026-10-05");

		assertThat(response.verifiedDays()).isEqualTo(2);
		assertThat((long) response.verifiedDays()).isEqualTo(verifiedOf(response));
	}

	@Test
	void 일년치_어제_캐시로도_판정_전_지난_주의_날짜별_값과_합계가_같다() {
		LocalDate to = LocalDate.of(2026, 10, 13);
		LocalDate from = GrassCacheService.defaultFrom(to);
		List<GrassDay> days = from.datesUntil(to.plusDays(1))
				.map(date -> new GrassDay(date, date.equals(LAST_WEEK.plusDays(4)) || date.equals(LAST_WEEK.plusDays(5)) ? 3 : 0))
				.toList();
		cache(new GrassSnapshot(MEMBER_ID, from, to, LocalDateTime.of(2026, 10, 13, 6, 0), days));

		WeeklyActivityResponse response = service.weeklyActivity(MEMBER_ID, "2026-10-05");

		assertThat(response.grassAvailable()).isTrue();
		assertThat(response.verifiedDays()).isEqualTo(2);
		assertThat((long) response.verifiedDays()).isEqualTo(verifiedOf(response));
	}

}
