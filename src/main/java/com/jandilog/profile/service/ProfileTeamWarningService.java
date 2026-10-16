package com.jandilog.profile.service;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jandilog.profile.dto.ProfileTeamWarning;
import com.jandilog.profile.dto.ProfileTeamWarnings;
import com.jandilog.warning.domain.Warning;
import com.jandilog.warning.domain.WarningRecalcResult;
import com.jandilog.warning.dto.WarningCategory;
import com.jandilog.warning.repository.WarningRepository;
import com.jandilog.warning.service.WarningCategoryReader;
import com.jandilog.warning.service.WarningRecalculationService;

// 프로필 팀별 경고 (기능명세서 8장, PR-01 ⑨). 본인 · 타인이 같은 모양이다.
// ⑧ 경고 합계와 같은 기준으로 센다: 재계산이 현재 경고 수에 넣은 경고만(이행 완료 · 차감된 경고 제외).
// 경고 1개가 붙은 카테고리마다 1회씩 세므로 중복 집계다. 비공개 · 삭제된 팀은 이름 하나로 묶여 한 줄이 된다
@Service
public class ProfileTeamWarningService {

	private final WarningRecalculationService recalculationService;
	private final WarningRepository warningRepository;
	private final WarningCategoryReader categoryReader;

	public ProfileTeamWarningService(WarningRecalculationService recalculationService,
			WarningRepository warningRepository, WarningCategoryReader categoryReader) {
		this.recalculationService = recalculationService;
		this.warningRepository = warningRepository;
		this.categoryReader = categoryReader;
	}

	// 재계산과 경고 조회가 같은 스냅샷을 보도록 한 읽기 트랜잭션 안에서 처리한다
	@Transactional(readOnly = true)
	public ProfileTeamWarnings teamWarnings(long memberId) {
		if (!(recalculationService.calculate(memberId) instanceof WarningRecalcResult.Calculated calculated)) {
			return ProfileTeamWarnings.held();
		}
		Set<LocalDate> activeWeeks = new HashSet<>(calculated.activeWarningWeeks());
		List<Long> warningIds = warningRepository.findByMemberIdAndDeletedAtIsNullOrderByWeekStartAsc(memberId)
				.stream()
				.filter(warning -> activeWeeks.contains(warning.getWeekStart()))
				.map(Warning::getId)
				.toList();

		Map<String, WarningCategory> categoryByKey = new LinkedHashMap<>();
		Map<String, Integer> counts = new LinkedHashMap<>();
		categoryReader.categoriesOf(warningIds).values().forEach(categories -> categories.forEach(category -> {
			categoryByKey.putIfAbsent(category.groupKey(), category);
			counts.merge(category.groupKey(), 1, Integer::sum);
		}));
		List<ProfileTeamWarning> teams = categoryByKey.values().stream()
				.sorted(WarningCategory.DISPLAY_ORDER)
				.map(category -> new ProfileTeamWarning(category.name(), counts.get(category.groupKey())))
				.toList();
		return new ProfileTeamWarnings(false, teams);
	}

}
