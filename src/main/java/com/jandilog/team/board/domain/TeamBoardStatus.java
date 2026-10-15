package com.jandilog.team.board.domain;

// 팀 현황판 상태 칸 (화면설계서 TM-05 ⑥). 미달은 판정이 확정된 주에만 쓴다
public enum TeamBoardStatus {
	// 인증일 3일 이상 그리고 기록글 1개 이상을 이미 채움
	PASS,
	// 아직 채우지 못했지만 주가 진행 중이라 만회할 수 있음
	NOT_YET,
	// 주가 끝나고 판정이 미달로 확정됨
	FAIL,
	// 판정 제외·면제 주 (첫 참가 주·면제 주간)
	EXCLUDED
}
