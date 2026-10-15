package com.jandilog.team.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jandilog.team.domain.Team;
import com.jandilog.team.repository.TeamRepository;

// 글에 붙는 팀 이름표. 공개 팀만 이름을 내려주고 비공개·삭제된 팀은 숨긴다.
// 열람자를 구분하지 않고 무조건 적용한다 (기능명세서 2장 공개 설정 표, E-23)
@Service
public class TeamNameService {

	private final TeamRepository teamRepository;

	public TeamNameService(TeamRepository teamRepository) {
		this.teamRepository = teamRepository;
	}

	// teamId → 이름. 보이지 않는 팀은 결과에 들어 있지 않다
	@Transactional(readOnly = true)
	public Map<Long, String> visibleNames(Collection<Long> teamIds) {
		Map<Long, String> names = new HashMap<>();
		if (teamIds.isEmpty()) {
			return names;
		}
		for (Team team : teamRepository.findPublicAlive(teamIds)) {
			names.put(team.getId(), team.getName());
		}
		return names;
	}

}
