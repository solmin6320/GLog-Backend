package com.jandilog.team.board.domain;

import java.time.LocalDate;

// 현황판 요일 한 칸. hasGrass가 null이면 잔디를 알 수 없는 날(캐시 없음·캐시 구간 밖)이다
public record BoardDay(LocalDate date, Boolean hasGrass, boolean hasRecord) {

	// 잔디 또는 기록글이 있으면 인증일(같은 날 둘 다여도 1일). 둘 다 없는데 잔디를 모르면 null
	public Boolean verified() {
		if (hasRecord || Boolean.TRUE.equals(hasGrass)) {
			return true;
		}
		return hasGrass == null ? null : false;
	}

}
