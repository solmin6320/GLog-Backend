package com.jandilog.notice;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;
import com.jandilog.testsupport.board.NoticeIntegrationSupport;

// 공지 목록: 고정 공지가 위, 그 아래 최신순, 커서 기반 20개 (기능명세서 4장, Q-06, E-57).
// 공유 DB에 다른 공지가 있어도 되도록 제목 접두어로 내 공지만 골라 순서를 확인한다
class NoticeListIntegrationTest extends NoticeIntegrationSupport {

	private static final Instant BASE = Instant.parse("2026-10-01T00:00:00Z");

	private long admin;
	private long member;
	private String prefix;
	private int seconds;

	@BeforeEach
	void setUp() {
		admin = adminMember();
		member = activeMember();
		prefix = "n" + members.tag() + "-";
		seconds = 0;
	}

	// 제목 접두어 + 순번으로 공지를 만든다. 만들 때마다 저장 시각이 1초씩 늘어난다
	private String create(String name, boolean pinned) {
		clock.fixAt(BASE.plusSeconds(seconds++));
		return newNotice(admin, prefix + name, pinned);
	}

	private List<String> mine() {
		return titlesWithPrefix(allNoticePages(member), prefix);
	}

	private static String cursorOf(boolean pinned, LocalDateTime createdAt, long id) {
		String raw = (pinned ? "P" : "N") + "|" + DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(createdAt) + "|" + id;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	// ----- 정렬 -----

	@Test
	void 고정_공지가_위에_오고_그_안과_아래는_최신순이다() {
		// 고정을 일부러 먼저(오래전에) 만들어서 고정이 최신순보다 우선하는지 본다
		create("고정-오래됨", true);
		create("고정-최신", true);
		create("일반-오래됨", false);
		create("일반-최신", false);

		assertThat(mine()).containsExactly(prefix + "고정-최신", prefix + "고정-오래됨", prefix + "일반-최신", prefix + "일반-오래됨");
	}

	@Test
	void 잔디_인정_규칙_공지는_고정_공지라서_일반_공지_위에_오고_고정_공지와는_최신순이다() {
		// 사용자 고정 공지는 09:00(KST)에, 시스템 공지는 12:00에 만들어진 것으로 본다
		create("고정", true);
		create("일반", false);
		insertSystemNotice(admin, prefix + "시스템");

		assertThat(mine()).containsExactly(prefix + "시스템", prefix + "고정", prefix + "일반");
	}

	@Test
	void 같은_시각에_만든_공지는_id가_큰_쪽이_먼저다() {
		clock.fixAt(BASE);
		newNotice(admin, prefix + "먼저", false);
		newNotice(admin, prefix + "나중", false);

		assertThat(mine()).containsExactly(prefix + "나중", prefix + "먼저");
	}

	@Test
	void 고정하면_맨_위로_올라가고_풀면_작성_시각_순서로_내려간다() {
		String older = create("A", false);
		create("B", false);
		assertThat(mine()).containsExactly(prefix + "B", prefix + "A");

		ok(gql(bearerFor(admin), PIN_NOTICE, vars("id", older, "isPinned", true)));
		assertThat(mine()).containsExactly(prefix + "A", prefix + "B");

		ok(gql(bearerFor(admin), PIN_NOTICE, vars("id", older, "isPinned", false)));
		assertThat(mine()).containsExactly(prefix + "B", prefix + "A");
	}

	@Test
	void 수정해도_작성_시각_기준_순서는_바뀌지_않는다() {
		String older = create("A", false);
		create("B", false);
		clock.fixAt(BASE.plusSeconds(1000));

		ok(gql(bearerFor(admin), UPDATE_NOTICE, vars("id", older, "input", noticeInput(prefix + "A-수정", "내용", false))));

		assertThat(mine()).containsExactly(prefix + "B", prefix + "A-수정");
	}

	@Test
	void 삭제한_공지는_목록에서_사라진다() {
		create("A", false);
		String gone = create("B", false);
		create("C", false);
		ok(gql(bearerFor(admin), DELETE_NOTICE, vars("id", gone)));

		assertThat(mine()).containsExactly(prefix + "C", prefix + "A");
	}

	@Test
	void 목록_항목에는_고정_여부와_시스템_여부와_작성_시각이_담긴다() {
		create("고정", true);
		long systemId = insertSystemNotice(admin, prefix + "시스템");

		List<NoticePage> pages = allNoticePages(member);

		List<JsonNode> mineItems = pages.stream().flatMap(page -> page.items().stream())
				.filter(item -> item.path("title").asText().startsWith(prefix)).toList();
		assertThat(mineItems).hasSize(2);
		JsonNode pinned = mineItems.stream().filter(item -> item.path("title").asText().endsWith("고정")).findFirst().orElseThrow();
		JsonNode system = mineItems.stream().filter(item -> item.path("title").asText().endsWith("시스템")).findFirst().orElseThrow();
		assertThat(pinned.path("isPinned").asBoolean()).isTrue();
		assertThat(pinned.path("isSystem").asBoolean()).isFalse();
		assertThat(pinned.path("createdAt").asText()).isEqualTo("2026-10-01T09:00:00");
		assertThat(system.path("id").asText()).isEqualTo(Long.toString(systemId));
		assertThat(system.path("isSystem").asBoolean()).isTrue();
		assertThat(system.path("isPinned").asBoolean()).isTrue();
	}

	// ----- 페이지 (고정과 일반 경계 포함) -----

	@ParameterizedTest(name = "고정 {0}개 + 일반 {1}개")
	@CsvSource({"0,0", "1,0", "0,1", "5,40", "22,3", "20,5", "21,0", "0,21", "0,40", "20,20"})
	void 한_페이지는_20개이고_고정과_일반_경계를_넘어도_중복과_누락_없이_이어진다(int pinnedCount, int plainCount) {
		List<String> expected = new ArrayList<>();
		List<String> pinnedNames = IntStream.range(0, pinnedCount).mapToObj(i -> "고정" + i).toList();
		List<String> plainNames = IntStream.range(0, plainCount).mapToObj(i -> "일반" + i).toList();
		// 고정을 먼저(오래전에), 일반을 나중에 만든다
		pinnedNames.forEach(name -> create(name, true));
		plainNames.forEach(name -> create(name, false));
		// 기대 순서: 고정 최신순 → 일반 최신순
		for (int i = pinnedNames.size() - 1; i >= 0; i--) {
			expected.add(prefix + pinnedNames.get(i));
		}
		for (int i = plainNames.size() - 1; i >= 0; i--) {
			expected.add(prefix + plainNames.get(i));
		}

		List<NoticePage> pages = allNoticePages(member);

		// 마지막을 뺀 모든 페이지는 정확히 20개이고 다음 커서가 있다
		for (int i = 0; i < pages.size() - 1; i++) {
			assertThat(pages.get(i).items()).as("%d번째 페이지", i + 1).hasSize(20);
			assertThat(pages.get(i).nextCursor()).isNotBlank();
		}
		NoticePage last = pages.get(pages.size() - 1);
		assertThat(last.items().size()).isLessThanOrEqualTo(20);
		assertThat(last.nextCursor()).isNull();
		List<String> all = pages.stream().flatMap(page -> page.titles().stream()).toList();
		assertThat(all).doesNotHaveDuplicates();
		assertThat(titlesWithPrefix(pages, prefix)).containsExactlyElementsOf(expected);
	}

	@Test
	void 공지가_정확히_20개_이하로만_있으면_마지막_페이지는_커서가_없다() {
		// 다른 공지가 없는 환경에서만 의미가 있어, 내 공지가 전체를 이루는 경우만 확인한다
		create("A", false);
		create("B", true);

		List<NoticePage> pages = allNoticePages(member);

		assertThat(pages.get(pages.size() - 1).nextCursor()).isNull();
	}

	@Test
	void 첫_페이지를_읽는_사이_새_공지가_올라와도_다음_페이지에_중복이_없다() {
		for (int i = 0; i < 25; i++) {
			create("글" + i, false);
		}
		NoticePage first = noticesPage(member, null);

		create("끼어든 글", false);
		NoticePage second = noticesPage(member, first.nextCursor());

		assertThat(first.nextCursor()).isNotNull();
		List<String> firstTitles = first.titles();
		assertThat(second.titles()).doesNotContainAnyElementsOf(firstTitles);
		assertThat(second.titles()).doesNotContain(prefix + "끼어든 글");
	}

	@Test
	void 커서는_그_공지_바로_다음부터_읽는다() {
		create("A", false);
		String b = create("B", false);
		create("C", false);
		LocalDateTime bCreatedAt = LocalDateTime.of(2026, 10, 1, 9, 0, 1);

		NoticePage page = noticesPage(member, cursorOf(false, bCreatedAt, Long.parseLong(b)));

		// B(작성 시각 09:00:01)보다 오래된 공지만 나온다. 내 공지 중에는 A
		assertThat(titlesWithPrefix(List.of(page), prefix)).containsExactly(prefix + "A");
	}

	@Test
	void 고정_공지_커서_다음에는_남은_고정_공지와_모든_일반_공지가_이어진다() {
		String pinnedOld = create("고정-오래됨", true);
		create("고정-최신", true);
		create("일반", false);
		LocalDateTime createdAt = LocalDateTime.of(2026, 10, 1, 9, 0, 0);

		NoticePage page = noticesPage(member, cursorOf(true, createdAt, Long.parseLong(pinnedOld)));

		// 가장 오래된 고정 공지 다음이므로 내 공지 중에는 일반 공지만 남는다
		assertThat(titlesWithPrefix(List.of(page), prefix)).containsExactly(prefix + "일반");
	}

	@ParameterizedTest
	@ValueSource(strings = {"!!!", "abc", "한글", "====", "a+b/", "not a cursor", "12345"})
	void 잘못된_커서는_INVALID_INPUT이다(String cursor) {
		GraphQlResponse response = gql(bearerFor(member), LIST_NOTICES, vars("after", cursor));

		assertError(response, ErrorCode.INVALID_INPUT.name());
		assertThat(response.errorMessage()).isEqualTo(ErrorCode.INVALID_INPUT.message());
	}

	@Test
	void 형식은_맞지만_내용이_틀린_커서도_INVALID_INPUT이다() {
		for (String raw : new String[] {"X|2026-10-01T09:00:00|1", "P|nope|1", "P|2026-10-01T09:00:00|0", "P|2026-10-01T09:00:00|x"}) {
			String cursor = Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));

			assertError(gql(bearerFor(member), LIST_NOTICES, vars("after", cursor)), ErrorCode.INVALID_INPUT.name());
		}
	}

	@Test
	void 빈_문자열_커서는_처음부터_읽는다() {
		create("A", false);

		NoticePage page = noticesPage(member, "");

		assertThat(titlesWithPrefix(List.of(page), prefix)).containsExactly(prefix + "A");
	}

}
