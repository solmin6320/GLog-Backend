package com.jandilog.exemption.service;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.common.pagination.CursorCodec;
import com.jandilog.common.validation.InputRules;
import com.jandilog.exemption.domain.ExemptionPeriod;
import com.jandilog.exemption.dto.ExemptionPeriodInput;
import com.jandilog.exemption.dto.ExemptionPeriodPage;
import com.jandilog.exemption.dto.ExemptionPeriodResponse;
import com.jandilog.exemption.dto.RetroExemptionPreview;
import com.jandilog.exemption.repository.ExemptionPeriodRepository;
import com.jandilog.exemption.repository.JudgmentLookupRepository;
import com.jandilog.judgment.domain.JudgmentStatus;
import com.jandilog.judgment.domain.SkipReason;
import com.jandilog.warning.service.WarningRecalculationService;

// 면제 기간 관리 (관리자, 기능명세서 7장 · DB명세서 1-12). 주 단위로 등록·수정·삭제하고 전체 회원에게 적용된다.
// 이미 판정된 주를 등록하거나 그 주로 옮기면 그 주의 통과·미달 판정이 면제로 바뀐다(소급, E-45). 화면은 미리보기를 먼저 부른다.
// 삭제하거나 다른 주로 옮겨도 이미 면제로 바뀐 판정은 그대로 둔다: 확정된 판정은 관리자 정정으로만 바뀌기 때문이다 (기능명세서 5장)
@Service
public class ExemptionPeriodService {

	static final int REASON_MAX_LENGTH = 200;
	private static final Set<JudgmentStatus> RETROACTIVE_TARGETS = EnumSet.of(JudgmentStatus.PASS, JudgmentStatus.FAIL);

	private final ExemptionPeriodRepository periodRepository;
	private final JudgmentLookupRepository lookupRepository;
	private final RetroactiveExemptionService retroactiveService;
	private final WarningRecalculationService recalculationService;
	private final Clock clock;

	public ExemptionPeriodService(ExemptionPeriodRepository periodRepository,
			JudgmentLookupRepository lookupRepository, RetroactiveExemptionService retroactiveService,
			WarningRecalculationService recalculationService, Clock clock) {
		this.periodRepository = periodRepository;
		this.lookupRepository = lookupRepository;
		this.retroactiveService = retroactiveService;
		this.recalculationService = recalculationService;
		this.clock = clock;
	}

	// 주차 최신순 커서 20개. 커서는 마지막 행의 주차
	@Transactional(readOnly = true)
	public ExemptionPeriodPage list(String cursor) {
		String raw = CursorCodec.decode(cursor);
		Pageable pageable = PageRequest.of(0, CursorCodec.PAGE_SIZE + 1);
		List<ExemptionPeriod> rows = raw == null
				? periodRepository.findAllByOrderByWeekStartDesc(pageable)
				: periodRepository.findByWeekStartLessThanOrderByWeekStartDesc(parseCursor(raw), pageable);
		boolean hasNext = rows.size() > CursorCodec.PAGE_SIZE;
		List<ExemptionPeriod> page = hasNext ? rows.subList(0, CursorCodec.PAGE_SIZE) : rows;
		String nextCursor = hasNext ? CursorCodec.encode(page.get(page.size() - 1).getWeekStart().toString()) : null;
		return new ExemptionPeriodPage(page.stream().map(ExemptionPeriodResponse::from).toList(), nextCursor);
	}

	// 그 주를 면제 기간으로 두면 바뀌는 판정과 영향. 아무것도 바꾸지 않는다
	@Transactional(readOnly = true)
	public RetroExemptionPreview preview(String weekStart) {
		return retroactiveService.previewAll(InputRules.weekStart(weekStart));
	}

	@Transactional(isolation = Isolation.READ_COMMITTED)
	public ExemptionPeriodResponse create(long adminId, ExemptionPeriodInput input) {
		LocalDate week = InputRules.weekStart(input.weekStart());
		String reason = InputRules.reason(input.reason(), REASON_MAX_LENGTH, ErrorCode.REASON_REQUIRED);
		requireFree(week);
		ExemptionPeriod saved = save(ExemptionPeriod.create(week, reason, adminId, LocalDateTime.now(clock)));
		applyRetroactively(week);
		return ExemptionPeriodResponse.from(saved);
	}

	@Transactional(isolation = Isolation.READ_COMMITTED)
	public ExemptionPeriodResponse update(String id, ExemptionPeriodInput input) {
		ExemptionPeriod period = periodRepository.findById(InputRules.id(id))
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		LocalDate week = InputRules.weekStart(input.weekStart());
		String reason = InputRules.reason(input.reason(), REASON_MAX_LENGTH, ErrorCode.REASON_REQUIRED);
		boolean weekChanged = !week.equals(period.getWeekStart());
		if (weekChanged) {
			requireFree(week);
		}
		period.change(week, reason, LocalDateTime.now(clock));
		if (weekChanged) {
			save(period);
			applyRetroactively(week);
		}
		return ExemptionPeriodResponse.from(period);
	}

	@Transactional
	public void delete(String id) {
		ExemptionPeriod period = periodRepository.findById(InputRules.id(id))
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		periodRepository.delete(period);
	}

	// 그 주에 통과·미달 판정을 가진 회원을 id 오름차순으로 하나씩 잠그고 면제로 바꾼다
	private void applyRetroactively(LocalDate week) {
		for (long memberId : lookupRepository.findMemberIdsByWeekAndStatusIn(week, RETROACTIVE_TARGETS)) {
			recalculationService.lockMember(memberId);
			retroactiveService.apply(memberId, week, SkipReason.EXEMPTION_PERIOD);
		}
	}

	private void requireFree(LocalDate week) {
		if (periodRepository.existsByWeekStart(week)) {
			throw new ApiException(ErrorCode.EXEMPTION_PERIOD_DUPLICATE);
		}
	}

	// week_start UNIQUE가 동시 등록을 막는다
	private ExemptionPeriod save(ExemptionPeriod period) {
		try {
			return periodRepository.saveAndFlush(period);
		} catch (DataIntegrityViolationException e) {
			throw new ApiException(ErrorCode.EXEMPTION_PERIOD_DUPLICATE);
		}
	}

	private static LocalDate parseCursor(String raw) {
		try {
			return LocalDate.parse(raw);
		} catch (DateTimeException e) {
			throw new ApiException(ErrorCode.INVALID_INPUT);
		}
	}

}
