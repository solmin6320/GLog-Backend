package com.jandilog.profile.dto;

import java.util.List;

import com.jandilog.common.time.KstFormats;
import com.jandilog.judgment.dto.GrassSnapshot;

// 프로필의 GitHub 잔디 (PR-01 ⑦). 캐시에 있는 구간 그대로이고 GitHub를 부르지 않는다. fetchedAt이 "오늘 오전 6시 기준"의 갱신 시각이다
public record ProfileGrass(String from, String to, String fetchedAt, List<Day> days) {

	public record Day(String date, int count) {
	}

	public static ProfileGrass from(GrassSnapshot snapshot) {
		return new ProfileGrass(snapshot.from().toString(), snapshot.to().toString(),
				KstFormats.of(snapshot.fetchedAt()),
				snapshot.days().stream().map(day -> new Day(day.date().toString(), day.count())).toList());
	}

}
