package com.jandilog.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.jandilog.admin.domain.AdminActionType;
import com.jandilog.testsupport.auth.AuthIntegrationTest;

// admin_action_log.action ENUM에 KICK_MEMBER·DELETE_TEAM(V4), PROMOTE_ADMIN(V5)을 끝에 붙인 마이그레이션 확인 (DB명세서 1-16)
class AdminActionLogSchemaIntegrationTest extends AuthIntegrationTest {

	private static final List<String> EXPECTED = List.of("REJECT_MEMBER", "DELETE_POST", "DELETE_COMMENT",
			"FULFILL_PENALTY", "CORRECT_JUDGMENT", "RESTORE_WARNING", "KICK_MEMBER", "DELETE_TEAM", "PROMOTE_ADMIN");

	private String actionColumnType() {
		return jdbc.queryForObject("select column_type from information_schema.columns "
				+ "where table_schema = database() and table_name = 'admin_action_log' and column_name = 'action'",
				String.class);
	}

	@Test
	void action_컬럼은_기존_여섯_값의_이름과_순서를_지키고_끝에_새_값들이_붙는다() {
		String type = actionColumnType();

		assertThat(type).isEqualTo("enum(" + String.join(",", EXPECTED.stream().map(v -> "'" + v + "'").toList()) + ")");
	}

	@Test
	void AdminActionType의_값은_DB_ENUM과_이름과_순서가_같다() {
		assertThat(Arrays.stream(AdminActionType.values()).map(Enum::name).toList()).containsExactlyElementsOf(EXPECTED);
	}

	@Test
	void action_컬럼은_NOT_NULL이고_다른_컬럼은_그대로다() {
		List<Map<String, Object>> columns = jdbc.queryForList("select column_name, is_nullable from information_schema.columns "
				+ "where table_schema = database() and table_name = 'admin_action_log' order by ordinal_position");

		assertThat(columns).extracting(c -> c.get("column_name").toString())
				.containsExactly("id", "admin_id", "action", "target_type", "target_id", "reason", "created_at");
		assertThat(columns).filteredOn(c -> c.get("column_name").equals("action"))
				.extracting(c -> c.get("is_nullable")).containsExactly("NO");
		assertThat(columns).filteredOn(c -> c.get("column_name").equals("reason"))
				.extracting(c -> c.get("is_nullable")).containsExactly("YES");
	}

	@Test
	void Flyway가_V1부터_V5까지_성공으로_적용했다() {
		List<Map<String, Object>> rows = jdbc.queryForList(
				"select version, success from flyway_schema_history where version is not null order by installed_rank");

		assertThat(rows).extracting(r -> r.get("version").toString()).contains("1", "2", "3", "4", "5");
		assertThat(rows).allSatisfy(r -> assertThat(String.valueOf(r.get("success"))).isIn("true", "1"));
	}

	@Test
	void 새_값으로_기록을_넣을_수_있고_기존_값도_그대로_들어간다() {
		long admin = members.admin();

		for (String action : EXPECTED) {
			jdbc.update("insert into admin_action_log (admin_id, action, target_type, target_id, reason, created_at) "
					+ "values (?, ?, 'TEST', '1', null, ?)", admin, action, LocalDateTime.of(2026, 10, 19, 9, 0));
		}

		List<String> saved = jdbc.queryForList("select action from admin_action_log where admin_id = ? order by id",
				String.class, admin);
		assertThat(saved).containsExactlyElementsOf(EXPECTED);
	}

}
