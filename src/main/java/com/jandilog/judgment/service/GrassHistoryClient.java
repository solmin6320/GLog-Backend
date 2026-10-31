package com.jandilog.judgment.service;

import java.time.LocalDate;
import java.util.List;

import com.jandilog.judgment.dto.GrassDay;

// 프로필 잔디 전체 이력 조회 포트 (2026-10-31 사용자 지시: GitHub 프로필 전체 데이터). 판정용 GrassClient와 따로 둔다
public interface GrassHistoryClient {

	// GitHub 가입일부터 today까지(양끝 포함) 일자별 기여 수를 날짜 오름차순으로 빠짐없이 돌려준다.
	// githubId가 있으면 githubLogin의 주인이 그 숫자 id 계정인지 확인한다. 실패는 GrassFetchException
	List<GrassDay> fetchAllDailyContributions(Long githubId, String githubLogin, LocalDate today);

}
