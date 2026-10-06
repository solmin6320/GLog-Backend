package com.jandilog.judgment.dto;

// 이번 주 화면 상단 배너. 동시에 해당하면 위에서부터 하나만 정한다: E-39 → E-32 → E-36 → E-46 (화면설계서 CM-01 ⑥)
public enum ActivityBanner {
	// E-39 GitHub 비공개 기여 표시가 꺼진 듯함 (총 기여 0인데 기록글만 있음)
	CONTRIBUTIONS_HIDDEN,
	// E-32 잔디를 아직 못 불러옴
	GRASS_NOT_LOADED,
	// E-36 계정 생애 첫 팀 참가 주
	FIRST_WEEK,
	// E-46 면제 주간
	EXEMPTION_WEEK
}
