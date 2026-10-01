package com.jandilog.judgment.domain;

import java.time.LocalDate;

// 판정에 쓴 하루치 근거. judgment_day 한 행에 대응한다 (DB명세서 1-8)
public record DayResult(LocalDate day, boolean hasGrass, boolean hasRecord) {

	// 같은 날 잔디와 기록글이 둘 다 있어도 인증일은 1일
	public boolean verified() {
		return hasGrass || hasRecord;
	}

}
