package com.jandilog.judgment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jandilog.judgment.dto.GrassDay;
import com.jandilog.judgment.dto.GrassSnapshot;

// 잔디 스냅샷과 Redis JSON의 변환 (DB명세서 2장). 깨진 값은 읽을 때 예외로 알린다
class GrassSnapshotJsonTest {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final GrassSnapshotJson codec = new GrassSnapshotJson(objectMapper);

	private static GrassSnapshot snapshot() {
		return new GrassSnapshot(7L, LocalDate.of(2026, 9, 28), LocalDate.of(2026, 9, 30),
				LocalDateTime.of(2026, 9, 30, 6, 0, 1),
				List.of(new GrassDay(LocalDate.of(2026, 9, 28), 3), new GrassDay(LocalDate.of(2026, 9, 29), 0),
						new GrassDay(LocalDate.of(2026, 9, 30), 12)));
	}

	@Test
	void 쓴_JSON을_다시_읽으면_같은_스냅샷이다() throws Exception {
		GrassSnapshot original = snapshot();

		assertThat(codec.read(codec.write(original))).isEqualTo(original);
	}

	@Test
	void 날짜와_시각은_문자열로_쓴다() throws Exception {
		JsonNode json = objectMapper.readTree(codec.write(snapshot()));

		assertThat(json.get("memberId").asLong()).isEqualTo(7L);
		assertThat(json.get("from").asText()).isEqualTo("2026-09-28");
		assertThat(json.get("to").asText()).isEqualTo("2026-09-30");
		assertThat(json.get("fetchedAt").asText()).isEqualTo("2026-09-30T06:00:01");
		assertThat(json.get("days").get(2).get("date").asText()).isEqualTo("2026-09-30");
		assertThat(json.get("days").get(2).get("count").asInt()).isEqualTo(12);
	}

	@Test
	void JSON이_깨졌으면_JsonProcessingException이다() {
		assertThatThrownBy(() -> codec.read("{not json")).isInstanceOf(JsonProcessingException.class);
	}

	@Test
	void 구간이나_갱신_시각이_빠졌으면_IllegalArgumentException이다() {
		String days = "\"days\":[{\"date\":\"2026-09-28\",\"count\":1}]";

		assertThatThrownBy(() -> codec.read("{\"memberId\":7,\"to\":\"2026-09-28\",\"fetchedAt\":\"2026-09-28T06:00:00\"," + days + "}"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> codec.read("{\"memberId\":7,\"from\":\"2026-09-28\",\"fetchedAt\":\"2026-09-28T06:00:00\"," + days + "}"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> codec.read("{\"memberId\":7,\"from\":\"2026-09-28\",\"to\":\"2026-09-28\"," + days + "}"))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void 일자_목록이_없으면_IllegalArgumentException이다() {
		assertThatThrownBy(() -> codec.read(
				"{\"memberId\":7,\"from\":\"2026-09-28\",\"to\":\"2026-09-28\",\"fetchedAt\":\"2026-09-28T06:00:00\"}"))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void 일자_항목이_비었거나_날짜가_없으면_IllegalArgumentException이다() {
		String head = "{\"memberId\":7,\"from\":\"2026-09-28\",\"to\":\"2026-09-28\",\"fetchedAt\":\"2026-09-28T06:00:00\",";

		assertThatThrownBy(() -> codec.read(head + "\"days\":[null]}")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> codec.read(head + "\"days\":[{\"count\":1}]}"))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void 날짜_형식이_틀리면_DateTimeParseException이다() {
		String bad = "{\"memberId\":7,\"from\":\"28/09/2026\",\"to\":\"2026-09-28\",\"fetchedAt\":\"2026-09-28T06:00:00\","
				+ "\"days\":[{\"date\":\"2026-09-28\",\"count\":1}]}";

		assertThatThrownBy(() -> codec.read(bad)).isInstanceOf(DateTimeParseException.class);
	}

	@Test
	void 일자가_구간을_빠짐없이_채우지_않으면_IllegalArgumentException이다() {
		String gap = "{\"memberId\":7,\"from\":\"2026-09-28\",\"to\":\"2026-09-30\",\"fetchedAt\":\"2026-09-30T06:00:00\","
				+ "\"days\":[{\"date\":\"2026-09-28\",\"count\":1}]}";

		assertThatThrownBy(() -> codec.read(gap)).isInstanceOf(IllegalArgumentException.class);
	}

}
