package com.jandilog.judgment.domain;

import java.time.LocalDate;
import java.util.Map;

// 판정에 넘기는 잔디 조회 결과. 성공하면 일자별 잔디 유무, 실패하면 보류 사유
public record GrassLookup(Map<LocalDate, Boolean> hasGrassByDay, HoldReason failure) {

	public GrassLookup {
		if ((hasGrassByDay == null) == (failure == null)) {
			throw new IllegalArgumentException("잔디 결과와 실패 사유 중 하나만 있어야 해요");
		}
		hasGrassByDay = hasGrassByDay == null ? null : Map.copyOf(hasGrassByDay);
	}

	public static GrassLookup available(Map<LocalDate, Boolean> hasGrassByDay) {
		return new GrassLookup(hasGrassByDay, null);
	}

	public static GrassLookup failed(HoldReason reason) {
		return new GrassLookup(null, reason);
	}

	public boolean isFailed() {
		return failure != null;
	}

}
