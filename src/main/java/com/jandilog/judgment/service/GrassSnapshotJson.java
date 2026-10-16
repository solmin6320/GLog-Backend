package com.jandilog.judgment.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jandilog.judgment.dto.GrassDay;
import com.jandilog.judgment.dto.GrassSnapshot;

// 잔디 스냅샷과 Redis JSON의 변환. 날짜는 문자열로 두어 ObjectMapper 설정과 무관하게 같은 JSON이 나온다.
// 읽을 때 JSON이 깨졌거나 빈 값·잘못된 날짜가 있으면 JsonProcessingException, DateTimeParseException, IllegalArgumentException
final class GrassSnapshotJson {

	private final ObjectMapper objectMapper;

	GrassSnapshotJson(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	String write(GrassSnapshot snapshot) throws JsonProcessingException {
		return objectMapper.writeValueAsString(toEntry(snapshot));
	}

	GrassSnapshot read(String json) throws JsonProcessingException {
		return toSnapshot(objectMapper.readValue(json, CacheEntry.class));
	}

	record CacheEntry(long memberId, String from, String to, String fetchedAt, List<CacheDay> days) {
	}

	record CacheDay(String date, int count) {
	}

	private static CacheEntry toEntry(GrassSnapshot snapshot) {
		return new CacheEntry(snapshot.memberId(), snapshot.from().toString(), snapshot.to().toString(),
				snapshot.fetchedAt().toString(),
				snapshot.days().stream().map(d -> new CacheDay(d.date().toString(), d.count())).toList());
	}

	private static GrassSnapshot toSnapshot(CacheEntry entry) {
		if (entry == null || entry.from() == null || entry.to() == null || entry.fetchedAt() == null
				|| entry.days() == null) {
			throw new IllegalArgumentException("잔디 캐시에 빈 값이 있어요");
		}
		return new GrassSnapshot(entry.memberId(), LocalDate.parse(entry.from()), LocalDate.parse(entry.to()),
				LocalDateTime.parse(entry.fetchedAt()),
				entry.days().stream().map(GrassSnapshotJson::toDay).toList());
	}

	private static GrassDay toDay(CacheDay day) {
		if (day == null || day.date() == null) {
			throw new IllegalArgumentException("잔디 캐시 일자에 빈 값이 있어요");
		}
		return new GrassDay(LocalDate.parse(day.date()), day.count());
	}

}
