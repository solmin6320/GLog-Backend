package com.jandilog.common.time;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

// 응답에 싣는 시각을 KST ISO-8601(오프셋 없음)로 만든다. 없으면 null
public final class KstFormats {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private KstFormats() {
	}

	public static String of(LocalDateTime dateTime) {
		return dateTime == null ? null : DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(dateTime);
	}

	public static String of(Instant instant) {
		return instant == null ? null : of(LocalDateTime.ofInstant(instant, KST));
	}

}
