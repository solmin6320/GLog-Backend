package com.jandilog.judgment.service;

import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Component;

import com.jandilog.common.config.GrassProperties;
import com.jandilog.judgment.dto.GrassDay;

// 프로필 잔디 전체 이력의 GitHub 구현. 판정용 GrassClient 빈과 따로 두어 그 빈을 바꿔 끼워도 영향이 없다
@Component
public class GitHubGrassHistoryClient implements GrassHistoryClient {

	private final GitHubGrassClient github;

	public GitHubGrassHistoryClient(GrassProperties properties) {
		this.github = new GitHubGrassClient(properties);
	}

	@Override
	public List<GrassDay> fetchAllDailyContributions(Long githubId, String githubLogin, LocalDate today) {
		return github.fetchAllDailyContributions(githubId, githubLogin, today);
	}

}
