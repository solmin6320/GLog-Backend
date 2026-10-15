package com.jandilog.testsupport.board;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;

// 공지 통합 테스트 공통: 공지 호출 도우미와 목록 읽기. 정리는 BoardIntegrationTest가 작성자(관리자) 기준으로 한다
public abstract class NoticeIntegrationSupport extends BoardIntegrationTest {

	public static final String NOTICE_FIELDS = "id title content isPinned isSystem createdAt updatedAt";

	public static final String CREATE_NOTICE = "mutation($input: NoticeInput!) { createNotice(input: $input) { "
			+ NOTICE_FIELDS + " } }";
	public static final String UPDATE_NOTICE = "mutation($id: ID!, $input: NoticeInput!) { updateNotice(id: $id, input: $input) { "
			+ NOTICE_FIELDS + " } }";
	public static final String PIN_NOTICE = "mutation($id: ID!, $isPinned: Boolean!) { setNoticePinned(id: $id, isPinned: $isPinned) { "
			+ NOTICE_FIELDS + " } }";
	public static final String DELETE_NOTICE = "mutation($id: ID!) { deleteNotice(id: $id) }";
	public static final String GET_NOTICE = "query($id: ID!) { notice(id: $id) { " + NOTICE_FIELDS + " } }";
	public static final String LIST_NOTICES = """
			query($after: String) {
			  notices(after: $after) {
			    nextCursor
			    items { id title isPinned isSystem createdAt }
			  }
			}
			""";

	// 공지 목록 한 페이지
	public record NoticePage(List<JsonNode> items, String nextCursor) {

		public List<String> titles() {
			return items.stream().map(item -> item.path("title").asText()).toList();
		}

	}

	protected static Map<String, Object> noticeInput(String title, String content, Boolean pinned) {
		Map<String, Object> input = new HashMap<>();
		input.put("title", title);
		input.put("content", content);
		if (pinned != null) {
			input.put("isPinned", pinned);
		}
		return input;
	}

	protected GraphQlResponse createNotice(long adminId, String title, String content, Boolean pinned) {
		return gql(bearerFor(adminId), CREATE_NOTICE, vars("input", noticeInput(title, content, pinned)));
	}

	// 공지를 만들고 id를 돌려준다
	protected String newNotice(long adminId, String title, boolean pinned) {
		return ok(createNotice(adminId, title, title + " 내용", pinned)).data().path("createNotice").path("id").asText();
	}

	protected NoticePage noticesPage(long memberId, String after) {
		Map<String, Object> variables = new HashMap<>();
		variables.put("after", after);
		GraphQlResponse response = ok(gql(bearerFor(memberId), LIST_NOTICES, variables));
		JsonNode node = response.data().path("notices");
		List<JsonNode> items = new ArrayList<>();
		node.path("items").forEach(items::add);
		return new NoticePage(items, node.path("nextCursor").isNull() ? null : node.path("nextCursor").asText());
	}

	// nextCursor가 null일 때까지 모든 페이지를 읽는다
	protected List<NoticePage> allNoticePages(long memberId) {
		List<NoticePage> pages = new ArrayList<>();
		String cursor = null;
		do {
			NoticePage page = noticesPage(memberId, cursor);
			pages.add(page);
			cursor = page.nextCursor();
		} while (cursor != null && pages.size() < 100);
		return pages;
	}

	// 공유 DB에는 다른 공지가 있을 수 있어 제목 접두어로 내 공지만 골라 읽은 순서대로 돌려준다
	protected static List<String> titlesWithPrefix(List<NoticePage> pages, String prefix) {
		List<String> mine = new ArrayList<>();
		for (NoticePage page : pages) {
			for (String title : page.titles()) {
				if (title.startsWith(prefix)) {
					mine.add(title);
				}
			}
		}
		return mine;
	}

	// 마이그레이션이 아니라 기동 때 만드는 시스템 공지를 테스트에서 직접 넣는다 (작성자는 이 테스트의 관리자)
	protected long insertSystemNotice(long adminId, String title) {
		jdbc.update("insert into notice (title, content, is_pinned, is_system, author_id, created_at) values (?, ?, ?, ?, ?, ?)",
				title, "잔디 인정 규칙 내용", true, true, adminId, LocalDateTime.of(2026, 10, 1, 12, 0, 0));
		Long id = jdbc.queryForObject("select id from notice where author_id = ? and title = ?", Long.class, adminId, title);
		assertThat(id).isNotNull();
		return id;
	}

	protected int noticeCount(long adminId) {
		Integer count = jdbc.queryForObject("select count(*) from notice where author_id = ?", Integer.class, adminId);
		return count == null ? 0 : count;
	}

}
