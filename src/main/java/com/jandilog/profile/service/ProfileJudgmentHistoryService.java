package com.jandilog.profile.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.judgment.domain.JudgmentCalculator;
import com.jandilog.judgment.domain.JudgmentInput;
import com.jandilog.judgment.domain.JudgmentWeek;
import com.jandilog.judgment.dto.JudgmentHistoryPage;
import com.jandilog.judgment.dto.WeeklyActivityResponse;
import com.jandilog.judgment.repository.JudgmentSourceRepository;
import com.jandilog.judgment.service.WeeklyActivityService;
import com.jandilog.member.domain.Member;
import com.jandilog.member.repository.MemberRepository;
import com.jandilog.profile.dto.ProfileJudgmentPage;
import com.jandilog.profile.dto.ProfileJudgmentWeek;

// 프로필 주간 판정 이력 (PR-01 ⑩). 본인 · 타인이 같은 모양이다.
// 저장된 판정 목록과 커서(Q-06)는 본인 지난 판정(AC-01 ⑤)과 같은 WeeklyActivityService.history를 그대로 쓴다.
// 이번 주 진행 중 행만 여기서 붙인다
@Service
public class ProfileJudgmentHistoryService {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private final MemberRepository memberRepository;
	private final WeeklyActivityService activityService;
	private final JudgmentSourceRepository sourceRepository;
	private final Clock clock;

	public ProfileJudgmentHistoryService(MemberRepository memberRepository, WeeklyActivityService activityService,
			JudgmentSourceRepository sourceRepository, Clock clock) {
		this.memberRepository = memberRepository;
		this.activityService = activityService;
		this.sourceRepository = sourceRepository;
		this.clock = clock;
	}

	public ProfileJudgmentPage history(long memberId, String after) {
		JudgmentHistoryPage page = activityService.history(memberId, after);
		List<ProfileJudgmentWeek> items = new ArrayList<>(page.items().size() + 1);
		// 진행 중 행은 최근 주보다 위라서 첫 페이지에만 붙는다
		if (after == null || after.isEmpty()) {
			inProgressWeek(memberId).ifPresent(items::add);
		}
		page.items().stream().map(ProfileJudgmentWeek::from).forEach(items::add);
		return new ProfileJudgmentPage(items, page.nextCursor());
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

}
