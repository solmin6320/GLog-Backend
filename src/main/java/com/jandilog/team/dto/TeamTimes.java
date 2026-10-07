package com.jandilog.team.dto;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

// DB의 KST DATETIME을 ISO-8601(오프셋 없음) 문자열로. 다른 응답의 createdAt 형식과 같다
public final class TeamTimes {

	private TeamTimes() {
	}

	public static String format(LocalDateTime time) {
		return time == null ? null : DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(time);
	}

}
