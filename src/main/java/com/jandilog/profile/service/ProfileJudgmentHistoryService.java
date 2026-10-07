package com.jandilog.profile.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.common.pagination.CursorCodec;
import com.jandilog.judgment.domain.JudgmentCalculator;
import com.jandilog.judgment.domain.JudgmentInput;
import com.jandilog.judgment.domain.JudgmentStatus;
import com.jandilog.judgment.domain.JudgmentWeek;
import com.jandilog.judgment.domain.SkipReason;
import com.jandilog.judgment.domain.WeeklyJudgment;
import com.jandilog.judgment.dto.WeeklyActivityResponse;
import com.jandilog.judgment.repository.JudgmentSourceRepository;
import com.jandilog.judgment.service.WeeklyActivityService;
import com.jandilog.member.domain.Member;
import com.jandilog.member.repository.MemberRepository;
import com.jandilog.profile.dto.ProfileJudgmentPage;
import com.jandilog.profile.dto.ProfileJudgmentWeek;
import com.jandilog.profile.repository.ProfileJudgmentQueryRepository;

// 프로필 주간 판정 이력 (PR-01 ⑩). 본인 · 타인이 같은 모양이다.
// 저장된 판정은 최근 주부터 커서 기반 20개(Q-06)로 읽는다. 팀이 없던 주(NO_TEAM 제외)는 보이지 않고,
// 내 주간 활동(AC-01 ⑤)의 지난 판정은 그대로라 그 행이 계속 보인다. 이번 주 진행 중 행은 첫 페이지에만 붙인다
@Service
public class ProfileJudgmentHistoryService {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	// 첫 페이지의 커서 상한. MariaDB DATE 범위 안의 먼 미래
	private static final LocalDate CURSOR_START = LocalDate.of(9999, 12, 31);

	private final MemberRepository memberRepository;
	private final WeeklyActivityService activityService;
	private final JudgmentSourceRepository sourceRepository;
	private final ProfileJudgmentQueryRepository queryRepository;
	private final Clock clock;

	public ProfileJudgmentHistoryService(MemberRepository memberRepository, WeeklyActivityService activityService,
			JudgmentSourceRepository sourceRepository, ProfileJudgmentQueryRepository queryRepository, Clock clock) {
		this.memberRepository = memberRepository;
		this.activityService = activityService;
		this.sourceRepository = sourceRepository;
		this.queryRepository = queryRepository;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public ProfileJudgmentPage history(long memberId, String after) {
		LocalDate before = decodeCursor(after);
		// 필터를 쿼리에서 걸어 한 페이지가 걸러진 뒤에도 20행이고 nextCursor가 어긋나지 않는다
		List<WeeklyJudgment> rows = queryRepository.findVisiblePage(memberId, before, JudgmentStatus.EXCLUDED,
				SkipReason.NO_TEAM, PageRequest.of(0, CursorCodec.PAGE_SIZE + 1));
		boolean hasNext = rows.size() > CursorCodec.PAGE_SIZE;
		List<WeeklyJudgment> page = hasNext ? rows.subList(0, CursorCodec.PAGE_SIZE) : rows;

		List<ProfileJudgmentWeek> items = new ArrayList<>(page.size() + 1);
		// 진행 중 행은 최근 주보다 위라서 첫 페이지에만 붙는다
		if (after == null || after.isEmpty()) {
			inProgressWeek(memberId).ifPresent(items::add);
		}
		page.stream().map(ProfileJudgmentWeek::from).forEach(items::add);
		String nextCursor = hasNext ? CursorCodec.encode(page.get(page.size() - 1).getWeekStart().toString()) : null;
		return new ProfileJudgmentPage(items, nextCursor);
	}

	// 이번 주가 판정 대상이고 아직 판정 행이 없을 때만 진행 중 행을 낸다.
	// 팀 없음 · 첫 참가 주 · 면제 주는 행이 없다(판정 이력 0건의 빈 상태, EX-PR01-03)
	private Optional<ProfileJudgmentWeek> inProgressWeek(long memberId) {
		LocalDate thisWeek = JudgmentWeek.mondayOf(LocalDate.now(clock.withZone(KST)));
		Member member = memberRepository.findById(memberId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		Set<Long> currentTeams = sourceRepository.findTeamIdsAt(memberId, LocalDateTime.now(clock.withZone(KST)));
		JudgmentInput input = new JudgmentInput(thisWeek, currentTeams, member.getFirstTeamJoinedAt(),
				sourceRepository.isExemptionPeriod(thisWeek),
				sourceRepository.hasApprovedPersonalExemption(memberId, thisWeek), null, null);
		if (JudgmentCalculator.skipReason(input) != null) {
			return Optional.empty();
		}
		WeeklyActivityResponse activity = activityService.weeklyActivity(memberId, null);
		return activity.judgment() == null ? Optional.of(ProfileJudgmentWeek.inProgress(activity)) : Optional.empty();
	}

	// 커서는 마지막 행 주차를 감싼 불투명 문자열. 첫 페이지는 상한 날짜, 형식이 틀리면 잘못된 입력
	private static LocalDate decodeCursor(String cursor) {
		String raw = CursorCodec.decode(cursor);
		if (raw == null) {
			return CURSOR_START;
		}
		try {
			return LocalDate.parse(raw);
		}
		catch (DateTimeParseException e) {
			throw new ApiException(ErrorCode.INVALID_INPUT);
		}
	}

}
