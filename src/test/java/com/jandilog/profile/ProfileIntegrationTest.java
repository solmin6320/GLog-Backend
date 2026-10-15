package com.jandilog.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.databind.JsonNode;
import com.jandilog.judgment.domain.HoldReason;
import com.jandilog.judgment.domain.JudgmentWeek;
import com.jandilog.judgment.service.GrassCacheService;
import com.jandilog.post.domain.PostType;
import com.jandilog.post.dto.CreatePostInput;
import com.jandilog.post.dto.PostSectionsInput;
import com.jandilog.post.service.CommentService;
import com.jandilog.post.service.PostService;
import com.jandilog.testsupport.admin.AdminIntegrationTest;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;

// 프로필 조회: 기본 정보, 글 · 댓글 수(MongoDB), 잔디 캐시, 경고 합계, 공개 팀만 보이는 소속 팀 (기능명세서 8장, PR-01)
class ProfileIntegrationTest extends AdminIntegrationTest {

	private static final LocalDate WEEK = JudgmentWeek.mondayOf(LocalDate.of(2045, 3, 6));

	private static final String PROFILE = """
			query($id: ID!) {
			  profile(memberId: $id) {
			    id nickname githubLogin profileImageUrl isMine postCount commentCount
			    grass { from to fetchedAt days { date count } }
			    warning { onHold warningCount penaltyTarget }
			    teams { id name }
			  }
			}
			""";

	@Autowired
	private PostService postService;
	@Autowired
	private CommentService commentService;
	@Autowired
	private GrassCacheService grassCacheService;

	@Test
	@DisplayName("다른 회원의 프로필은 기본 이미지와 isMine=false로 보이고 내 프로필은 isMine=true다")
	void showsBasicInfoForSelfAndOthers() {
		long viewer = activeMember();
		long target = activeMember();
		var targetRow = members.find(target).orElseThrow();

		JsonNode other = profile(viewer, target);
		assertThat(other.path("id").asLong()).isEqualTo(target);
		assertThat(other.path("nickname").asText()).isEqualTo(targetRow.nickname());
		assertThat(other.path("githubLogin").asText()).isEqualTo(targetRow.githubLogin());
		assertThat(other.path("profileImageUrl").isNull()).isTrue();
		assertThat(other.path("isMine").asBoolean()).isFalse();

		assertThat(profile(target, target).path("isMine").asBoolean()).isTrue();
		// 관리자도 같은 화면을 본다
		assertThat(dataOf(asAdmin(PROFILE, Map.of("id", Long.toString(target)))).path("profile").path("id").asLong())
				.isEqualTo(target);
	}

	@Test
	@DisplayName("글 수와 댓글 수는 삭제되지 않은 것만 MongoDB에서 센다")
	void countsOnlyAlivePostsAndComments() {
		long viewer = activeMember();
		long writer = activeMember();
		String kept = createPost(writer);
		String removed = createPost(writer);
		commentService.create(viewer, kept, "다른 사람 댓글");
		commentService.create(writer, kept, "내 댓글 1");
		String deletedComment = commentService.create(writer, kept, "내 댓글 2").id();
		postService.delete(writer, removed);
		commentService.delete(writer, deletedComment);

		JsonNode profile = profile(viewer, writer);

		assertThat(profile.path("postCount").asInt()).isEqualTo(1);
		assertThat(profile.path("commentCount").asInt()).isEqualTo(1);
		JsonNode empty = profile(viewer, viewer);
		assertThat(empty.path("postCount").asInt()).isZero();
		assertThat(empty.path("commentCount").asInt()).isEqualTo(1);
	}

	@Test
	@DisplayName("소속 팀은 지금 속한 공개 팀만 보이고 비공개 · 떠난 · 삭제된 팀은 빠진다")
	void showsOnlyCurrentPublicTeams() {
		long viewer = activeMember();
		long member = activeMember();
		long leader = activeMember();
		LocalDateTime joined = LocalDateTime.of(2045, 1, 1, 9, 0);
		long publicTeam = data.team(data.uniqueName("공개팀"), true, leader);
		long privateTeam = data.team(data.uniqueName("비공개팀"), false, leader);
		long leftTeam = data.team(data.uniqueName("떠난팀"), true, leader);
		long deletedTeam = data.team(data.uniqueName("삭제팀"), true, leader, joined.plusDays(10));
		data.teamMember(publicTeam, member, joined, null);
		data.teamMember(privateTeam, member, joined, null);
		data.teamMember(leftTeam, member, joined, joined.plusDays(5));
		data.teamMember(deletedTeam, member, joined, null);

		JsonNode teams = profile(viewer, member).path("teams");

		assertThat(teams).hasSize(1);
		assertThat(teams.get(0).path("id").asLong()).isEqualTo(publicTeam);
		assertThat(teams.get(0).path("name").asText()).isNotBlank();
		// 팀이 없으면 빈 목록이다
		assertThat(profile(viewer, viewer).path("teams")).isEmpty();
	}

	@Test
	@DisplayName("경고 합계는 판정 이력에서 다시 계산하고 3개면 벌칙 대상, 보류가 남으면 계산하지 않는다")
	void warningCardIsRecalculated() {
		long viewer = activeMember();
		long leader = activeMember();
		long team = data.team(data.uniqueName("경고팀"), true, leader);
		long two = activeMember();
		data.failWithWarning(two, WEEK.minusWeeks(1), team);
		data.failWithWarning(two, WEEK, team);
		long three = activeMember();
		data.failWithWarning(three, WEEK.minusWeeks(2), team);
		data.failWithWarning(three, WEEK.minusWeeks(1), team);
		data.failWithWarning(three, WEEK, team);
		long held = activeMember();
		data.failWithWarning(held, WEEK.minusWeeks(1), team);
		data.hold(held, WEEK, "API_ERROR", 3);

		JsonNode twoWarning = profile(viewer, two).path("warning");
		assertThat(twoWarning.path("onHold").asBoolean()).isFalse();
		assertThat(twoWarning.path("warningCount").asInt()).isEqualTo(2);
		assertThat(twoWarning.path("penaltyTarget").asBoolean()).isFalse();

		JsonNode threeWarning = profile(viewer, three).path("warning");
		assertThat(threeWarning.path("warningCount").asInt()).isEqualTo(3);
		assertThat(threeWarning.path("penaltyTarget").asBoolean()).isTrue();

		JsonNode heldWarning = profile(viewer, held).path("warning");
		assertThat(heldWarning.path("onHold").asBoolean()).isTrue();
		assertThat(heldWarning.path("warningCount").isNull()).isTrue();
		assertThat(heldWarning.path("penaltyTarget").isNull()).isTrue();

		JsonNode none = profile(viewer, viewer).path("warning");
		assertThat(none.path("warningCount").asInt()).isZero();
		assertThat(none.path("penaltyTarget").asBoolean()).isFalse();
	}

	@Test
	@DisplayName("잔디는 캐시가 있으면 갱신 시각과 함께 보이고 없으면 null이며 GitHub를 부르지 않는다")
	void grassComesFromCacheOnly() {
		long viewer = activeMember();
		long cached = activeMember();
		long notCached = activeMember();
		grassCache(cached);
		grassClient.failFor(members.find(notCached).orElseThrow().githubLogin(), HoldReason.API_ERROR);
		int callsBefore = grassClient.calls();

		JsonNode withCache = profile(viewer, cached).path("grass");
		assertThat(withCache.path("fetchedAt").asText()).isNotBlank();
		assertThat(withCache.path("days")).isNotEmpty();
		assertThat(withCache.path("days").get(0).path("date").asText()).isEqualTo(withCache.path("from").asText());
		assertThat(withCache.path("days").get(0).path("count").asInt()).isEqualTo(1);

		GraphQlResponse withoutCache = asMember(viewer, PROFILE, Map.of("id", Long.toString(notCached)));
		assertThat(withoutCache.hasErrors()).isFalse();
		assertThat(withoutCache.data().path("profile").path("grass").isNull()).isTrue();
		// 프로필 조회는 GitHub 클라이언트를 한 번도 부르지 않았다
		assertThat(grassClient.calls()).isEqualTo(callsBefore);
	}

	@Test
	@DisplayName("승인되지 않은 회원이나 없는 회원의 프로필은 없는 내용으로 본다")
	void unknownOrNotApprovedProfilesAreNotFound() {
		long viewer = activeMember();
		long pending = pendingMember();
		long rejected = data.track(members.rejected());

		assertThat(asMember(viewer, PROFILE, Map.of("id", Long.toString(pending))).errorCode()).isEqualTo("NOT_FOUND");
		assertThat(asMember(viewer, PROFILE, Map.of("id", Long.toString(rejected))).errorCode()).isEqualTo("NOT_FOUND");
		assertThat(asMember(viewer, PROFILE, Map.of("id", "999999999999")).errorCode()).isEqualTo("NOT_FOUND");
		assertThat(asMember(viewer, PROFILE, Map.of("id", "abc")).errorCode()).isEqualTo("NOT_FOUND");
	}

	@Test
	@DisplayName("승인된 회원만 프로필을 볼 수 있다")
	void onlyApprovedMembersCanView() {
		long target = activeMember();
		long pending = pendingMember();

		assertThat(asMember(pending, PROFILE, Map.of("id", Long.toString(target))).errorCode())
				.isEqualTo("ACCOUNT_PENDING");
		assertThat(graphQl.post(null, PROFILE, Map.of("id", Long.toString(target))).errorCode())
				.isEqualTo("UNAUTHENTICATED");
	}

	private JsonNode profile(long viewerId, long targetId) {
		return dataOf(asMember(viewerId, PROFILE, Map.of("id", Long.toString(targetId)))).path("profile");
	}

	private String createPost(long authorId) {
		return postService.create(authorId, new CreatePostInput(PostType.DEVLOG, data.uniqueName("프로필글"),
				new PostSectionsInput(null, null, null, "했다", "배웠다"), List.of(), new ArrayList<>(), null)).id();
	}

	// 스텁 GitHub 응답으로 오늘 캐시를 채운다 (회원 로그인 기준)
	private void grassCache(long memberId) {
		grassCacheService.refresh(memberId, members.find(memberId).orElseThrow().githubLogin());
	}

}
