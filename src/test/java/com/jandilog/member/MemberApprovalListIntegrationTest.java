package com.jandilog.member;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.jandilog.common.exception.ErrorCode;
import com.jandilog.member.domain.MemberRole;
import com.jandilog.member.domain.MemberStatus;
import com.jandilog.testsupport.auth.GraphQlHttpClient.GraphQlResponse;

// 가입 승인 목록: 커서 20개, 상태 필터, 검색 (기능명세서 1·9장, E-57, Q-06)
class MemberApprovalListIntegrationTest extends ApprovalIntegrationTest {

	private String tag() {
		return members.tag();
	}

	private long insert(MemberStatus status, String login, String nickname) {
		return members.insert(status, MemberRole.MEMBER, login, nickname);
	}

	// 닉네임 n<tag>-<i> 형태의 승인 대기 회원을 n명 만든다 (id 오름차순으로 만들어진다)
	private List<Long> insertPending(int count) {
		List<Long> ids = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			ids.add(insert(MemberStatus.PENDING, members.uniqueLogin(), "n" + tag() + "-" + i));
		}
		return ids;
	}

	private static String cursorOf(long id) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(Long.toString(id).getBytes(StandardCharsets.UTF_8));
	}

	// ----- 페이지 -----

	@ParameterizedTest
	@ValueSource(ints = {0, 1, 19, 20, 21, 39, 40, 41, 45})
	void 한_페이지는_20개이고_중복과_누락_없이_id_오름차순으로_끝까지_읽힌다(int count) {
		List<Long> inserted = insertPending(count);

		List<Page> pages = allPages("PENDING", tag());

		int expectedPages = Math.max(1, (count + 19) / 20);
		assertThat(pages).hasSize(expectedPages);
		List<Long> all = new ArrayList<>();
		for (int i = 0; i < pages.size(); i++) {
			Page page = pages.get(i);
			boolean last = i == pages.size() - 1;
			assertThat(page.ids()).hasSize(last ? count - 20 * (expectedPages - 1) : 20);
			// 다음 페이지가 있을 때만 커서가 있고, 마지막 페이지는 null (정확히 20의 배수여도 마지막은 null)
			if (last) {
				assertThat(page.nextCursor()).isNull();
			} else {
				assertThat(page.nextCursor()).isNotBlank();
			}
			assertThat(page.totalCount()).isEqualTo(count);
			all.addAll(page.ids());
		}
		assertThat(all).containsExactlyElementsOf(inserted);
		assertThat(all).doesNotHaveDuplicates().isSorted();
	}

	@Test
	void 커서를_넘기면_그_id_다음부터_읽는다() {
		List<Long> inserted = insertPending(5);

		Page page = page("PENDING", tag(), cursorOf(inserted.get(1)));

		assertThat(page.ids()).containsExactly(inserted.get(2), inserted.get(3), inserted.get(4));
		assertThat(page.totalCount()).isEqualTo(5);
	}

	@Test
	void 마지막_id_이후_커서는_빈_목록이고_전체_건수는_그대로다() {
		List<Long> inserted = insertPending(3);

		Page page = page("PENDING", tag(), cursorOf(inserted.get(2)));

		assertThat(page.ids()).isEmpty();
		assertThat(page.nextCursor()).isNull();
		assertThat(page.totalCount()).isEqualTo(3);
	}

	@Test
	void 커서가_0이거나_빈_문자열이면_처음부터_읽는다() {
		List<Long> inserted = insertPending(3);

		assertThat(page("PENDING", tag(), cursorOf(0)).ids()).containsExactlyElementsOf(inserted);
		assertThat(page("PENDING", tag(), "").ids()).containsExactlyElementsOf(inserted);
	}

	@ParameterizedTest
	@ValueSource(strings = {"!!!", "abc", "a+b/", "한글", "====", "not a cursor", "LTE", "MS4 1"})
	void 잘못된_커서는_INVALID_INPUT이다(String cursor) {
		GraphQlResponse response = list("PENDING", tag(), cursor);

		assertThat(response.status()).isEqualTo(200);
		assertThat(response.errorCode()).isEqualTo("INVALID_INPUT");
		assertThat(response.errorMessage()).isEqualTo(ErrorCode.INVALID_INPUT.message());
		assertThat(response.dataIsNull()).isTrue();
	}

	@Test
	void 음수와_범위를_넘는_숫자를_감싼_커서도_INVALID_INPUT이다() {
		for (String raw : new String[] {"-1", "abc", "99999999999999999999"}) {
			GraphQlResponse response = list("PENDING", tag(),
					Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8)));

			assertThat(response.errorCode()).as(raw).isEqualTo("INVALID_INPUT");
		}
	}

	@Test
	void 앞_페이지_항목이_처리돼_사라져도_다음_페이지에_중복과_누락이_없다() {
		List<Long> inserted = insertPending(25);
		Page first = page("PENDING", tag(), null);
		assertThat(first.ids()).hasSize(20);

		// 첫 페이지 앞쪽 10명을 승인해서 승인 대기에서 뺀다
		for (int i = 0; i < 10; i++) {
			assertThat(approve(adminToken, first.ids().get(i)).hasErrors()).isFalse();
		}
		Page second = page("PENDING", tag(), first.nextCursor());

		// 커서 기준이라 21~25번째가 그대로 나온다 (오프셋 방식이면 건너뛰게 된다)
		assertThat(second.ids()).containsExactlyElementsOf(inserted.subList(20, 25));
		assertThat(second.nextCursor()).isNull();
		assertThat(second.totalCount()).isEqualTo(15);
		assertThat(page("PENDING", tag(), null).ids()).containsExactlyElementsOf(inserted.subList(10, 25).subList(0, 15));
	}

	@Test
	void 페이지를_넘기는_사이_새로_가입해도_마지막에_이어서_나온다() {
		List<Long> inserted = insertPending(21);
		Page first = page("PENDING", tag(), null);

		long late = insert(MemberStatus.PENDING, members.uniqueLogin(), "n" + tag() + "-late");
		Page second = page("PENDING", tag(), first.nextCursor());

		assertThat(second.ids()).containsExactly(inserted.get(20), late);
		assertThat(second.nextCursor()).isNull();
	}

	// ----- 상태 필터 -----

	@Test
	void 상태_필터는_기본_승인_대기이고_ALL은_승인_대기와_거절이다() {
		long p1 = insert(MemberStatus.PENDING, members.uniqueLogin(), "n" + tag() + "-p1");
		long r1 = insert(MemberStatus.REJECTED, members.uniqueLogin(), "n" + tag() + "-r1");
		long p2 = insert(MemberStatus.PENDING, members.uniqueLogin(), "n" + tag() + "-p2");
		long r2 = insert(MemberStatus.REJECTED, members.uniqueLogin(), "n" + tag() + "-r2");
		long p3 = insert(MemberStatus.PENDING, members.uniqueLogin(), "n" + tag() + "-p3");
		insert(MemberStatus.ACTIVE, members.uniqueLogin(), "n" + tag() + "-a1");
		insert(MemberStatus.ACTIVE, members.uniqueLogin(), "n" + tag() + "-a2");

		Page byDefault = page(null, tag(), null);
		Page pending = page("PENDING", tag(), null);
		Page rejected = page("REJECTED", tag(), null);
		Page all = page("ALL", tag(), null);

		assertThat(byDefault.ids()).containsExactly(p1, p2, p3);
		assertThat(byDefault.totalCount()).isEqualTo(3);
		assertThat(pending.ids()).containsExactly(p1, p2, p3);
		assertThat(rejected.ids()).containsExactly(r1, r2);
		assertThat(rejected.totalCount()).isEqualTo(2);
		// ALL은 두 상태를 섞어서 id 순서 그대로, 활성 회원은 어느 필터에도 나오지 않는다
		assertThat(all.ids()).containsExactly(p1, r1, p2, r2, p3);
		assertThat(all.totalCount()).isEqualTo(5);
		for (var item : all.items()) {
			assertThat(item.path("status").asText()).isIn("PENDING", "REJECTED");
		}
	}

	@Test
	void status를_명시적으로_null로_보내도_승인_대기만_나온다() {
		long pending = insert(MemberStatus.PENDING, members.uniqueLogin(), "n" + tag() + "-p");
		insert(MemberStatus.REJECTED, members.uniqueLogin(), "n" + tag() + "-r");
		Map<String, Object> variables = new HashMap<>();
		variables.put("status", null);
		variables.put("keyword", tag());

		GraphQlResponse response = graphQl.post(adminToken, LIST, variables);

		assertThat(toPage(response).ids()).containsExactly(pending);
	}

	@Test
	void 상태_필터와_검색이_함께_걸려도_페이지가_이어진다() {
		List<Long> pending = insertPending(25);
		List<Long> rejected = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			rejected.add(insert(MemberStatus.REJECTED, members.uniqueLogin(), "n" + tag() + "-r" + i));
		}

		List<Page> pendingPages = allPages("PENDING", tag());
		List<Page> allPages = allPages("ALL", tag());

		assertThat(pendingPages).extracting(p -> p.ids().size()).containsExactly(20, 5);
		assertThat(allPages).extracting(p -> p.ids().size()).containsExactly(20, 8);
		List<Long> expectedAll = new ArrayList<>(pending);
		expectedAll.addAll(rejected);
		assertThat(allPages.stream().flatMap(p -> p.ids().stream()).toList()).containsExactlyElementsOf(expectedAll);
		assertThat(allPages.get(0).totalCount()).isEqualTo(28);
	}

	@Test
	void 항목에는_신청_시각을_KST_ISO_형식_문자열과_문자열_id로_담는다() {
		long id = insert(MemberStatus.PENDING, "octo-" + tag(), "홍길동-" + tag());

		Page page = page("PENDING", tag(), null);

		var item = page.items().get(0);
		assertThat(page.ids()).containsExactly(id);
		assertThat(item.path("id").isTextual()).isTrue();
		assertThat(item.path("githubLogin").asText()).isEqualTo("octo-" + tag());
		assertThat(item.path("nickname").asText()).isEqualTo("홍길동-" + tag());
		assertThat(item.path("status").asText()).isEqualTo("PENDING");
		assertThat(item.path("createdAt").asText()).isEqualTo("2026-01-02T03:04:05");
	}

	// ----- 검색 -----

	@Test
	void 검색어는_닉네임_부분_일치로_찾는다() {
		long target = insert(MemberStatus.PENDING, members.uniqueLogin(), "Alpha" + tag() + "Zed");
		insert(MemberStatus.PENDING, members.uniqueLogin(), "Other" + tag());

		Page page = page("PENDING", "pha" + tag() + "Z", null);

		assertThat(page.ids()).containsExactly(target);
		assertThat(page.totalCount()).isEqualTo(1);
	}

	@Test
	void 검색어는_GitHub_아이디_부분_일치로도_찾는다() {
		long target = insert(MemberStatus.PENDING, "Login" + tag() + "X", "전혀다른이름");
		insert(MemberStatus.PENDING, members.uniqueLogin(), "또다른이름");

		Page page = page("PENDING", "ogin" + tag(), null);

		assertThat(page.ids()).containsExactly(target);
	}

	@Test
	void 검색은_대소문자를_가리지_않는다() {
		long upper = insert(MemberStatus.PENDING, members.uniqueLogin(), "ABC" + tag().toUpperCase());
		long lower = insert(MemberStatus.PENDING, members.uniqueLogin(), "abc" + tag());

		assertThat(page("PENDING", "abc" + tag(), null).ids()).containsExactly(upper, lower);
		assertThat(page("PENDING", "ABC" + tag().toUpperCase(), null).ids()).containsExactly(upper, lower);
		assertThat(page("PENDING", "aBc" + tag().toUpperCase(), null).ids()).containsExactly(upper, lower);
	}

	@Test
	void 한글_검색어도_부분_일치로_찾는다() {
		long target = insert(MemberStatus.PENDING, members.uniqueLogin(), "김잔디" + tag() + "님");

		assertThat(page("PENDING", "잔디" + tag(), null).ids()).containsExactly(target);
	}

	@Test
	void 검색어_앞뒤_공백은_무시한다() {
		long target = insert(MemberStatus.PENDING, members.uniqueLogin(), "Trim" + tag());

		assertThat(page("PENDING", "   Trim" + tag() + "  ", null).ids()).containsExactly(target);
	}

	@Test
	void 퍼센트는_와일드카드가_아니라_글자_그대로_찾는다() {
		long literal = insert(MemberStatus.PENDING, members.uniqueLogin(), "a%" + tag());
		insert(MemberStatus.PENDING, members.uniqueLogin(), "axyz" + tag());

		Page page = page("PENDING", "a%" + tag(), null);

		assertThat(page.ids()).containsExactly(literal);
		assertThat(page.totalCount()).isEqualTo(1);
	}

	@Test
	void 퍼센트만_검색해도_퍼센트가_든_회원만_나온다() {
		long literal = insert(MemberStatus.PENDING, members.uniqueLogin(), "q%r" + tag());
		insert(MemberStatus.PENDING, members.uniqueLogin(), "plain" + tag());

		// 키워드 "%" 하나는 전체 일치가 아니다. 다른 팀 데이터가 섞일 수 있어 내 회원만 확인한다
		Page page = page("PENDING", "%", null);

		assertThat(page.ids()).contains(literal);
		for (var item : page.items()) {
			String text = item.path("nickname").asText() + item.path("githubLogin").asText();
			assertThat(text).contains("%");
		}
	}

	@Test
	void 밑줄은_글자_하나_와일드카드가_아니라_글자_그대로_찾는다() {
		long literal = insert(MemberStatus.PENDING, members.uniqueLogin(), "a_" + tag());
		insert(MemberStatus.PENDING, members.uniqueLogin(), "ab" + tag());

		Page page = page("PENDING", "a_" + tag(), null);

		assertThat(page.ids()).containsExactly(literal);
		assertThat(page.totalCount()).isEqualTo(1);
	}

	@Test
	void 이스케이프_문자_느낌표도_글자_그대로_찾는다() {
		long literal = insert(MemberStatus.PENDING, members.uniqueLogin(), "wow!" + tag());
		insert(MemberStatus.PENDING, members.uniqueLogin(), "wow" + tag());

		Page page = page("PENDING", "wow!" + tag(), null);

		assertThat(page.ids()).containsExactly(literal);
	}

	@Test
	void 역슬래시도_글자_그대로_찾는다() {
		long literal = insert(MemberStatus.PENDING, members.uniqueLogin(), "bk\\sl" + tag());
		insert(MemberStatus.PENDING, members.uniqueLogin(), "bksl" + tag());

		Page page = page("PENDING", "bk\\sl" + tag(), null);

		assertThat(page.ids()).containsExactly(literal);
	}

	@Test
	void 따옴표가_든_검색어는_SQL로_해석되지_않는다() {
		insert(MemberStatus.PENDING, members.uniqueLogin(), "safe" + tag());

		GraphQlResponse response = list("PENDING", "' OR '1'='1" + tag(), null);

		assertThat(response.hasErrors()).isFalse();
		assertThat(toPage(response).ids()).isEmpty();
		assertThat(toPage(response).totalCount()).isZero();
	}

	@Test
	void 일치하는_회원이_없으면_빈_목록이다() {
		insertPending(2);

		Page page = page("PENDING", "no-such-" + tag(), null);

		assertThat(page.ids()).isEmpty();
		assertThat(page.nextCursor()).isNull();
		assertThat(page.totalCount()).isZero();
	}

	@Test
	void 검색어가_비었거나_공백뿐이면_검색_조건이_없는_것과_같다() {
		List<Long> inserted = insertPending(2);

		for (String keyword : new String[] {"", "   ", "\t"}) {
			GraphQlResponse response = list("PENDING", keyword, null);
			assertThat(response.hasErrors()).as(keyword).isFalse();
			Page page = toPage(response);
			assertThat(page.totalCount()).isGreaterThanOrEqualTo(inserted.size());
		}
	}

	@Test
	void 검색어는_50자까지_허용하고_51자부터_INVALID_INPUT이다() {
		GraphQlResponse fifty = list("PENDING", "a".repeat(50), null);
		GraphQlResponse fiftyOne = list("PENDING", "a".repeat(51), null);

		assertThat(fifty.hasErrors()).isFalse();
		assertThat(fiftyOne.errorCode()).isEqualTo("INVALID_INPUT");
		assertThat(fiftyOne.errorMessage()).isEqualTo(ErrorCode.INVALID_INPUT.message());
		assertThat(fiftyOne.dataIsNull()).isTrue();
	}

	@Test
	void 검색어_길이는_앞뒤_공백을_뺀_뒤에_센다() {
		assertThat(list("PENDING", "  " + "a".repeat(50) + "  ", null).hasErrors()).isFalse();
		assertThat(list("PENDING", "  " + "a".repeat(51) + "  ", null).errorCode()).isEqualTo("INVALID_INPUT");
	}

	@Test
	void 한글_50자는_허용하고_51자는_거부한다() {
		assertThat(list("PENDING", "가".repeat(50), null).hasErrors()).isFalse();
		assertThat(list("PENDING", "가".repeat(51), null).errorCode()).isEqualTo("INVALID_INPUT");
	}

	@Test
	void 검색어에_걸린_회원이_20명을_넘으면_커서로_이어_읽는다() {
		List<Long> inserted = insertPending(23);

		List<Page> pages = allPages("PENDING", "n" + tag() + "-");

		assertThat(pages).extracting(p -> p.ids().size()).containsExactly(20, 3);
		assertThat(pages.stream().flatMap(p -> p.ids().stream()).toList()).containsExactlyElementsOf(inserted);
	}

}
