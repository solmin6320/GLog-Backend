package com.jandilog.post;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.testsupport.teampost.TeamPostIntegrationTest;

// 팀장의 팀 글·댓글 관리 정상 경로 (기능명세서 2장, BD-03·BD-04, TM-06 ⑧, DB명세서 4-6).
// 수정은 작성자 본인과 그 글이 연결된 팀의 팀장, 삭제는 작성자 본인뿐이다
class TeamPostManageIntegrationTest extends TeamPostIntegrationTest {

	private long leader;
	private long member;
	private long peer;
	private long outsider;
	private long admin;
	private long teamId;

	@BeforeEach
	void setUpTeam() {
		leader = activeMember();
		member = activeMember();
		peer = activeMember();
		outsider = activeMember();
		admin = adminMember();
		teamId = createTeam(leader);
		joinTeam(teamId, member);
		joinTeam(teamId, peer);
	}

	// ----- 수정 -----

	@Test
	void 팀장이_팀원의_글을_고치면_내용과_기록글_여부가_바뀌고_작성자_종류_작성일_팀_연결은_그대로다() {
		String postId = newPost(member, troubleshooting("원래 제목", "문제", "", "", teamId));
		assertThat(indexRow(postId)).hasValueSatisfying(row -> assertThat(row.record()).isFalse());
		String writtenDate = mongoPost(postId).getString("writtenDate");

		JsonNode edited = ok(gql(leader, UPDATE_POST, vars("id", postId, "input",
				troubleshootingEdit("팀장이 고친 제목", "문제", "원인", "해결", null)))).data().path("updatePost");

		assertThat(edited.path("title").asText()).isEqualTo("팀장이 고친 제목");
		assertThat(edited.path("sections").path("cause").asText()).isEqualTo("원인");
		assertThat(edited.path("isRecord").asBoolean()).isTrue();
		assertThat(edited.path("authorId").asText()).isEqualTo(Long.toString(member));
		assertThat(edited.path("type").asText()).isEqualTo("TROUBLESHOOTING");
		assertThat(edited.path("teamId").asText()).isEqualTo(Long.toString(teamId));
		assertThat(edited.path("writtenDate").asText()).isEqualTo(writtenDate);

		assertThat(mongoPost(postId).getString("title")).isEqualTo("팀장이 고친 제목");
		assertThat(mongoPost(postId).getLong("teamId")).isEqualTo(teamId);
		assertThat(indexRow(postId)).hasValueSatisfying(row -> {
			assertThat(row.authorId()).isEqualTo(member);
			assertThat(row.teamId()).isEqualTo(teamId);
			assertThat(row.record()).isTrue();
		});
	}

	@Test
	void 팀장이_다른_팀_id를_보내도_무시되고_기존_팀_연결이_유지된다() {
		long otherTeam = createTeam(leader);
		String postId = newPost(member, troubleshooting("제목", "문제", "원인", "해결", teamId));

		JsonNode edited = ok(gql(leader, UPDATE_POST, vars("id", postId, "input",
				troubleshootingEdit("고친 제목", "문제", "원인", "해결", otherTeam)))).data().path("updatePost");

		assertThat(edited.path("teamId").asText()).isEqualTo(Long.toString(teamId));
		assertThat(mongoPost(postId).getLong("teamId")).isEqualTo(teamId);
		assertThat(indexRow(postId)).hasValueSatisfying(row -> assertThat(row.teamId()).isEqualTo(teamId));
	}

	@Test
	void 팀장이_팀원의_댓글을_고치면_내용만_바뀌고_작성자는_그대로다() {
		String postId = newPost(member, troubleshooting("제목", "문제", "원인", "해결", teamId));
		String commentId = newComment(peer, postId, "원래 댓글");

		JsonNode edited = ok(gql(leader, UPDATE_COMMENT, vars("id", commentId, "content", "팀장이 고친 댓글"))).data()
				.path("updateComment");

		assertThat(edited.path("content").asText()).isEqualTo("팀장이 고친 댓글");
		assertThat(edited.path("authorId").asText()).isEqualTo(Long.toString(peer));
		assertThat(mongoComment(commentId).getString("content")).isEqualTo("팀장이 고친 댓글");
		assertThat(mongoComment(commentId).getLong("authorId")).isEqualTo(peer);
	}

	@Test
	void 팀장이_아닌_팀원은_남의_글과_댓글을_고칠_수_없다() {
		String postId = newPost(member, troubleshooting("원래 제목", "문제", "원인", "해결", teamId));
		String commentId = newComment(member, postId, "원래 댓글");

		assertError(gql(peer, UPDATE_POST, vars("id", postId, "input",
				troubleshootingEdit("바꿔치기", "문제", "원인", "해결", null))), ErrorCode.FORBIDDEN.name());
		assertError(gql(peer, UPDATE_COMMENT, vars("id", commentId, "content", "바꿔치기")), ErrorCode.FORBIDDEN.name());

		assertThat(mongoPost(postId).getString("title")).isEqualTo("원래 제목");
		assertThat(mongoComment(commentId).getString("content")).isEqualTo("원래 댓글");
	}

	@Test
	void 팀에_연결되지_않은_글과_댓글은_팀장도_고칠_수_없다() {
		String postId = newPost(member, troubleshooting("원래 제목", "문제", "원인", "해결", null));
		String commentId = newComment(member, postId, "원래 댓글");

		assertError(gql(leader, UPDATE_POST, vars("id", postId, "input",
				troubleshootingEdit("바꿔치기", "문제", "원인", "해결", null))), ErrorCode.FORBIDDEN.name());
		assertError(gql(leader, UPDATE_COMMENT, vars("id", commentId, "content", "바꿔치기")), ErrorCode.FORBIDDEN.name());

		assertThat(mongoPost(postId).getString("title")).isEqualTo("원래 제목");
		assertThat(mongoComment(commentId).getString("content")).isEqualTo("원래 댓글");
	}

	// ----- 삭제 -----

	@Test
	void 팀장은_팀원의_글과_댓글을_삭제할_수_없다() {
		String postId = newPost(member, troubleshooting("제목", "문제", "원인", "해결", teamId));
		String commentId = newComment(member, postId, "댓글");

		assertError(gql(leader, DELETE_COMMENT, vars("id", commentId)), ErrorCode.FORBIDDEN.name());
		assertError(gql(leader, DELETE_POST, vars("id", postId)), ErrorCode.FORBIDDEN.name());

		assertThat(mongoPost(postId).get("deletedAt")).isNull();
		assertThat(mongoComment(commentId).get("deletedAt")).isNull();
		assertThat(indexRow(postId)).hasValueSatisfying(row -> assertThat(row.deletedAt()).isNull());
	}

	// ----- 팀 글 관리 목록 (TM-06 ⑧) -----

	@Test
	void 팀장은_팀에_연결된_글과_댓글을_한_건씩_본다() {
		String linked = newPost(member, troubleshooting("팀 글", "문제", "원인", "해결", teamId));
		String linkedComment = newComment(peer, linked, "팀 글의 댓글");
		String unlinked = newPost(member, troubleshooting("팀 없는 글", "문제", "원인", "해결", null));
		newComment(peer, unlinked, "팀 없는 글의 댓글");

		JsonNode posts = ok(gql(leader, TEAM_POSTS, vars("teamId", Long.toString(teamId), "after", null))).data()
				.path("teamPosts");
		assertThat(ids(posts.path("items"))).containsExactly(linked);
		assertThat(posts.path("items").path(0).path("commentCount").asInt()).isEqualTo(1);
		assertThat(posts.path("nextCursor").isNull()).isTrue();

		JsonNode comments = ok(gql(leader, TEAM_COMMENTS, vars("teamId", Long.toString(teamId), "after", null))).data()
				.path("teamComments");
		assertThat(ids(comments.path("items"))).containsExactly(linkedComment);
		assertThat(comments.path("items").path(0).path("postId").asText()).isEqualTo(linked);
		assertThat(comments.path("items").path(0).path("author").path("id").asText()).isEqualTo(Long.toString(peer));
		assertThat(comments.path("nextCursor").isNull()).isTrue();
	}

	@Test
	void 그_팀의_팀장이_아니면_목록을_볼_수_없다() {
		newPost(member, troubleshooting("팀 글", "문제", "원인", "해결", teamId));
		String team = Long.toString(teamId);

		// 팀원·팀 밖 회원·관리자 모두 같다
		for (long viewer : new long[] { member, outsider, admin }) {
			assertError(gql(viewer, TEAM_POSTS, vars("teamId", team, "after", null)), ErrorCode.FORBIDDEN.name());
			assertError(gql(viewer, TEAM_COMMENTS, vars("teamId", team, "after", null)), ErrorCode.FORBIDDEN.name());
		}
	}

}
