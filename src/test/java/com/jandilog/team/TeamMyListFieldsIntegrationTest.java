package com.jandilog.team;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.jandilog.member.domain.MemberRole;
import com.jandilog.member.domain.MemberStatus;
import com.jandilog.team.support.TeamGql;
import com.jandilog.team.support.TeamIntegrationTest;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;

// 내 팀 목록(myTeams)을 Team 타입 전체 필드로 조회해 값까지 확인한다 (화면설계서 TM-01, 기능명세서 2장).
// 팀을 만들고 바꾸는 쪽도 GraphQL 요청으로 하고, 목록은 별도 요청으로 읽는다.
// 미인증·승인 대기·승인 거절의 myTeams 거부는 TeamAccessControlIntegrationTest가 이미 덮는다
class TeamMyListFieldsIntegrationTest extends TeamIntegrationTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

	// 백엔드 Team 타입의 필드를 하나도 빼지 않고 요청한다 (team.graphqls, team-board.graphqls)
	private static final List<String> FIELDS = List.of("id", "name", "description", "isPublic", "isLeader",
			"memberCount", "joinedAt", "createdAt", "leader", "weekSummary");
	private static final String MY_TEAMS_FULL = "{ myTeams { id name description isPublic isLeader memberCount "
			+ "joinedAt createdAt leader { id nickname githubLogin profileImageUrl } "
			+ "weekSummary { weekStart passCount failCount notYetCount } } }";

	private record Listed(long id, String name, String description, boolean isPublic, boolean isLeader,
			int memberCount, LocalDateTime joinedAt, LocalDateTime createdAt, long leaderId, String leaderNickname,
			String leaderLogin, String leaderImage, LocalDate weekStart) {
	}

	// ----- 요청 도우미 -----

	// 목록을 전체 필드로 읽고 필드가 빠짐없이 내려왔는지 확인한 뒤 값으로 바꾼다
	private List<Listed> myTeams(long viewer) {
		GraphQlResponse response = gql(viewer, MY_TEAMS_FULL);
		assertGraphQlOk(response);
		JsonNode teams = response.data().path("myTeams");
		assertThat(teams.isArray()).as(response.rawBody()).isTrue();
		List<Listed> result = new ArrayList<>();
		for (JsonNode team : teams) {
			for (String field : FIELDS) {
				assertThat(team.has(field)).as("필드 %s: %s", field, response.rawBody()).isTrue();
			}
			JsonNode leader = team.path("leader");
			result.add(new Listed(team.path("id").asLong(), team.path("name").asText(), textOrNull(team, "description"),
					team.path("isPublic").asBoolean(), team.path("isLeader").asBoolean(), team.path("memberCount").asInt(),
					LocalDateTime.parse(team.path("joinedAt").asText(), ISO),
					LocalDateTime.parse(team.path("createdAt").asText(), ISO), leader.path("id").asLong(),
					leader.path("nickname").asText(), leader.path("githubLogin").asText(),
					textOrNull(leader, "profileImageUrl"), LocalDate.parse(team.path("weekSummary").path("weekStart").asText())));
		}
		return result;
	}

	private static String textOrNull(JsonNode parent, String field) {
		JsonNode node = parent.path(field);
		return node.isNull() || node.isMissingNode() ? null : node.asText();
	}

	private static List<Long> ids(List<Listed> teams) {
		return teams.stream().map(Listed::id).toList();
	}

	private static Listed only(List<Listed> teams, long teamId) {
		return teams.stream().filter(t -> t.id() == teamId).findFirst()
				.orElseThrow(() -> new AssertionError("목록에 팀 " + teamId + " 없음: " + ids(teams)));
	}

	// createTeam 요청. 값이 null인 항목은 입력에서 뺀다
	private CreatedTeam create(long creator, String name, String description, Boolean isPublic) {
		Map<String, Object> input = new HashMap<>();
		input.put("name", name);
		if (description != null) {
			input.put("description", description);
		}
		if (isPublic != null) {
			input.put("isPublic", isPublic);
		}
		GraphQlResponse created = gql(creator, TeamGql.CREATE, Map.of("input", input));
		assertGraphQlOk(created);
		JsonNode payload = created.data().path("createTeam");
		long teamId = payload.path("team").path("id").asLong();
		trackTeam(teamId);
		return new CreatedTeam(teamId, payload.path("inviteCode").asText());
	}

	private CreatedTeam create(long creator, String name) {
		return create(creator, name, null, null);
	}

	private void join(long memberId, CreatedTeam team) {
		GraphQlResponse joined = gql(memberId, TeamGql.JOIN, Map.of("code", team.inviteCode()));
		assertGraphQlOk(joined);
		assertThat(joined.data().path("joinTeamByCode").path("id").asLong()).isEqualTo(team.id());
	}

	private void transfer(long leader, CreatedTeam team, long newLeader) {
		assertGraphQlOk(gql(leader, TeamGql.TRANSFER,
				Map.of("teamId", Long.toString(team.id()), "newLeaderId", Long.toString(newLeader))));
	}

	private void leave(long memberId, CreatedTeam team) {
		assertTrueResult(gql(memberId, TeamGql.LEAVE, Map.of("teamId", Long.toString(team.id()))), "leaveTeam");
	}

	private void kick(long leader, CreatedTeam team, long target) {
		assertTrueResult(gql(leader, TeamGql.KICK,
				Map.of("teamId", Long.toString(team.id()), "memberId", Long.toString(target))), "kickTeamMember");
	}

	private void delete(long leader, CreatedTeam team) {
		assertTrueResult(gql(leader, TeamGql.DELETE, Map.of("teamId", Long.toString(team.id()))), "deleteTeam");
	}

	private void assertTrueResult(GraphQlResponse response, String field) {
		assertGraphQlOk(response);
		assertThat(response.data().path(field).asBoolean()).as(response.rawBody()).isTrue();
	}

	private String name(String label) {
		return label + "-" + members.tag();
	}

	// ----- 1. 관리자 -----

	@Test
	void 관리자가_만든_팀이_본인_목록에_나오고_남의_팀은_안_나온다() {
		long admin = adminMember();
		long other = member();
		assertThat(members.find(admin)).hasValueSatisfying(row -> {
			assertThat(row.role()).isEqualTo(MemberRole.ADMIN);
			assertThat(row.status()).isEqualTo(MemberStatus.ACTIVE);
		});
		CreatedTeam othersTeam = create(other, name("남의팀"));

		CreatedTeam adminTeam = create(admin, name("관리자팀"), "관리자가 만든 팀", false);

		List<Listed> mine = myTeams(admin);
		assertThat(ids(mine)).containsExactly(adminTeam.id());
		Listed team = mine.get(0);
		assertThat(team.name()).isEqualTo(name("관리자팀"));
		assertThat(team.description()).isEqualTo("관리자가 만든 팀");
		assertThat(team.isPublic()).isFalse();
		assertThat(team.isLeader()).isTrue();
		assertThat(team.memberCount()).isEqualTo(1);
		assertThat(team.leaderId()).isEqualTo(admin);
		assertThat(team.leaderLogin()).isEqualTo(loginOf(admin));
		// 목록은 소속 기준이라 관리자라도 남의 팀은 보이지 않고, 관리자 팀도 남에게 보이지 않는다
		assertThat(ids(myTeams(other))).containsExactly(othersTeam.id());

		// 초대코드로 참가하면 일반 팀원으로 목록에 이어서 나온다
		join(admin, othersTeam);
		List<Listed> joined = myTeams(admin);
		assertThat(ids(joined)).containsExactly(adminTeam.id(), othersTeam.id());
		assertThat(only(joined, othersTeam.id()).isLeader()).isFalse();
		assertThat(only(joined, othersTeam.id()).memberCount()).isEqualTo(2);
	}

	// ----- 2. 팀 여러 개 -----

	@Test
	void 팀_6개를_가진_회원의_목록에는_6개가_참가한_순서대로_모두_나온다() {
		long me = member();
		long other = member();
		// 남의 팀은 미리 만들어 두고, 내가 만들기(1·3·6번째)와 초대코드 참가(2·4·5번째)를 번갈아 한다
		CreatedTeam joined2 = create(other, name("남팀2"), "둘째로 참가", true);
		CreatedTeam joined4 = create(other, name("남팀4"), null, false);
		CreatedTeam joined5 = create(other, name("남팀5"));
		CreatedTeam own1 = create(me, name("내팀1"));
		join(me, joined2);
		CreatedTeam own3 = create(me, name("내팀3"), null, false);
		join(me, joined4);
		join(me, joined5);
		CreatedTeam own6 = create(me, name("내팀6"), "여섯째", true);

		List<Listed> teams = myTeams(me);

		// 팀을 만든 때든 참가한 때든 내가 소속된 순서대로
		assertThat(ids(teams)).containsExactly(own1.id(), joined2.id(), own3.id(), joined4.id(), joined5.id(), own6.id());
		assertThat(teams).extracting(Listed::isLeader).containsExactly(true, false, true, false, false, true);
		assertThat(teams).extracting(Listed::memberCount).containsExactly(1, 2, 1, 2, 2, 1);
		assertThat(teams).extracting(Listed::leaderId).containsExactly(me, other, me, other, other, me);
		// 비공개 팀도 본인에게는 실제 이름
		assertThat(teams).extracting(Listed::name).containsExactly(name("내팀1"), name("남팀2"), name("내팀3"),
				name("남팀4"), name("남팀5"), name("내팀6"));
		assertThat(teams).extracting(Listed::isPublic).containsExactly(true, true, false, false, true, true);
		assertThat(teams).extracting(Listed::description).containsExactly(null, "둘째로 참가", null, null, null, "여섯째");
		// 참가 시각이 오래된 순으로 늘어난다
		List<LocalDateTime> joinedAt = teams.stream().map(Listed::joinedAt).toList();
		assertThat(joinedAt).isSorted();
		// 다른 회원의 목록은 영향받지 않는다
		assertThat(ids(myTeams(other))).containsExactly(joined2.id(), joined4.id(), joined5.id());
	}

	// ----- 3. 팀장 위임 -----

	@Test
	void 팀장을_넘기면_이전_팀장의_목록에서_isLeader가_false이고_leader가_새_팀장이다() {
		long oldLeader = member();
		long newLeader = member();
		long bystander = member();
		CreatedTeam team = create(oldLeader, name("위임팀"));
		CreatedTeam kept = create(oldLeader, name("유지팀"));
		join(newLeader, team);
		join(bystander, team);
		Listed before = only(myTeams(oldLeader), team.id());
		assertThat(before.isLeader()).isTrue();
		assertThat(before.leaderId()).isEqualTo(oldLeader);

		transfer(oldLeader, team, newLeader);

		List<Listed> afterOld = myTeams(oldLeader);
		assertThat(ids(afterOld)).containsExactly(team.id(), kept.id());
		Listed handed = only(afterOld, team.id());
		assertThat(handed.isLeader()).isFalse();
		assertThat(handed.leaderId()).isEqualTo(newLeader);
		assertThat(handed.leaderLogin()).isEqualTo(loginOf(newLeader));
		assertThat(handed.leaderNickname()).isNotBlank();
		assertThat(handed.memberCount()).isEqualTo(3);
		// 소속 시각은 위임으로 바뀌지 않는다
		assertThat(handed.joinedAt()).isEqualTo(before.joinedAt());
		// 다른 팀의 팀장은 그대로
		assertThat(only(afterOld, kept.id()).isLeader()).isTrue();
		assertThat(only(afterOld, kept.id()).leaderId()).isEqualTo(oldLeader);

		Listed fromNew = only(myTeams(newLeader), team.id());
		assertThat(fromNew.isLeader()).isTrue();
		assertThat(fromNew.leaderId()).isEqualTo(newLeader);
		Listed fromBystander = only(myTeams(bystander), team.id());
		assertThat(fromBystander.isLeader()).isFalse();
		assertThat(fromBystander.leaderId()).isEqualTo(newLeader);
	}

	// ----- 4. 삭제·탈퇴 -----

	@Test
	void 자진_탈퇴하면_그_팀만_목록에서_빠지고_다른_팀은_그대로다() {
		long leader = member();
		long leaver = member();
		CreatedTeam teamA = create(leader, name("탈퇴A"));
		CreatedTeam teamB = create(leader, name("탈퇴B"), "남는 팀", false);
		join(leaver, teamA);
		join(leaver, teamB);
		Listed bBefore = only(myTeams(leaver), teamB.id());
		assertThat(ids(myTeams(leaver))).containsExactly(teamA.id(), teamB.id());

		leave(leaver, teamA);

		List<Listed> after = myTeams(leaver);
		assertThat(ids(after)).containsExactly(teamB.id());
		assertThat(after.get(0)).isEqualTo(bBefore);
		// 남은 팀원 쪽에는 인원수만 줄어서 그대로 보인다
		List<Listed> leaderView = myTeams(leader);
		assertThat(ids(leaderView)).containsExactly(teamA.id(), teamB.id());
		assertThat(only(leaderView, teamA.id()).memberCount()).isEqualTo(1);
		assertThat(only(leaderView, teamB.id()).memberCount()).isEqualTo(2);
	}

	@Test
	void 팀을_삭제하면_팀장과_팀원_목록에서_모두_빠지고_다른_팀은_그대로다() {
		long leader = member();
		long mate = member();
		CreatedTeam doomed = create(leader, name("삭제팀"));
		CreatedTeam survivor = create(leader, name("남는팀"));
		join(mate, doomed);
		join(mate, survivor);
		assertThat(ids(myTeams(leader))).containsExactly(doomed.id(), survivor.id());
		assertThat(ids(myTeams(mate))).containsExactly(doomed.id(), survivor.id());

		delete(leader, doomed);

		List<Listed> leaderView = myTeams(leader);
		List<Listed> mateView = myTeams(mate);
		assertThat(ids(leaderView)).containsExactly(survivor.id());
		assertThat(ids(mateView)).containsExactly(survivor.id());
		assertThat(leaderView.get(0).isLeader()).isTrue();
		assertThat(leaderView.get(0).memberCount()).isEqualTo(2);
		assertThat(mateView.get(0).isLeader()).isFalse();
		assertThat(mateView.get(0).leaderId()).isEqualTo(leader);
	}

	@Test
	void 혼자_있던_팀을_삭제하면_목록이_빈_배열이다() {
		long leader = member();
		CreatedTeam team = create(leader, name("혼자팀"));
		assertThat(ids(myTeams(leader))).containsExactly(team.id());

		delete(leader, team);

		assertThat(myTeams(leader)).isEmpty();
	}

	@Test
	void 나갔다가_초대코드로_다시_참가하면_목록에_한_번만_나온다() {
		long leader = member();
		long rejoiner = member();
		CreatedTeam team = create(leader, name("재참가팀"));
		join(rejoiner, team);
		leave(rejoiner, team);
		assertThat(myTeams(rejoiner)).isEmpty();

		join(rejoiner, team);

		List<Listed> teams = myTeams(rejoiner);
		assertThat(ids(teams)).containsExactly(team.id());
		assertThat(teams.get(0).memberCount()).isEqualTo(2);
		assertThat(teams.get(0).isLeader()).isFalse();
	}

	// ----- 5. 프로필 이미지 -----

	@Test
	void 프로필_이미지가_없는_팀장의_팀도_목록_전체가_깨지지_않는다() {
		long viewer = member();
		long noImageLeader = member();
		long imageLeader = member();
		String image = "https://example.com/profile/" + members.tag() + ".png";
		jdbc.update("update member set profile_image_url = ? where id = ?", image, imageLeader);
		CreatedTeam byViewer = create(viewer, name("내팀"));
		CreatedTeam noImage = create(noImageLeader, name("이미지없음"));
		CreatedTeam withImage = create(imageLeader, name("이미지있음"));
		join(viewer, noImage);
		join(viewer, withImage);

		GraphQlResponse response = gql(viewer, MY_TEAMS_FULL);
		assertGraphQlOk(response);
		List<Listed> teams = myTeams(viewer);

		assertThat(ids(teams)).containsExactly(byViewer.id(), noImage.id(), withImage.id());
		assertThat(teams).extracting(Listed::leaderImage).containsExactly(null, null, image);
		JsonNode raw = response.data().path("myTeams");
		assertThat(raw.get(1).path("leader").has("profileImageUrl")).isTrue();
		assertThat(raw.get(1).path("leader").path("profileImageUrl").isNull()).isTrue();
		// 이미지가 없는 팀장의 나머지 값은 정상으로 채워진다
		assertThat(teams.get(1).leaderId()).isEqualTo(noImageLeader);
		assertThat(teams.get(1).leaderLogin()).isEqualTo(loginOf(noImageLeader));
		assertThat(teams.get(1).leaderNickname()).isNotBlank();
	}

	@Test
	void 이미지가_없는_본인이_팀장이어도_본인_목록이_나온다() {
		long leader = member();
		CreatedTeam team = create(leader, name("무이미지팀"));

		List<Listed> teams = myTeams(leader);

		assertThat(ids(teams)).containsExactly(team.id());
		assertThat(teams.get(0).leaderImage()).isNull();
		assertThat(teams.get(0).leaderId()).isEqualTo(leader);
	}

	// ----- 6. 필드 값 -----

	@Test
	void memberCount는_참가_탈퇴_추방에_따라_바뀐다() {
		long leader = member();
		long first = member();
		long second = member();
		CreatedTeam team = create(leader, name("인원수팀"));
		assertThat(only(myTeams(leader), team.id()).memberCount()).isEqualTo(1);

		join(first, team);
		assertThat(only(myTeams(leader), team.id()).memberCount()).isEqualTo(2);
		assertThat(only(myTeams(first), team.id()).memberCount()).isEqualTo(2);

		join(second, team);
		assertThat(only(myTeams(leader), team.id()).memberCount()).isEqualTo(3);
		assertThat(only(myTeams(second), team.id()).memberCount()).isEqualTo(3);

		leave(first, team);
		assertThat(only(myTeams(leader), team.id()).memberCount()).isEqualTo(2);
		assertThat(only(myTeams(second), team.id()).memberCount()).isEqualTo(2);

		kick(leader, team, second);
		assertThat(only(myTeams(leader), team.id()).memberCount()).isEqualTo(1);
		assertThat(myTeams(second)).isEmpty();
	}

	@Test
	void 팀장_팀원_모두_isLeader_description_joinedAt_createdAt이_채워진다() {
		long leader = member();
		long mate = member();
		LocalDateTime before = LocalDateTime.now(KST).truncatedTo(ChronoUnit.SECONDS);
		CreatedTeam withDescription = create(leader, name("소개있음"), "팀 소개 문장", true);
		CreatedTeam withoutDescription = create(leader, name("소개없음"));
		join(mate, withDescription);
		LocalDateTime after = LocalDateTime.now(KST).plusSeconds(1);

		List<Listed> leaderView = myTeams(leader);
		List<Listed> mateView = myTeams(mate);

		Listed described = only(leaderView, withDescription.id());
		assertThat(described.description()).isEqualTo("팀 소개 문장");
		assertThat(described.isPublic()).isTrue();
		assertThat(described.isLeader()).isTrue();
		// 소개를 안 적은 팀은 null, 공개 설정을 안 적으면 공개
		Listed plain = only(leaderView, withoutDescription.id());
		assertThat(plain.description()).isNull();
		assertThat(plain.isPublic()).isTrue();

		// 팀장은 팀을 만든 시각에 참가한 것으로 적힌다
		assertThat(described.createdAt()).isBetween(before, after);
		assertThat(described.joinedAt()).isEqualTo(described.createdAt());
		assertThat(plain.joinedAt()).isEqualTo(plain.createdAt());

		// 팀원의 참가 시각은 팀을 만든 이후, createdAt은 팀장과 같은 값
		Listed joined = only(mateView, withDescription.id());
		assertThat(joined.isLeader()).isFalse();
		assertThat(joined.description()).isEqualTo("팀 소개 문장");
		assertThat(joined.createdAt()).isEqualTo(described.createdAt());
		assertThat(joined.joinedAt()).isBetween(before, after).isAfterOrEqualTo(joined.createdAt());
		assertThat(joined.leaderId()).isEqualTo(leader);
		assertThat(joined.leaderLogin()).isEqualTo(loginOf(leader));

		// 같이 요청한 이번 주 요약은 월요일 날짜를 돌려준다
		assertThat(described.weekStart().getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
		assertThat(described.weekStart()).isBeforeOrEqualTo(LocalDate.now(KST));
	}

}
