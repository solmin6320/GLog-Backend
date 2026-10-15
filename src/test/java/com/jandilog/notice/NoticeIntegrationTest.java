package com.jandilog.notice;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.databind.JsonNode;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.notice.service.SystemNoticeService;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;
import com.jandilog.testsupport.board.NoticeIntegrationSupport;

// 공지 작성·수정·고정·삭제·상세와 접근 제어, 잔디 인정 규칙 공지 보호 (기능명세서 4장, EX-AD01-02)
class NoticeIntegrationTest extends NoticeIntegrationSupport {

	private static final Instant T0 = Instant.parse("2026-10-01T03:00:00Z");
	private static final String NO_ID = "999999999999";

	@Autowired
	private SystemNoticeService systemNoticeService;

	private long admin;
	private long member;

	@BeforeEach
	void setUp() {
		admin = adminMember();
		member = activeMember();
	}

	private GraphQlResponse update(long memberId, String id, String title, String content, Boolean pinned) {
		return gql(bearerFor(memberId), UPDATE_NOTICE, vars("id", id, "input", noticeInput(title, content, pinned)));
	}

	private GraphQlResponse pin(long memberId, String id, boolean pinned) {
		return gql(bearerFor(memberId), PIN_NOTICE, vars("id", id, "isPinned", pinned));
	}

	private GraphQlResponse delete(long memberId, String id) {
		return gql(bearerFor(memberId), DELETE_NOTICE, vars("id", id));
	}

	private GraphQlResponse get(long memberId, String id) {
		return gql(bearerFor(memberId), GET_NOTICE, vars("id", id));
	}

	private boolean pinnedInDb(String id) {
		return Boolean.TRUE.equals(jdbc.queryForObject("select is_pinned from notice where id = ?", Boolean.class, id));
	}

	// ----- 작성 -----

	@Test
	void 관리자가_공지를_쓰면_내용과_작성_시각이_저장된다() {
		clock.fixAt(T0);

		JsonNode notice = ok(createNotice(admin, "  정기 점검 안내  ", "  이번 주 토요일 점검  ", true)).data().path("createNotice");

		assertThat(notice.path("id").asText()).matches("^[0-9]+$");
		assertThat(notice.path("title").asText()).isEqualTo("정기 점검 안내");
		assertThat(notice.path("content").asText()).isEqualTo("이번 주 토요일 점검");
		assertThat(notice.path("isPinned").asBoolean()).isTrue();
		assertThat(notice.path("isSystem").asBoolean()).isFalse();
		assertThat(notice.path("createdAt").asText()).isEqualTo("2026-10-01T12:00:00");
		assertThat(notice.path("updatedAt").isNull()).isTrue();
		String id = notice.path("id").asText();
		assertThat(jdbc.queryForObject("select author_id from notice where id = ?", Long.class, id)).isEqualTo(admin);
		assertThat(jdbc.queryForObject("select is_system from notice where id = ?", Boolean.class, id)).isFalse();
		assertThat(jdbc.queryForObject("select created_at from notice where id = ?", LocalDateTime.class, id))
				.isEqualTo(LocalDateTime.of(2026, 10, 1, 12, 0, 0));
	}

	@Test
	void 실제_시계로_쓴_공지도_작성_응답의_시각이_다시_읽은_값과_같다() {
		clock.reset();
		JsonNode created = ok(createNotice(admin, "시각 확인", "내용", false)).data().path("createNotice");

		JsonNode read = ok(get(member, created.path("id").asText())).data().path("notice");

		assertThat(read.path("createdAt").asText()).isEqualTo(created.path("createdAt").asText());
	}

	@Test
	void 고정_여부를_생략하면_고정하지_않는다() {
		JsonNode notice = ok(createNotice(admin, "일반 공지", "내용", null)).data().path("createNotice");

		assertThat(notice.path("isPinned").asBoolean()).isFalse();
		assertThat(pinnedInDb(notice.path("id").asText())).isFalse();
	}

	@Test
	void 제목은_200자까지_허용하고_201자부터_INVALID_INPUT이다() {
		assertThat(ok(createNotice(admin, "가".repeat(200), "내용", false)).data().path("createNotice").path("title").asText())
				.hasSize(200);

		GraphQlResponse response = createNotice(admin, "가".repeat(201), "내용", false);

		assertError(response, ErrorCode.INVALID_INPUT.name());
		assertThat(noticeCount(admin)).isEqualTo(1);
	}

	@Test
	void 제목_길이는_UTF_16이_아니라_글자_수로_세어_이모지_200자도_저장된다() {
		String emojis = "😀".repeat(200);

		JsonNode notice = ok(createNotice(admin, emojis, "내용", false)).data().path("createNotice");

		assertThat(notice.path("title").asText()).isEqualTo(emojis);
		assertThat(jdbc.queryForObject("select title from notice where id = ?", String.class, notice.path("id").asText()))
				.isEqualTo(emojis);
		assertError(createNotice(admin, "😀".repeat(201), "내용", false), ErrorCode.INVALID_INPUT.name());
	}

	@Test
	void 내용은_65535바이트까지_저장하고_그_이상은_INVALID_INPUT이다() {
		String maxAscii = "a".repeat(65_535);
		JsonNode notice = ok(createNotice(admin, "긴 공지", maxAscii, false)).data().path("createNotice");

		assertThat(notice.path("content").asText()).hasSize(65_535);
		assertThat(jdbc.queryForObject("select length(content) from notice where id = ?", Integer.class,
				notice.path("id").asText())).isEqualTo(65_535);
		assertError(createNotice(admin, "긴 공지", "a".repeat(65_536), false), ErrorCode.INVALID_INPUT.name());
		// 한글은 글자당 3바이트라 21,845자까지다
		assertThat(ok(createNotice(admin, "한글 공지", "가".repeat(21_845), false)).data().path("createNotice").path("content")
				.asText()).hasSize(21_845);
		assertError(createNotice(admin, "한글 공지", "가".repeat(21_846), false), ErrorCode.INVALID_INPUT.name());
		assertThat(noticeCount(admin)).isEqualTo(2);
	}

	@Test
	void 제목이나_내용이_비었으면_INVALID_INPUT이고_저장되지_않는다() {
		assertError(createNotice(admin, "  ", "내용", false), ErrorCode.INVALID_INPUT.name());
		assertError(createNotice(admin, "제목", "  ", false), ErrorCode.INVALID_INPUT.name());
		assertError(createNotice(admin, "", "내용", false), ErrorCode.INVALID_INPUT.name());

		assertThat(noticeCount(admin)).isZero();
	}

	@Test
	void 공지에는_댓글이_없어_댓글_필드를_요청하면_스키마_검증에서_거부된다() {
		String id = newNotice(admin, "댓글 없는 공지", false);

		GraphQlResponse response = gql(bearerFor(member), "query($id: ID!) { notice(id: $id) { id comments { id } } }",
				vars("id", id));

		assertThat(response.hasErrors()).isTrue();
		assertThat(response.errorClassification()).isEqualTo("ValidationError");
	}

	// ----- 수정 -----

	@Test
	void 수정하면_제목_내용_고정이_바뀌고_수정_시각이_찍힌다() {
		clock.fixAt(T0);
		String id = newNotice(admin, "원래 제목", true);
		clock.fixAt(T0.plusSeconds(3600));

		JsonNode notice = ok(update(admin, id, " 새 제목 ", " 새 내용 ", true)).data().path("updateNotice");

		assertThat(notice.path("id").asText()).isEqualTo(id);
		assertThat(notice.path("title").asText()).isEqualTo("새 제목");
		assertThat(notice.path("content").asText()).isEqualTo("새 내용");
		assertThat(notice.path("isPinned").asBoolean()).isTrue();
		assertThat(notice.path("createdAt").asText()).isEqualTo("2026-10-01T12:00:00");
		assertThat(notice.path("updatedAt").asText()).isEqualTo("2026-10-01T13:00:00");
		assertThat(ok(get(member, id)).data().path("notice").path("title").asText()).isEqualTo("새 제목");
	}

	@Test
	void 수정할_때_고정_여부를_생략하면_고정이_풀린다() {
		String id = newNotice(admin, "고정 공지", true);
		assertThat(pinnedInDb(id)).isTrue();

		JsonNode notice = ok(update(admin, id, "고정 공지", "내용", null)).data().path("updateNotice");

		assertThat(notice.path("isPinned").asBoolean()).isFalse();
		assertThat(pinnedInDb(id)).isFalse();
	}

	@Test
	void 수정_입력이_잘못되면_공지가_바뀌지_않는다() {
		String id = newNotice(admin, "원래 제목", true);

		assertError(update(admin, id, " ", "내용", true), ErrorCode.INVALID_INPUT.name());
		assertError(update(admin, id, "제목", " ", true), ErrorCode.INVALID_INPUT.name());
		assertError(update(admin, id, "가".repeat(201), "내용", true), ErrorCode.INVALID_INPUT.name());

		JsonNode notice = ok(get(member, id)).data().path("notice");
		assertThat(notice.path("title").asText()).isEqualTo("원래 제목");
		assertThat(notice.path("updatedAt").isNull()).isTrue();
	}

	@Test
	void 없는_공지나_숫자가_아닌_id는_NOT_FOUND다() {
		assertError(update(admin, NO_ID, "제목", "내용", false), ErrorCode.NOT_FOUND.name());
		assertError(update(admin, "abc", "제목", "내용", false), ErrorCode.NOT_FOUND.name());
		assertError(pin(admin, NO_ID, true), ErrorCode.NOT_FOUND.name());
		assertError(delete(admin, NO_ID), ErrorCode.NOT_FOUND.name());
		assertError(get(member, NO_ID), ErrorCode.NOT_FOUND.name());
		assertError(get(member, "abc"), ErrorCode.NOT_FOUND.name());
	}

	// ----- 고정 -----

	@Test
	void 상단_고정을_켜고_끈다() {
		String id = newNotice(admin, "고정 시험", false);

		JsonNode pinned = ok(pin(admin, id, true)).data().path("setNoticePinned");
		assertThat(pinned.path("isPinned").asBoolean()).isTrue();
		assertThat(pinnedInDb(id)).isTrue();

		JsonNode unpinned = ok(pin(admin, id, false)).data().path("setNoticePinned");
		assertThat(unpinned.path("isPinned").asBoolean()).isFalse();
		assertThat(pinnedInDb(id)).isFalse();
	}

	@Test
	void 고정만_바꾸면_제목과_내용과_수정_시각은_그대로다() {
		String id = newNotice(admin, "그대로", false);

		JsonNode notice = ok(pin(admin, id, true)).data().path("setNoticePinned");

		assertThat(notice.path("title").asText()).isEqualTo("그대로");
		assertThat(notice.path("content").asText()).isEqualTo("그대로 내용");
		assertThat(notice.path("updatedAt").isNull()).isTrue();
	}

	// ----- 삭제 -----

	@Test
	void 관리자가_일반_공지를_삭제하면_사라진다() {
		String id = newNotice(admin, "지울 공지", false);

		GraphQlResponse response = ok(delete(admin, id));

		assertThat(response.data().path("deleteNotice").asBoolean()).isTrue();
		assertThat(jdbc.queryForObject("select count(*) from notice where id = ?", Integer.class, id)).isZero();
		assertError(get(member, id), ErrorCode.NOT_FOUND.name());
		assertError(delete(admin, id), ErrorCode.NOT_FOUND.name());
	}

	@Test
	void 고정된_일반_공지도_삭제할_수_있다() {
		String id = newNotice(admin, "고정된 일반 공지", true);

		assertThat(ok(delete(admin, id)).data().path("deleteNotice").asBoolean()).isTrue();
	}

	// ----- 잔디 인정 규칙 공지 보호 -----

	@Test
	void 잔디_인정_규칙_공지는_삭제를_거부하고_공지는_그대로_남는다() {
		long systemId = insertSystemNotice(admin, "잔디 인정 규칙 " + members.tag());

		GraphQlResponse response = delete(admin, Long.toString(systemId));

		assertError(response, ErrorCode.SYSTEM_NOTICE_UNDELETABLE.name());
		assertThat(response.errorMessage()).isEqualTo("이 공지는 판정 기준 안내라 삭제할 수 없어요.");
		assertThat(response.errorClassification()).isEqualTo("FORBIDDEN");
		JsonNode notice = ok(get(member, Long.toString(systemId))).data().path("notice");
		assertThat(notice.path("isSystem").asBoolean()).isTrue();
		assertThat(notice.path("isPinned").asBoolean()).isTrue();
	}

	@Test
	void 시스템_공지는_몇_번을_시도해도_삭제되지_않고_다른_관리자도_마찬가지다() {
		long systemId = insertSystemNotice(admin, "잔디 인정 규칙 " + members.tag());
		long anotherAdmin = adminMember();

		for (int i = 0; i < 3; i++) {
			assertError(delete(admin, Long.toString(systemId)), ErrorCode.SYSTEM_NOTICE_UNDELETABLE.name());
		}
		assertError(delete(anotherAdmin, Long.toString(systemId)), ErrorCode.SYSTEM_NOTICE_UNDELETABLE.name());

		assertThat(jdbc.queryForObject("select count(*) from notice where id = ?", Integer.class, systemId)).isEqualTo(1);
	}

	@Test
	void 일반_회원이_시스템_공지를_지우려_하면_삭제_거부가_아니라_권한_없음이다() {
		long systemId = insertSystemNotice(admin, "잔디 인정 규칙 " + members.tag());

		GraphQlResponse response = delete(member, Long.toString(systemId));

		assertError(response, ErrorCode.FORBIDDEN.name());
		assertThat(jdbc.queryForObject("select count(*) from notice where id = ?", Integer.class, systemId)).isEqualTo(1);
	}

	@Test
	void 시스템_공지가_이미_있으면_기동_초기화는_아무것도_만들지_않는다() {
		insertSystemNotice(admin, "잔디 인정 규칙 " + members.tag());
		Integer before = jdbc.queryForObject("select count(*) from notice where is_system = 1", Integer.class);

		boolean created = systemNoticeService.ensureExists();

		assertThat(created).isFalse();
		assertThat(jdbc.queryForObject("select count(*) from notice where is_system = 1", Integer.class)).isEqualTo(before);
	}

	// ----- 접근 제어 -----

	private record Operation(String name, String query) {

		@Override
		public String toString() {
			return name;
		}

	}

	private static Stream<Operation> operations() {
		return Stream.of(
				new Operation("notices", "query { notices { nextCursor items { id } } }"),
				new Operation("notice", "query { notice(id: \"" + NO_ID + "\") { id } }"),
				new Operation("createNotice", "mutation { createNotice(input: { title: \"권한 확인\", content: \"내용\" }) { id } }"),
				new Operation("updateNotice",
						"mutation { updateNotice(id: \"" + NO_ID + "\", input: { title: \"권한 확인\", content: \"내용\" }) { id } }"),
				new Operation("setNoticePinned", "mutation { setNoticePinned(id: \"" + NO_ID + "\", isPinned: true) { id } }"),
				new Operation("deleteNotice", "mutation { deleteNotice(id: \"" + NO_ID + "\") }"));
	}

	private static Stream<Operation> adminOnly() {
		return operations().filter(operation -> !operation.name().equals("notices") && !operation.name().equals("notice"));
	}

	private static Stream<Arguments> deniedByStatus() {
		return operations().flatMap(operation -> Stream.of(
				Arguments.of(operation, "ANONYMOUS", ErrorCode.UNAUTHENTICATED),
				Arguments.of(operation, "PENDING", ErrorCode.ACCOUNT_PENDING),
				Arguments.of(operation, "REJECTED", ErrorCode.ACCOUNT_REJECTED)));
	}

	@ParameterizedTest(name = "{0} - {1}")
	@MethodSource("deniedByStatus")
	void 비로그인_승인_대기_거절_계정은_모든_공지_루트_필드가_거부된다(Operation operation, String kind, ErrorCode expected) {
		String token = switch (kind) {
			case "PENDING" -> bearerFor(pendingMember());
			case "REJECTED" -> bearerFor(rejectedMember());
			default -> null;
		};

		GraphQlResponse response = graphQl.post(token, operation.query());

		assertThat(response.status()).isEqualTo(200);
		assertThat(response.errorCode()).as(response.rawBody()).isEqualTo(expected.name());
		assertThat(response.errorMessage()).isEqualTo(expected.message());
		assertThat(response.dataIsNull()).isTrue();
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("adminOnly")
	void 일반_회원은_공지_작성_수정_고정_삭제가_FORBIDDEN이다(Operation operation) {
		GraphQlResponse response = graphQl.post(bearerFor(member), operation.query());

		assertThat(response.status()).isEqualTo(200);
		assertThat(response.errorCode()).as(response.rawBody()).isEqualTo("FORBIDDEN");
		assertThat(response.errorMessage()).isEqualTo(ErrorCode.FORBIDDEN.message());
		assertThat(response.dataIsNull()).isTrue();
	}

	@Test
	void 일반_회원이_공지를_써도_저장되지_않고_기존_공지도_바뀌지_않는다() {
		String id = newNotice(admin, "관리자 공지", true);

		assertError(createNotice(member, "회원이 쓴 공지", "내용", false), "FORBIDDEN");
		assertError(update(member, id, "바꿔치기", "내용", false), "FORBIDDEN");
		assertError(pin(member, id, false), "FORBIDDEN");
		assertError(delete(member, id), "FORBIDDEN");

		assertThat(noticeCount(member)).isZero();
		JsonNode notice = ok(get(member, id)).data().path("notice");
		assertThat(notice.path("title").asText()).isEqualTo("관리자 공지");
		assertThat(notice.path("isPinned").asBoolean()).isTrue();
	}

	@Test
	void 일반_회원은_공지_목록과_상세를_볼_수_있다() {
		String id = newNotice(admin, "회원도 보는 공지", false);

		assertThat(ok(get(member, id)).data().path("notice").path("title").asText()).isEqualTo("회원도 보는 공지");
		assertThat(ok(gql(bearerFor(member), LIST_NOTICES, vars("after", null))).hasErrors()).isFalse();
	}

	@Test
	void 관리자도_ACTIVE_회원이라_공지를_볼_수_있다() {
		String id = newNotice(admin, "관리자가 보는 공지", false);

		assertThat(ok(get(admin, id)).data().path("notice").path("id").asText()).isEqualTo(id);
	}

}
