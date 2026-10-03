package com.jandilog.post.dto;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

// Mongo의 UTC 시각을 KST ISO-8601(오프셋 없음)로 바꾼다. 다른 목록 응답의 createdAt 형식과 같다
final class KstFormat {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private KstFormat() {
	}

	static String of(Instant instant) {
		return instant == null ? null : DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(LocalDateTime.ofInstant(instant, KST));
	}

}
