package com.jandilog.warning.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.judgment.domain.JudgmentStatus;
import com.jandilog.judgment.domain.WeeklyJudgment;
import com.jandilog.judgment.repository.WeeklyJudgmentRepository;
import com.jandilog.warning.domain.PenaltyFulfillment;
import com.jandilog.warning.domain.Warning;
import com.jandilog.warning.domain.WarningRecalcPreview;
import com.jandilog.warning.domain.WarningRecalcResult;
import com.jandilog.warning.domain.WarningRecalculator;
import com.jandilog.warning.domain.WeekEntry;
import com.jandilog.warning.repository.MemberLockRepository;
import com.jandilog.warning.repository.PenaltyFulfillmentRepository;
import com.jandilog.warning.repository.WarningRepository;

// 경고 재계산의 DB 쪽 창구. 판정 이력·경고·이행 기준선을 읽어 WarningRecalculator에 넘긴다.
// 경고 수·연속 통과는 저장하지 않고 호출할 때마다 처음부터 다시 계산한다(member_warning_state 같은 캐시 테이블 없음).
// 정정·소급 면제·복구·이행 체크·판정 저장은 변경 전에 lockMember로 회원 행을 잠그고 같은 트랜잭션에서 recalculate를 부른다 (Q-08)
@Service
public class WarningRecalculationService {

	private final MemberLockRepository memberLockRepository;
	private final WeeklyJudgmentRepository judgmentRepository;
	private final WarningRepository warningRepository;
	private final PenaltyFulfillmentRepository fulfillmentRepository;

	public WarningRecalculationService(MemberLockRepository memberLockRepository,
			WeeklyJudgmentRepository judgmentRepository, WarningRepository warningRepository,
			PenaltyFulfillmentRepository fulfillmentRepository) {
		this.memberLockRepository = memberLockRepository;
		this.judgmentRepository = judgmentRepository;
		this.warningRepository = warningRepository;
		this.fulfillmentRepository = fulfillmentRepository;
	}

	// 회원 행 SELECT ... FOR UPDATE. 이미 열린 트랜잭션 안에서만 부를 수 있다(락은 그 트랜잭션이 끝날 때 풀린다)
	@Transactional(propagation = Propagation.MANDATORY)
	public void lockMember(long memberId) {
		memberLockRepository.findLockedById(memberId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	// 락을 잡고 다시 계산한다. 변경을 마친 같은 트랜잭션에서 부르면 변경이 반영된 값을 돌려준다
	@Transactional
	public WarningRecalcResult recalculate(long memberId) {
		lockMember(memberId);
		return compute(load(memberId), Map.of());
	}

	// 락 없이 읽기만 한다. 화면의 경고 수·연속 통과 표시용
	@Transactional(readOnly = true)
	public WarningRecalcResult calculate(long memberId) {
		return compute(load(memberId), Map.of());
	}

	// 미리보기: hypothetical(주차 → 가정한 판정 상태)을 적용한 값과 지금 값을 비교한다. DB는 바꾸지 않는다.
	// 소급 면제는 EXEMPT, 정정은 PASS/FAIL/EXEMPT를 가정한다. 이행 기준선 이전 주를 가정하면 값은 그대로다 (E-58)
	@Transactional(readOnly = true)
	public WarningRecalcPreview preview(long memberId, Map<LocalDate, JudgmentStatus> hypothetical) {
		History history = load(memberId);
		return new WarningRecalcPreview(compute(history, Map.of()), compute(history, hypothetical));
	}

	private History load(long memberId) {
		List<WeeklyJudgment> judgments = judgmentRepository.findByMemberIdOrderByWeekStartAsc(memberId);
		Map<LocalDate, Warning> warnings = warningRepository.findByMemberIdOrderByWeekStartAsc(memberId).stream()
				.collect(Collectors.toMap(Warning::getWeekStart, Function.identity()));
		LocalDateTime baseline = fulfillmentRepository.findFirstByMemberIdOrderByFulfilledAtDesc(memberId)
				.map(PenaltyFulfillment::getFulfilledAt).orElse(null);
		return new History(judgments, warnings, baseline);
	}

	private WarningRecalcResult compute(History history, Map<LocalDate, JudgmentStatus> hypothetical) {
		Map<LocalDate, WeekEntry> entries = new TreeMap<>();
		for (WeeklyJudgment judgment : history.judgments()) {
			LocalDate week = judgment.getWeekStart();
			JudgmentStatus status = hypothetical.getOrDefault(week, judgment.getStatus());
			Warning warning = history.warnings().get(week);
			entries.put(week, new WeekEntry(week, status, warning != null && !warning.isAlive()));
		}
		// 판정 행이 아직 없는 주를 가정한 경우도 한 주로 넣는다
		hypothetical.forEach((week, status) -> entries.putIfAbsent(week, new WeekEntry(week, status, false)));
		return WarningRecalculator.recalculate(entries.values(), history.baseline());
	}

	private record History(List<WeeklyJudgment> judgments, Map<LocalDate, Warning> warnings,
			LocalDateTime baseline) {
	}

}
