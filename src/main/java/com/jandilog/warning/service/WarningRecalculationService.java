package com.jandilog.warning.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import com.jandilog.warning.domain.Warning;
import com.jandilog.warning.domain.WarningRecalcPreview;
import com.jandilog.warning.domain.WarningRecalcResult;
import com.jandilog.warning.domain.WarningRecalculator;
import com.jandilog.warning.domain.WeekEntry;
import com.jandilog.warning.repository.LatestFulfillment;
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

	// 여러 회원 행을 id 오름차순으로 하나씩 잠근다. 같은 회원들을 잠그는 다른 트랜잭션과 순서가 같아 교착하지 않는다
	@Transactional(propagation = Propagation.MANDATORY)
	public void lockMembers(Collection<Long> memberIds) {
		memberIds.stream().distinct().sorted().forEach(this::lockMember);
	}

	// 락을 잡고 다시 계산한다. 변경을 마친 같은 트랜잭션에서 부르면 변경이 반영된 값을 돌려준다
	@Transactional
	public WarningRecalcResult recalculate(long memberId) {
		lockMember(memberId);
		return compute(load(memberId), Map.of(), Set.of());
	}

	// 여러 회원을 잠그고 한 번에 다시 계산한다. 결과는 회원마다 recalculate를 부른 것과 같다
	@Transactional
	public Map<Long, WarningRecalcResult> recalculateAll(Collection<Long> memberIds) {
		lockMembers(memberIds);
		return computeAll(memberIds);
	}

	// 락 없이 읽기만 한다. 화면의 경고 수·연속 통과 표시용
	@Transactional(readOnly = true)
	public WarningRecalcResult calculate(long memberId) {
		return compute(load(memberId), Map.of(), Set.of());
	}

	// 여러 회원을 락 없이 한 번에 계산한다. 목록 화면(팀 현황판·벌칙 대상·관리자 목록)이 회원 수만큼 쿼리를 다시 부르지 않게
	// 판정·경고·이행 기준선을 테이블마다 한 번씩만 읽는다. 결과는 calculate를 회원마다 부른 것과 같다
	@Transactional(readOnly = true)
	public Map<Long, WarningRecalcResult> calculateAll(Collection<Long> memberIds) {
		return computeAll(memberIds);
	}

	// 미리보기: hypothetical(주차 → 가정한 판정 상태)을 적용한 값과 지금 값을 비교한다. DB는 바꾸지 않는다.
	// 소급 면제는 EXEMPT, 정정은 PASS/FAIL/EXEMPT를 가정한다. 이행 기준선 이전 주를 가정하면 값은 그대로다 (E-58)
	@Transactional(readOnly = true)
	public WarningRecalcPreview preview(long memberId, Map<LocalDate, JudgmentStatus> hypothetical) {
		return preview(memberId, hypothetical, Set.of());
	}

	// 경고 복구 미리보기: restoredWarningWeeks에 든 주의 경고는 삭제 표시를 풀었다고 가정한다 (E-42)
	@Transactional(readOnly = true)
	public WarningRecalcPreview preview(long memberId, Map<LocalDate, JudgmentStatus> hypothetical,
			Set<LocalDate> restoredWarningWeeks) {
		History history = load(memberId);
		return new WarningRecalcPreview(compute(history, Map.of(), Set.of()),
				compute(history, hypothetical, restoredWarningWeeks));
	}

	private Map<Long, WarningRecalcResult> computeAll(Collection<Long> memberIds) {
		Map<Long, WarningRecalcResult> results = new HashMap<>();
		loadAll(memberIds).forEach((memberId, history) -> results.put(memberId, compute(history, Map.of(), Set.of())));
		return results;
	}

	private History load(long memberId) {
		return loadAll(List.of(memberId)).get(memberId);
	}

	// 회원 한 명이든 여럿이든 같은 쿼리 세 개(판정·경고·최근 이행)로 읽어 회원별로 나눈다. 이력이 없는 회원도 빈 이력으로 넣는다
	private Map<Long, History> loadAll(Collection<Long> memberIds) {
		List<Long> ids = memberIds.stream().distinct().toList();
		if (ids.isEmpty()) {
			return Map.of();
		}
		Map<Long, List<WeeklyJudgment>> judgments = judgmentRepository.findByMemberIdIn(ids).stream()
				.collect(Collectors.groupingBy(WeeklyJudgment::getMemberId));
		Map<Long, Map<LocalDate, Warning>> warnings = warningRepository.findByMemberIdIn(ids).stream()
				.collect(Collectors.groupingBy(Warning::getMemberId,
						Collectors.toMap(Warning::getWeekStart, Function.identity())));
		Map<Long, LocalDateTime> baselines = fulfillmentRepository.findLatestByMemberIds(ids).stream()
				.collect(Collectors.toMap(LatestFulfillment::memberId, LatestFulfillment::fulfilledAt));

		Map<Long, History> histories = new HashMap<>();
		for (Long id : ids) {
			histories.put(id, new History(judgments.getOrDefault(id, List.of()), warnings.getOrDefault(id, Map.of()),
					baselines.get(id)));
		}
		return histories;
	}

	private WarningRecalcResult compute(History history, Map<LocalDate, JudgmentStatus> hypothetical,
			Set<LocalDate> restoredWarningWeeks) {
		Map<LocalDate, WeekEntry> entries = new TreeMap<>();
		for (WeeklyJudgment judgment : history.judgments()) {
			LocalDate week = judgment.getWeekStart();
			JudgmentStatus status = hypothetical.getOrDefault(week, judgment.getStatus());
			Warning warning = history.warnings().get(week);
			boolean deleted = warning != null && !warning.isAlive() && !restoredWarningWeeks.contains(week);
			entries.put(week, new WeekEntry(week, status, deleted));
		}
		// 판정 행이 아직 없는 주를 가정한 경우도 한 주로 넣는다
		hypothetical.forEach((week, status) -> entries.putIfAbsent(week, new WeekEntry(week, status, false)));
		return WarningRecalculator.recalculate(entries.values(), history.baseline());
	}

	private record History(List<WeeklyJudgment> judgments, Map<LocalDate, Warning> warnings,
			LocalDateTime baseline) {
	}

}
