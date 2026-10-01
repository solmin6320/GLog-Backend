package com.jandilog.judgment.dto;

import java.time.LocalDate;

// 한국시간(KST) 하루의 GitHub 기여 수. 1 이상이면 그날 잔디가 찍힌 것이다
public record GrassDay(LocalDate date, int count) {

	public GrassDay {
		if (date == null) {
			throw new IllegalArgumentException("date가 비었어요");
		}
		if (count < 0) {
			throw new IllegalArgumentException("count는 0 이상이어야 해요");
		}
	}

	public boolean hasGrass() {
		return count >= 1;
	}

}
