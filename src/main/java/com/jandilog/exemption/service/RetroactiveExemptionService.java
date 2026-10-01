package com.jandilog.exemption.service;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.jandilog.exemption.dto.RetroExemptionImpact;
import com.jandilog.exemption.dto.RetroExemptionPreview;
import com.jandilog.exemption.repository.JudgmentLookupRepository;
import com.jandilog.judgment.domain.JudgmentStatus;
import com.jandilog.judgment.domain.SkipReason;
import com.jandilog.judgment.domain.WeeklyJudgment;
import com.jandilog.judgment.repository.WeeklyJudgmentRepository;
import com.jandilog.member.domain.Member;
import com.jandilog.member.dto.MemberBrief;
import com.jandilog.member.repository.MemberRepository;
import com.jandilog.warning.dto.RecalcImpact;
import com.jandilog.warning.service.RecalcImpactService;

// 이미 판정된 주에 면제를 소급한다 (기능명세서 7장, E-45). 경고 수는 저장하지 않고 판정 결과에서 다시 구하므로
// 판정을 면제로 바꾸면 그 주의 경고는 세지 않고 이후 연속 통과·차감은 다음 계산에서 처음부터 다시 훑는다.
// 바꾸는 대상은 확정된 통과·미달뿐이다: 제외(첫 참가·팀 없음)는 이미 판정에서 빠져 있고, 보류는 확정 전이라 재판정 때 면제가 반영된다
@Service
public class RetroactiveExemptionService {

	private static final Set<JudgmentStatus> RETROACTIVE_TARGETS = EnumSet.of(JudgmentStatus.PASS, JudgmentStatus.FAIL);

	private final WeeklyJudgmentRepository judgmentRepository;
	private final JudgmentLookupRepository lookupRepository;
	private final RecalcImpactService impactService;
	private final MemberRepository memberRepository;

	public RetroactiveExemptionService(WeeklyJudgmentRepository judgmentRepository,
			JudgmentLookupRepository lookupRepository, RecalcImpactService impactService,
			MemberRepository memberRepository) {
		this.judgmentRepository = judgmentRepository;
		this.lookupRepository = lookupRepository;
		this.impactService = impactService;
		this.memberRepository = memberRepository;
	}

	// 회원 락이 잡힌 트랜잭션 안에서만 부른다. 바뀐 판정이 있으면 true
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean apply(long memberId, LocalDate weekStart, SkipReason reason) {
		Optional<WeeklyJudgment> judgment = judgmentRepository.findByMemberIdAndWeekStart(memberId, weekStart)
				.filter(j -> RETROACTIVE_TARGETS.contains(j.getStatus()));
		judgment.ifPresent(j -> j.applyExemption(reason));
		return judgment.isPresent();
	}

	// 전체 회원 대상 미리보기(면제 기간 등록·수정). 그 주에 통과·미달 판정이 있는 회원만 나온다
	@Transactional(readOnly = true)
	public RetroExemptionPreview previewAll(LocalDate weekStart) {
		List<Long> memberIds = lookupRepository.findMemberIdsByWeekAndStatusIn(weekStart, RETROACTIVE_TARGETS);
		Map<Long, Member> members = memberRepository.findAllById(memberIds).stream()
				.collect(Collectors.toMap(Member::getId, Function.identity()));
		List<RetroExemptionImpact> items = memberIds.stream()
				.filter(members::containsKey)
				.map(memberId -> impactOf(members.get(memberId), weekStart))
				.toList();
		return new RetroExemptionPreview(weekStart.toString(), !memberIds.isEmpty(), items);
	}

	// 회원 한 명 대상 미리보기(개인 면제 승인)
	@Transactional(readOnly = true)
	public RetroExemptionPreview previewMember(long memberId, LocalDate weekStart) {
		Optional<WeeklyJudgment> judgment = judgmentRepository.findByMemberIdAndWeekStart(memberId, weekStart)
				.filter(j -> RETROACTIVE_TARGETS.contains(j.getStatus()));
		if (judgment.isEmpty()) {
			return new RetroExemptionPreview(weekStart.toString(), false, List.of());
		}
		Member member = memberRepository.findById(memberId).orElseThrow();
		return new RetroExemptionPreview(weekStart.toString(), true, List.of(impactOf(member, weekStart)));
	}

	private RetroExemptionImpact impactOf(Member member, LocalDate weekStart) {
		JudgmentStatus current = judgmentRepository.findByMemberIdAndWeekStart(member.getId(), weekStart)
				.orElseThrow().getStatus();
		RecalcImpact impact = impactService.ofStatusChange(member.getId(), weekStart, JudgmentStatus.EXEMPT);
		return new RetroExemptionImpact(MemberBrief.from(member), current, impact);
	}

}
