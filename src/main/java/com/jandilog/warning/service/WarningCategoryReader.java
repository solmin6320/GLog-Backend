package com.jandilog.warning.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.jandilog.team.query.TeamReadModel;
import com.jandilog.warning.domain.WarningTeam;
import com.jandilog.warning.dto.WarningCategory;
import com.jandilog.warning.repository.WarningTeamRepository;

// 경고별 팀 카테고리의 표시 이름을 모은다. 경고 이력(AC-02 ③)과 프로필 팀별 경고(PR-01 ⑨)가 같은 규칙을 쓴다.
// 빠진 카테고리(removed_at)는 보이지 않고, 남은 카테고리가 하나도 없는데 살아 있는 경고는
// 팀이 삭제된 채 복구된 경고이므로 "삭제된 팀" 하나로 보인다 (E-43)
@Component
public class WarningCategoryReader {

	private final WarningTeamRepository warningTeamRepository;
	private final TeamReadModel teamReadModel;

	public WarningCategoryReader(WarningTeamRepository warningTeamRepository, TeamReadModel teamReadModel) {
		this.warningTeamRepository = warningTeamRepository;
		this.teamReadModel = teamReadModel;
	}

	// 경고 id별 카테고리. 한 경고 안에서 같은 이름은 한 번만 나오고 공개 팀 → 비공개 팀 → 삭제된 팀 순이다
	public Map<Long, List<WarningCategory>> categoriesOf(Collection<Long> warningIds) {
		if (warningIds.isEmpty()) {
			return Map.of();
		}
		List<WarningTeam> rows = warningTeamRepository.findByWarningIdIn(warningIds);
		Map<Long, TeamReadModel.TeamName> teams = teamReadModel
				.findByIds(rows.stream().map(WarningTeam::getTeamId).distinct().toList());
		Map<Long, List<WarningTeam>> visibleByWarning = rows.stream()
				.filter(row -> !row.isRemoved())
				.collect(Collectors.groupingBy(WarningTeam::getWarningId));

		Map<Long, List<WarningCategory>> result = new HashMap<>();
		for (Long warningId : warningIds) {
			Map<String, WarningCategory> distinct = new LinkedHashMap<>();
			for (WarningTeam row : visibleByWarning.getOrDefault(warningId, List.of())) {
				WarningCategory category = WarningCategory.of(teams.get(row.getTeamId()));
				distinct.putIfAbsent(category.groupKey(), category);
			}
			if (distinct.isEmpty()) {
				WarningCategory deleted = WarningCategory.deleted();
				distinct.put(deleted.groupKey(), deleted);
			}
			result.put(warningId, distinct.values().stream().sorted(WarningCategory.DISPLAY_ORDER).toList());
		}
		return result;
	}

}
