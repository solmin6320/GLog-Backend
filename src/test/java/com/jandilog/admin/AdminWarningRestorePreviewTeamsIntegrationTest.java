package com.jandilog.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.jandilog.judgment.domain.JudgmentWeek;
import com.jandilog.testsupport.admin.AdminIntegrationTest;

// 경고 복구 미리보기의 되살아날 팀 (AD-01 ⑦, E-42 · E-43): 아직 있는 팀만 이름순으로, 비공개 팀도 실명, 남는 팀이 없으면 삭제된 팀 표시.
// 목록과 같은 일괄 조회를 쓰므로 미리보기 응답과 목록 행이 같아야 한다
class AdminWarningRestorePreviewTeamsIntegrationTest extends AdminIntegrationTest {

	private static final LocalDate WEEK = JudgmentWeek.mondayOf(LocalDate.of(2044, 5, 12));
	private static final LocalDateTime DELETED_AT = LocalDateTime.of(2044, 2, 1, 9, 0);

	private static final String PREVIEW = """
			query($warningId: ID!) {
			  previewWarningRestore(warningId: $warningId) { warningId restoreTeams { id name } teamDeleted }
			}
			""";

	private static final String LIST = """
			query($after: String) {
			  adminDeletedWarnings(after: $after) { nextCursor items { warningId restoreTeams { id name } teamDeleted } }
			}
			""";

	@BeforeEach
	void fixClock() {
		clock.fixAt(WEEK.plusWeeks(8).atTime(10, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant());
	}

	@Test
	@DisplayName("남은 팀은 이름순이고 삭제된 팀은 빠지며 비공개 팀도 실명이다")
	void previewListsAliveTeamsByName() {
		long leader = activeMember();
		long gone = data.team(data.uniqueName("가"), true, leader, DELETED_AT);
		long privateTeam = data.team(data.uniqueName("다"), false, leader);
		long first = data.team(data.uniqueName("나"), true, leader);
		long last = data.team(data.uniqueName("라"), true, leader);
		long warning = deletedWarning(activeMember(), last, gone, privateTeam, first);

		JsonNode preview = preview(warning);

		assertThat(ids(preview.path("restoreTeams"))).containsExactly(first, privateTeam, last);
		assertThat(preview.path("restoreTeams").get(1).path("name").asText()).isEqualTo(teamName(privateTeam));
		assertThat(preview.path("teamDeleted").asBoolean()).isFalse();
		assertThat(preview.path("restoreTeams")).isEqualTo(listed(warning).path("restoreTeams"));
	}

	@Test
	@DisplayName("붙은 팀이 모두 삭제됐으면 되살아날 팀이 없고 삭제된 팀으로 표시한다")
	void previewMarksDeletedTeamWhenNoTeamRemains() {
		long leader = activeMember();
		long gone = data.team(data.uniqueName("삭"), true, leader, DELETED_AT);
		long warning = deletedWarning(activeMember(), gone);

		JsonNode preview = preview(warning);

		assertThat(preview.path("restoreTeams")).isEmpty();
		assertThat(preview.path("teamDeleted").asBoolean()).isTrue();
		assertThat(listed(warning).path("teamDeleted").asBoolean()).isTrue();
	}

	@Test
	@DisplayName("팀 카테고리가 없는 경고도 되살아날 팀이 없다")
	void previewWithoutCategory() {
		long warning = deletedWarning(activeMember());

		JsonNode preview = preview(warning);

		assertThat(preview.path("restoreTeams")).isEmpty();
		assertThat(preview.path("teamDeleted").asBoolean()).isTrue();
	}

	private long deletedWarning(long memberId, long... teams) {
		long judgmentId = data.fail(memberId, WEEK);
		long warningId = data.warning(memberId, WEEK, DELETED_AT, "KICKED");
		for (long teamId : teams) {
			data.judgmentTeam(judgmentId, teamId);
			data.warningTeam(warningId, teamId, DELETED_AT);
		}
		return warningId;
	}

	private JsonNode preview(long warningId) {
		return dataOf(asAdmin(PREVIEW, Map.of("warningId", Long.toString(warningId)))).path("previewWarningRestore");
	}

	private JsonNode listed(long warningId) {
		String cursor = null;
		do {
			Map<String, Object> variables = cursor == null ? Map.of() : Map.of("after", cursor);
			JsonNode page = dataOf(asAdmin(LIST, variables)).path("adminDeletedWarnings");
			for (JsonNode item : page.path("items")) {
				if (item.path("warningId").asLong() == warningId) {
					return item;
				}
			}
			cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText();
		} while (cursor != null);
		throw new AssertionError("삭제된 경고가 목록에 없어요: " + warningId);
	}

	private static List<Long> ids(JsonNode teams) {
		List<Long> ids = new ArrayList<>();
		teams.forEach(team -> ids.add(team.path("id").asLong()));
		return ids;
	}

	private String teamName(long teamId) {
		return jdbc.queryForObject("select name from team where id = ?", String.class, teamId);
	}

}
