package com.jandilog.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.jandilog.admin.domain.AdminActionLog;
import com.jandilog.admin.repository.AdminActionLogRepository;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.post.domain.Post;
import com.jandilog.team.service.TeamBanService;
import com.jandilog.team.support.TeamGql;
import com.jandilog.team.support.TeamIntegrationTest;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;

// 관리자 권한의 추방·팀 삭제를 admin_action_log 기록 뒤에서 실패시켜, 기록·소속·경고·차단 키·글 연결이 함께 되돌아가는지 확인한다.
// 기록 단계 앞에서 거절되는 경우는 TeamAdminAccessIntegrationTest가, 롤백 표시만 한 트랜잭션은 거기서 이미 본다
class TeamAdminActionLogRollbackIntegrationTest extends TeamIntegrationTest {

	private static final LocalDate WEEK_1 = LocalDate.of(2026, 9, 7);

	@MockitoSpyBean
	private TeamBanService banSpy;
	@MockitoSpyBean
	private MongoTemplate mongoSpy;
	@MockitoSpyBean
	private AdminActionLogRepository logRepositorySpy;

	private long leader;
	private long mate;
	private long admin;
	private CreatedTeam team;
	private long warningId;
	private String postId;

	@BeforeEach
	void setUpTeam() {
		leader = member();
		mate = member();
		admin = adminMember();
		team = newTeam(leader, "롤백 확인 팀", null, true);
		joinService.joinByCode(mate, team.inviteCode());
		warningId = warnings.warning(mate, WEEK_1, team.id());
		postId = newPost(mate, team);
	}

	private List<Map<String, Object>> adminLogs() {
		return jdbc.queryForList("select * from admin_action_log where admin_id = ? order by id", admin);
	}

	private GraphQlResponse kick() {
		return gql(admin, TeamGql.KICK, Map.of("teamId", Long.toString(team.id()), "memberId", Long.toString(mate)));
	}

	private GraphQlResponse delete() {
		return gql(admin, TeamGql.DELETE, Map.of("teamId", Long.toString(team.id())));
	}

	private void assertInternalError(GraphQlResponse response) {
		assertGraphQlError(response, ErrorCode.INTERNAL_ERROR);
		assertThat(response.rawBody()).doesNotContain("장애 흉내");
	}

	// 추방·삭제 전 상태 그대로인가
	private void assertNothingChanged() {
		assertThat(adminLogs()).isEmpty();
		assertThat(teamRow(team.id()).get("deleted_at")).isNull();
		assertThat(isActiveMember(team.id(), mate)).isTrue();
		assertThat(isActiveMember(team.id(), leader)).isTrue();
		assertThat(membershipRows(team.id(), mate)).hasSize(1);
		assertThat(membershipRows(team.id(), mate).get(0).get("left_at")).isNull();
		assertThat(redis.hasKey(banKey(team.id(), mate))).isFalse();
		assertThat(warnings.warning(warningId).alive()).isTrue();
		assertThat(warnings.category(warningId, team.id()).removed()).isFalse();
		assertThat(postService.get(postId).teamId()).isEqualTo(team.id());
	}

	// ----- 기록 뒤 단계의 실패 -----

	@Test
	void 관리자_추방의_차단_키를_쓰고_난_뒤_실패하면_기록_소속_경고_차단_키가_모두_되돌아간다() {
		doAnswer(call -> {
			call.callRealMethod();
			throw new IllegalStateException("레디스 장애 흉내");
		}).when(banSpy).ban(anyLong(), anyLong(), any());

		assertInternalError(kick());

		assertNothingChanged();
		reset(banSpy);

		// 되돌아간 뒤 같은 요청을 다시 보내면 정상으로 처리되고 기록은 한 건이다
		assertGraphQlOk(kick());
		assertThat(isActiveMember(team.id(), mate)).isFalse();
		assertThat(redis.hasKey(banKey(team.id(), mate))).isTrue();
		assertThat(warnings.warning(warningId).alive()).isFalse();
		assertThat(adminLogs()).extracting(r -> r.get("action")).containsExactly("KICK_MEMBER");
	}

	@Test
	void 관리자_팀_삭제의_글_연결_해제가_실패하면_기록_삭제_소속_경고_글_연결이_모두_되돌아간다() {
		doThrow(new IllegalStateException("몽고 장애 흉내")).when(mongoSpy)
				.updateMulti(any(Query.class), any(UpdateDefinition.class), eq(Post.class));

		assertInternalError(delete());

		assertNothingChanged();
		reset(mongoSpy);

		assertGraphQlOk(delete());
		assertThat(teamRow(team.id()).get("deleted_at")).isNotNull();
		assertThat(warnings.warning(warningId).alive()).isFalse();
		assertThat(postService.get(postId).teamId()).isNull();
		assertThat(adminLogs()).extracting(r -> r.get("action")).containsExactly("DELETE_TEAM");
	}

	@Test
	void 팀장이_직접_한_추방도_같은_실패에서_되돌아가고_기록은_원래_없다() {
		doAnswer(call -> {
			call.callRealMethod();
			throw new IllegalStateException("레디스 장애 흉내");
		}).when(banSpy).ban(anyLong(), anyLong(), any());

		assertInternalError(gql(leader, TeamGql.KICK, Map.of("teamId", Long.toString(team.id()), "memberId", Long.toString(mate))));

		assertNothingChanged();
	}

	// ----- 기록 자체가 실패 -----

	@Test
	void 기록을_저장하지_못하면_관리자_추방도_함께_취소된다() {
		doThrow(new IllegalStateException("기록 장애 흉내")).when(logRepositorySpy).save(any(AdminActionLog.class));

		assertInternalError(kick());

		assertNothingChanged();
		reset(logRepositorySpy);

		assertGraphQlOk(kick());
		assertThat(adminLogs()).hasSize(1);
	}

	@Test
	void 기록을_저장하지_못하면_관리자_팀_삭제도_함께_취소된다() {
		doThrow(new IllegalStateException("기록 장애 흉내")).when(logRepositorySpy).save(any(AdminActionLog.class));

		assertInternalError(delete());

		assertNothingChanged();
		reset(logRepositorySpy);

		assertGraphQlOk(delete());
		assertThat(adminLogs()).hasSize(1);
	}

}
