package com.jandilog.judgment.service;

import java.time.LocalDate;
import java.util.List;

import com.jandilog.judgment.dto.GrassDay;

// 잔디(GitHub 기여) 조회 포트. 실제 구현은 GitHubGrassClient, 테스트는 이 인터페이스를 갈아끼운다.
// 날짜는 모두 한국시간(KST) 일자다. 구현체가 GitHub의 시각 기준을 KST 일자로 맞춰서 돌려준다
public interface GrassClient {

	// from~to(양끝 포함) 일자별 기여 수를 날짜 오름차순으로 빠짐없이 돌려준다.
	// 실패는 GrassFetchException: 사용자 없음은 IDENTITY_MISMATCH, 그 밖은 API_ERROR
	List<GrassDay> fetchDailyContributions(String githubLogin, LocalDate from, LocalDate to);

}
