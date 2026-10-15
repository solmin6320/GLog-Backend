package com.jandilog.judgment.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.judgment.domain.JudgmentCalculator;
import com.jandilog.judgment.domain.JudgmentDay;
import com.jandilog.judgment.domain.JudgmentWeek;
import com.jandilog.judgment.domain.WeeklyJudgment;
import com.jandilog.judgment.dto.ActivityBanner;
import com.jandilog.judgment.dto.ActivityDayResponse;
import com.jandilog.judgment.dto.GrassDay;
import com.jandilog.judgment.dto.GrassSnapshot;
import com.jandilog.judgment.dto.JudgmentHistoryItem;
import com.jandilog.judgment.dto.JudgmentHistoryPage;
import com.jandilog.judgment.dto.WeekJudgmentResponse;
import com.jandilog.judgment.dto.WeeklyActivityResponse;
import com.jandilog.judgment.repository.JudgmentDayRepository;
import com.jandilog.judgment.repository.JudgmentSourceRepository;
import com.jandilog.judgment.repository.WeeklyJudgmentRepository;
import com.jandilog.member.domain.Member;
import com.jandilog.member.repository.MemberRepository;

// 본인 주간 활동 조회 (CM-01 이번 주 진행, AC-01). 잔디는 캐시만 읽고 GitHub를 부르지 않는다(기능명세서 5장).
// 기록글 수는 실시간(post_index), 인증일·잔디는 새벽 갱신 시점 값이다 (화면설계서 5-2-6).
// 판정이 끝난 주는 판정 시점에 저장한 judgment_day를 보여준다
@Service
public class WeeklyActivityService {

	static final int PAGE_SIZE = 20;
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	// 첫 페이지의 커서 상한. MariaDB DATE 범위 안의 먼 미래
	private static final LocalDate CURSOR_START = LocalDate.of(9999, 12, 31);

	private final MemberRepository memberRepository;
	private final WeeklyJudgmentRepository judgmentRepository;
	private final JudgmentDayRepository dayRepository;
	private final JudgmentSourceRepository sourceRepository;
	private final GrassCacheService grassCacheService;
	private final Clock clock;

	public WeeklyActivityService(MemberRepository memberRepository, WeeklyJudgmentRepository judgmentRepository,
			JudgmentDayRepository dayRepository, JudgmentSourceRepository sourceRepository,
			GrassCacheService grassCacheService, Clock clock) {
		this.memberRepository = memberRepository;
		this.judgmentRepository = judgmentRepository;
		this.dayRepository = dayRepository;
		this.sourceRepository = sourceRepository;
		this.grassCacheService = grassCacheService;
		this.clock = clock;
	}

	// weekStart: 월요일 날짜 문자열, 비우면 이번 주. 미래 주는 볼 것이 없어 INVALID_INPUT
	@Transactional(readOnly = true)
	public WeeklyActivityResponse weeklyActivity(long memberId, String weekStartText) {
		LocalDate thisWeek = JudgmentWeek.mondayOf(LocalDate.now(clock.withZone(KST)));
		LocalDate weekStart = parseWeekStart(weekStartText, thisWeek);
		LocalDate weekEnd = weekStart.plusDays(6);
		boolean currentWeek = weekStart.equals(thisWeek);

		Member member = memberRepository.findById(memberId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		WeeklyJudgment judgment = judgmentRepository.findByMemberIdAndWeekStart(memberId, weekStart).orElse(null);
		boolean inTeam = sourceRepository.hasCurrentTeam(memberId);

		// 판정 시점 값은 통과·미달 주에만 쓴다. 정정·소급 면제로 면제가 된 주는 예전 수치가 남아 있어도 새로 계산해 보여준다
		List<JudgmentDay> storedDays = judgment != null && judgment.getStatus().showsCounts()
				? dayRepository.findByJudgmentIdOrderByDay(judgment.getId())
				: List.of();
		if (storedDays.size() == 7) {
			// 판정 시점에 저장한 값. 잔디 캐시는 이미 사라졌다
			List<ActivityDayResponse> days = storedDays.stream()
					.map(d -> new ActivityDayResponse(d.getDay().toString(), d.isHasGrass(), null, d.isHasRecord(),
							null))
					.toList();
			return new WeeklyActivityResponse(weekStart.toString(), weekEnd.toString(), currentWeek, inTeam, days,
					judgment.getVerifiedDays(), judgment.getRecordCount() == null ? 0 : judgment.getRecordCount(),
					JudgmentCalculator.REQUIRED_VERIFIED_DAYS, JudgmentCalculator.REQUIRED_RECORD_COUNT, null, true,
					null, null, WeekJudgmentResponse.from(judgment));
		}

		Map<LocalDate, Integer> posts = sourceRepository.countRecordPostsByDay(memberId, weekStart, weekEnd);
		// 캐시는 지난 주 월요일부터만 덮는다. 그보다 오래된 주는 잔디를 알 수 없는 것으로 본다 (0칸으로 오해하지 않게)
		GrassSnapshot snapshot = grassCacheService.findLatest(memberId)
				.filter(s -> !s.from().isAfter(weekStart))
				.orElse(null);
		Map<LocalDate, GrassDay> grassByDay = new HashMap<>();
		if (snapshot != null) {
			snapshot.days().forEach(day -> grassByDay.put(day.date(), day));
		}

		List<ActivityDayResponse> days = new ArrayList<>(7);
		int recordCount = 0;
		int verified = 0;
		for (int i = 0; i < 7; i++) {
			LocalDate date = weekStart.plusDays(i);
			GrassDay grass = grassByDay.get(date);
			int posted = posts.getOrDefault(date, 0);
			recordCount += posted;
			if ((grass != null && grass.hasGrass()) || posted > 0) {
				verified++;
			}
			days.add(new ActivityDayResponse(date.toString(), grass == null ? null : grass.hasGrass(),
					grass == null ? null : grass.count(), posted > 0, posted));
		}
		// 잔디를 못 불러왔으면 인증일을 알 수 없다 (E-32)
		Integer verifiedDays = snapshot == null ? null : verified;
		Boolean passExpected = currentWeek && verifiedDays != null
				? verifiedDays >= JudgmentCalculator.REQUIRED_VERIFIED_DAYS
						&& recordCount >= JudgmentCalculator.REQUIRED_RECORD_COUNT
				: null;
		ActivityBanner banner = currentWeek ? banner(member, weekStart, snapshot, recordCount) : null;

		return new WeeklyActivityResponse(weekStart.toString(), weekEnd.toString(), currentWeek, inTeam, days,
				verifiedDays, recordCount, JudgmentCalculator.REQUIRED_VERIFIED_DAYS,
				JudgmentCalculator.REQUIRED_RECORD_COUNT, passExpected, snapshot != null,
				snapshot == null ? null : DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(snapshot.fetchedAt()), banner,
				judgment == null ? null : WeekJudgmentResponse.from(judgment));
	}

	// 지난 판정 목록: 최근 주부터 커서 기반 20개. 커서는 마지막 행 주차를 감싼 불투명 문자열 (Q-06)
	@Transactional(readOnly = true)
	public JudgmentHistoryPage history(long memberId, String cursor) {
		LocalDate before = decodeCursor(cursor);
		List<WeeklyJudgment> rows = judgmentRepository.findByMemberIdAndWeekStartLessThanOrderByWeekStartDesc(memberId,
				before, PageRequest.of(0, PAGE_SIZE + 1));
		boolean hasNext = rows.size() > PAGE_SIZE;
		List<WeeklyJudgment> page = hasNext ? rows.subList(0, PAGE_SIZE) : rows;
		// 제외·면제·보류 주는 인증일·기록글을 "—"(null)로 둔다. 저장된 값은 지우지 않아 통과·미달로 되돌리면 다시 보인다
		List<JudgmentHistoryItem> items = page.stream()
				.map(j -> new JudgmentHistoryItem(j.getWeekStart().toString(), j.getWeekStart().plusDays(6).toString(),
						j.getStatus(), j.getSkipReason(), j.getHoldReason(),
						j.getStatus().showsCounts() ? j.getVerifiedDays() : null,
						j.getStatus().showsCounts() ? j.getRecordCount() : null, j.isCorrected()))
				.toList();
		String nextCursor = hasNext ? encodeCursor(page.get(page.size() - 1).getWeekStart()) : null;
		return new JudgmentHistoryPage(items, nextCursor);
	}

	// 배너는 하나만: E-39 → E-32 → E-36 → E-46 순 (CM-01 ⑥)
	private ActivityBanner banner(Member member, LocalDate weekStart, GrassSnapshot snapshot, int recordCount) {
		if (snapshot != null && snapshot.totalContributions() == 0 && recordCount > 0) {
			return ActivityBanner.CONTRIBUTIONS_HIDDEN;
		}
		if (snapshot == null) {
			return ActivityBanner.GRASS_NOT_LOADED;
		}
		if (member.getFirstTeamJoinedAt() != null
				&& JudgmentWeek.mondayOf(member.getFirstTeamJoinedAt().toLocalDate()).equals(weekStart)) {
			return ActivityBanner.FIRST_WEEK;
		}
		if (sourceRepository.isExemptionPeriod(weekStart)
				|| sourceRepository.hasApprovedPersonalExemption(member.getId(), weekStart)) {
			return ActivityBanner.EXEMPTION_WEEK;
		}
		return null;
	}

	private static LocalDate parseWeekStart(String text, LocalDate thisWeek) {
		if (text == null || text.isBlank()) {
			return thisWeek;
		}
		try {
			LocalDate weekStart = LocalDate.parse(text.strip());
			if (JudgmentWeek.isMonday(weekStart) && !weekStart.isAfter(thisWeek)) {
				return weekStart;
			}
		}
		catch (DateTimeParseException e) {
			// 아래에서 같은 오류로 처리
		}
		throw new ApiException(ErrorCode.INVALID_INPUT);
	}

	static String encodeCursor(LocalDate lastWeekStart) {
		return Base64.getUrlEncoder().withoutPadding()
				.encodeToString(lastWeekStart.toString().getBytes(StandardCharsets.UTF_8));
	}

	static LocalDate decodeCursor(String cursor) {
		if (cursor == null || cursor.isEmpty()) {
			return CURSOR_START;
		}
		try {
			return LocalDate.parse(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
		}
		catch (IllegalArgumentException | DateTimeParseException e) {
			throw new ApiException(ErrorCode.INVALID_INPUT);
		}
	}

}
