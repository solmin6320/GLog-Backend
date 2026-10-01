package com.jandilog.warning.domain;

import java.time.LocalDate;

import com.jandilog.judgment.domain.JudgmentStatus;

// 재계산이 훑는 한 주. status는 저장된 판정이거나 미리보기용 가정값이다.
// warningDeleted: 그 주 경고 레코드가 있고 소프트 삭제된 상태(추방·팀 삭제). 레코드가 없으면 false라
// 미달(FAIL)이면 경고가 새로 생길 주로 센다(미리보기에서 통과→미달 정정)
public record WeekEntry(LocalDate weekStart, JudgmentStatus status, boolean warningDeleted) {

	public WeekEntry {
		if (weekStart == null || status == null) {
			throw new IllegalArgumentException("weekStart와 status가 필요해요");
		}
	}

	// 경고 수에 들어가는 주: 미달이고 경고 레코드가 살아 있다 (E-60)
	public boolean countsAsWarning() {
		return status == JudgmentStatus.FAIL && !warningDeleted;
	}

}
