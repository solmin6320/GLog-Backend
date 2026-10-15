package com.jandilog.warning.service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.common.pagination.CursorCodec;
import com.jandilog.judgment.domain.JudgmentStatus;
import com.jandilog.warning.domain.WarningRecalcResult;
import com.jandilog.warning.dto.WarningCategory;
import com.jandilog.warning.dto.WarningHistoryItem;
import com.jandilog.warning.dto.WarningHistoryPage;
import com.jandilog.warning.dto.WarningHistoryRow;
import com.jandilog.warning.dto.WarningHistoryStatus;
import com.jandilog.warning.repository.WarningHistoryQueryRepository;

// 내 경고 이력 표 (AC-02 ③, 기능명세서 6장). 최근 주부터 커서 기반 20개 (Q-06).
// 상태는 저장하지 않고 경고 재계산 결과로 정한다: 현재 경고 수에 들어가면 유효, 2주 연속 통과로 빠졌으면 차감됨,
// 벌칙 이행 체크 이전에 받았으면 이행 완료 (Q-13). 삭제 표시된 경고와 정정·소급 면제로 미달이 아니게 된 주의 경고는 나오지 않는다
@Service
public class WarningHistoryService {

	// 첫 페이지의 커서 상한. MariaDB DATE 범위 안의 먼 미래
	private static final LocalDate CURSOR_START = LocalDate.of(9999, 12, 31);

	private final WarningRecalculationService recalculationService;
	private final WarningHistoryQueryRepository queryRepository;
	private final WarningCategoryReader categoryReader;

	public WarningHistoryService(WarningRecalculationService recalculationService,
			WarningHistoryQueryRepository queryRepository, WarningCategoryReader categoryReader) {
		this.recalculationService = recalculationService;
		this.queryRepository = queryRepository;
		this.categoryReader = categoryReader;
	}

	// 재계산과 이력 조회가 같은 스냅샷을 보도록 한 읽기 트랜잭션 안에서 처리한다
	@Transactional(readOnly = true)
	public WarningHistoryPage history(long memberId, String after) {
		LocalDate before = decodeCursor(after);
		if (!(recalculationService.calculate(memberId) instanceof WarningRecalcResult.Calculated calculated)) {
			return WarningHistoryPage.held();
		}
		Set<LocalDate> active = new HashSet<>(calculated.activeWarningWeeks());
		Set<LocalDate> deducted = new HashSet<>(calculated.deductedWarningWeeks());
		Set<LocalDate> fulfilled = new HashSet<>(calculated.beforeBaselineWarningWeeks());

		List<WarningHistoryRow> rows = queryRepository.findAlivePage(memberId, JudgmentStatus.FAIL, before,
				PageRequest.of(0, CursorCodec.PAGE_SIZE + 1));
		boolean hasNext = rows.size() > CursorCodec.PAGE_SIZE;
		List<WarningHistoryRow> page = hasNext ? rows.subList(0, CursorCodec.PAGE_SIZE) : rows;
		Map<Long, List<WarningCategory>> categories = categoryReader
				.categoriesOf(page.stream().map(WarningHistoryRow::warningId).toList());

		List<WarningHistoryItem> items = new ArrayList<>(page.size());
		for (WarningHistoryRow row : page) {
			WarningHistoryStatus status = statusOf(row.weekStart(), active, deducted, fulfilled);
			// 재계산이 어느 쪽에도 넣지 않은 경고는 경고로 세지 않는 것이라 보이지 않는다
			if (status == null) {
				continue;
			}
			items.add(new WarningHistoryItem(row.weekStart().toString(), row.weekStart().plusDays(6).toString(),
					row.verifiedDays(), row.recordCount(),
					categories.getOrDefault(row.warningId(), List.of()).stream().map(WarningCategory::name).toList(),
					status));
		}
		String nextCursor = hasNext ? CursorCodec.encode(page.get(page.size() - 1).weekStart().toString()) : null;
		return new WarningHistoryPage(false, items, nextCursor);
	}

	private static WarningHistoryStatus statusOf(LocalDate week, Set<LocalDate> active, Set<LocalDate> deducted,
			Set<LocalDate> fulfilled) {
		if (active.contains(week)) {
			return WarningHistoryStatus.ACTIVE;
		}
		if (deducted.contains(week)) {
			return WarningHistoryStatus.DEDUCTED;
		}
		return fulfilled.contains(week) ? WarningHistoryStatus.FULFILLED : null;
	}

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
