package com.jandilog.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import com.jandilog.testsupport.board.BoardIntegrationTest;

// post_index.team_id의 team FK(V3, DB명세서 1-5)가 걸려 있고 팀 삭제 흐름은 막지 않는지 확인한다
class PostIndexTeamFkIntegrationTest extends BoardIntegrationTest {

	private String insertIndex(long authorId, Long teamId) {
		String mongoPostId = new ObjectId().toHexString();
		jdbc.update("insert into post_index (mongo_post_id, author_id, team_id, post_type, is_record, written_date, "
				+ "created_at) values (?, ?, ?, 'DEVLOG', true, ?, ?)", mongoPostId, authorId, teamId,
				LocalDate.of(2026, 10, 7), LocalDateTime.of(2026, 10, 7, 9, 0));
		return mongoPostId;
	}

	private Long indexTeamId(String mongoPostId) {
		return jdbc.queryForObject("select team_id from post_index where mongo_post_id = ?", Long.class, mongoPostId);
	}

	@Test
	void post_index에_team_FK가_걸려_있다() {
		List<String> referenced = jdbc.queryForList(
				"select referenced_table_name from information_schema.referential_constraints "
						+ "where constraint_schema = database() and table_name = 'post_index' "
						+ "and constraint_name = 'fk_pi_team'",
				String.class);

		assertThat(referenced).containsExactly("team");
	}

	@Test
	void 없는_팀_id를_색인에_넣으면_거부된다() {
		long author = activeMember();

		assertThatThrownBy(() -> insertIndex(author, 9_000_000_000L)).isInstanceOf(DataIntegrityViolationException.class);
		assertThat(indexCount(author)).isZero();
	}

	@Test
	void 실제_팀과_팀_없음은_색인에_들어간다() {
		long author = activeMember();
		long team = teams.create(author);

		String linked = insertIndex(author, team);
		String unlinked = insertIndex(author, null);

		assertThat(indexTeamId(linked)).isEqualTo(team);
		assertThat(indexTeamId(unlinked)).isNull();
	}

	@Test
	void 팀을_삭제하면_색인의_팀_연결만_끊기고_삭제는_FK에_막히지_않는다() {
		long leader = activeMember();
		long team = teams.create(leader);
		String linked = insertIndex(leader, team);

		teams.delete(team, leader);

		assertThat(indexTeamId(linked)).isNull();
		assertThat(jdbc.queryForObject("select deleted_at is not null from team where id = ?", Boolean.class, team))
				.isTrue();
	}

}
