package com.jandilog.notice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.notice.domain.Notice;
import com.jandilog.notice.dto.NoticeInput;
import com.jandilog.notice.dto.NoticePage;
import com.jandilog.notice.dto.NoticeResponse;
import com.jandilog.notice.repository.NoticeRepository;
import com.jandilog.testsupport.auth.MutableClock;

// 공지 입력 규칙, 고정, 시스템 공지 삭제 거부, 커서 페이지 (기능명세서 4장, EX-AD01-02, E-57)
@ExtendWith(MockitoExtension.class)
class NoticeServiceTest {

	private static final long ADMIN = 1L;
	// 2026-10-01 12:00 KST
	private static final Instant NOW = Instant.parse("2026-10-01T03:00:00Z");
	private static final LocalDateTime NOW_KST = LocalDateTime.of(2026, 10, 1, 12, 0, 0);

	@Mock
	private NoticeRepository repository;

	private MutableClock clock;
	private NoticeService service;

	@BeforeEach
	void setUp() {
		clock = new MutableClock();
		clock.fixAt(NOW);
		service = new NoticeService(repository, clock);
	}

	private static Notice notice(long id, boolean pinned, LocalDateTime createdAt) {
		Notice notice = Notice.create("공지 " + id, "내용 " + id, pinned, ADMIN, createdAt);
		ReflectionTestUtils.setField(notice, "id", id);
		return notice;
	}

	private static Notice systemNotice(long id) {
		Notice notice = Notice.createSystem("잔디 인정 규칙", "내용", ADMIN, NOW_KST);
		ReflectionTestUtils.setField(notice, "id", id);
		return notice;
	}

	private static void assertCode(Runnable action, ErrorCode expected) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(expected));
	}

	// ----- 작성 -----

	@Test
	void 작성하면_제목과_내용의_앞뒤_공백을_지우고_작성자와_서버_시각을_저장한다() {
		when(repository.saveAndFlush(any(Notice.class))).thenAnswer(call -> call.getArgument(0));

		NoticeResponse response = service.create(ADMIN, new NoticeInput("  점검 안내  ", "\n내용입니다\n", true));

		ArgumentCaptor<Notice> saved = ArgumentCaptor.forClass(Notice.class);
		verify(repository).saveAndFlush(saved.capture());
		Notice notice = saved.getValue();
		assertThat(notice.getTitle()).isEqualTo("점검 안내");
		assertThat(notice.getContent()).isEqualTo("내용입니다");
		assertThat(notice.isPinned()).isTrue();
		assertThat(notice.isSystem()).isFalse();
		assertThat(notice.getAuthorId()).isEqualTo(ADMIN);
		assertThat(notice.getCreatedAt()).isEqualTo(NOW_KST);
		assertThat(notice.getUpdatedAt()).isNull();
		assertThat(response.createdAt()).isEqualTo("2026-10-01T12:00:00");
		assertThat(response.updatedAt()).isNull();
		assertThat(response.isSystem()).isFalse();
	}

	@Test
	void 고정_여부를_생략하면_고정하지_않는다() {
		when(repository.saveAndFlush(any(Notice.class))).thenAnswer(call -> call.getArgument(0));

		assertThat(service.create(ADMIN, new NoticeInput("제목", "내용", null)).isPinned()).isFalse();
		assertThat(service.create(ADMIN, new NoticeInput("제목", "내용", false)).isPinned()).isFalse();
		assertThat(service.create(ADMIN, new NoticeInput("제목", "내용", true)).isPinned()).isTrue();
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "\t", "\n", " \n "})
	void 제목이_비었으면_INVALID_INPUT이다(String title) {
		assertCode(() -> service.create(ADMIN, new NoticeInput(title, "내용", false)), ErrorCode.INVALID_INPUT);
		verifyNoInteractions(repository);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "\t", "\n", " \n "})
	void 내용이_비었으면_INVALID_INPUT이다(String content) {
		assertCode(() -> service.create(ADMIN, new NoticeInput("제목", content, false)), ErrorCode.INVALID_INPUT);
		verifyNoInteractions(repository);
	}

	@Test
	void 제목은_200자까지_허용하고_201자부터_INVALID_INPUT이다() {
		when(repository.saveAndFlush(any(Notice.class))).thenAnswer(call -> call.getArgument(0));

		assertThat(service.create(ADMIN, new NoticeInput("a".repeat(200), "내용", false)).title()).hasSize(200);
		assertCode(() -> service.create(ADMIN, new NoticeInput("a".repeat(201), "내용", false)), ErrorCode.INVALID_INPUT);
	}

	@Test
	void 제목_길이는_앞뒤_공백을_뺀_글자_수로_센다() {
		when(repository.saveAndFlush(any(Notice.class))).thenAnswer(call -> call.getArgument(0));

		assertThat(service.create(ADMIN, new NoticeInput("  " + "가".repeat(200) + "  ", "내용", false)).title()).hasSize(200);
		assertCode(() -> service.create(ADMIN, new NoticeInput("가".repeat(201), "내용", false)), ErrorCode.INVALID_INPUT);
		assertThat(service.create(ADMIN, new NoticeInput("😀".repeat(200), "내용", false)).title())
				.isEqualTo("😀".repeat(200));
	}

	@Test
	void 내용은_TEXT_한도_65535바이트까지_허용한다() {
		when(repository.saveAndFlush(any(Notice.class))).thenAnswer(call -> call.getArgument(0));
		String exactly = "a".repeat(65_535);

		assertThat(service.create(ADMIN, new NoticeInput("제목", exactly, false)).content()).isEqualTo(exactly);
		assertCode(() -> service.create(ADMIN, new NoticeInput("제목", "a".repeat(65_536), false)),
				ErrorCode.INVALID_INPUT);
	}

	@Test
	void 내용_한도는_글자_수가_아니라_UTF8_바이트로_센다() {
		when(repository.saveAndFlush(any(Notice.class))).thenAnswer(call -> call.getArgument(0));
		// 한글은 3바이트: 21,845자 = 65,535바이트
		String fits = "가".repeat(21_845);
		String over = "가".repeat(21_846);
		assertThat(fits.getBytes(StandardCharsets.UTF_8)).hasSize(65_535);

		assertThat(service.create(ADMIN, new NoticeInput("제목", fits, false)).content()).isEqualTo(fits);
		assertCode(() -> service.create(ADMIN, new NoticeInput("제목", over, false)), ErrorCode.INVALID_INPUT);
	}

	// ----- 수정 -----

	@Test
	void 수정하면_제목_내용_고정을_통째로_바꾸고_수정_시각을_찍는다() {
		Notice existing = notice(5, true, LocalDateTime.of(2026, 9, 1, 9, 0, 0));
		when(repository.findById(5L)).thenReturn(Optional.of(existing));
		when(repository.saveAndFlush(existing)).thenReturn(existing);

		NoticeResponse response = service.update("5", new NoticeInput(" 새 제목 ", " 새 내용 ", false));

		assertThat(response.title()).isEqualTo("새 제목");
		assertThat(response.content()).isEqualTo("새 내용");
		assertThat(response.isPinned()).isFalse();
		assertThat(response.updatedAt()).isEqualTo("2026-10-01T12:00:00");
		// 작성 시각과 작성자는 그대로
		assertThat(response.createdAt()).isEqualTo("2026-09-01T09:00:00");
		assertThat(existing.getAuthorId()).isEqualTo(ADMIN);
	}

	@Test
	void 수정할_때_고정_여부를_생략하면_고정이_풀린다() {
		Notice existing = notice(5, true, LocalDateTime.of(2026, 9, 1, 9, 0, 0));
		when(repository.findById(5L)).thenReturn(Optional.of(existing));
		when(repository.saveAndFlush(existing)).thenReturn(existing);

		NoticeResponse response = service.update("5", new NoticeInput("제목", "내용", null));

		assertThat(response.isPinned()).isFalse();
	}

	@Test
	void 수정해도_시스템_공지_표시는_바뀌지_않는다() {
		Notice system = systemNotice(9);
		when(repository.findById(9L)).thenReturn(Optional.of(system));
		when(repository.saveAndFlush(system)).thenReturn(system);

		NoticeResponse response = service.update("9", new NoticeInput("고친 제목", "고친 내용", true));

		assertThat(response.isSystem()).isTrue();
	}

	@Test
	void 수정_입력이_잘못되면_저장하지_않는다() {
		Notice existing = notice(5, true, LocalDateTime.of(2026, 9, 1, 9, 0, 0));
		when(repository.findById(5L)).thenReturn(Optional.of(existing));

		assertCode(() -> service.update("5", new NoticeInput(" ", "내용", true)), ErrorCode.INVALID_INPUT);
		assertCode(() -> service.update("5", new NoticeInput("제목", " ", true)), ErrorCode.INVALID_INPUT);
		verify(repository, never()).saveAndFlush(any());
		assertThat(existing.getTitle()).isEqualTo("공지 5");
		assertThat(existing.isPinned()).isTrue();
		assertThat(existing.getUpdatedAt()).isNull();
	}

	// ----- 고정 -----

	@Test
	void 고정을_켜고_끈다() {
		Notice existing = notice(5, false, LocalDateTime.of(2026, 9, 1, 9, 0, 0));
		when(repository.findById(5L)).thenReturn(Optional.of(existing));
		when(repository.saveAndFlush(existing)).thenReturn(existing);

		assertThat(service.setPinned("5", true).isPinned()).isTrue();
		assertThat(service.setPinned("5", false).isPinned()).isFalse();
	}

	@Test
	void 고정만_바꾸면_수정_시각은_그대로다() {
		Notice existing = notice(5, false, LocalDateTime.of(2026, 9, 1, 9, 0, 0));
		when(repository.findById(5L)).thenReturn(Optional.of(existing));
		when(repository.saveAndFlush(existing)).thenReturn(existing);

		NoticeResponse response = service.setPinned("5", true);

		assertThat(response.updatedAt()).isNull();
		assertThat(response.title()).isEqualTo("공지 5");
	}

	// ----- 삭제 -----

	@Test
	void 일반_공지는_삭제한다() {
		Notice existing = notice(5, false, LocalDateTime.of(2026, 9, 1, 9, 0, 0));
		when(repository.findById(5L)).thenReturn(Optional.of(existing));

		service.delete("5");

		verify(repository).delete(existing);
		verify(repository).flush();
	}

	@Test
	void 잔디_인정_규칙_공지는_삭제를_거부한다() {
		Notice system = systemNotice(9);
		when(repository.findById(9L)).thenReturn(Optional.of(system));

		assertCode(() -> service.delete("9"), ErrorCode.SYSTEM_NOTICE_UNDELETABLE);
		verify(repository, never()).delete(any(Notice.class));
		verify(repository, never()).flush();
	}

	@Test
	void 시스템_공지_삭제_거부_문구는_판정_기준_안내다() {
		assertThat(ErrorCode.SYSTEM_NOTICE_UNDELETABLE.message()).isEqualTo("이 공지는 판정 기준 안내라 삭제할 수 없어요.");
		assertThat(ErrorCode.SYSTEM_NOTICE_UNDELETABLE.httpStatus()).isEqualTo(403);
	}

	// ----- 조회 -----

	@Test
	void 없는_공지는_NOT_FOUND다() {
		when(repository.findById(404L)).thenReturn(Optional.empty());

		assertCode(() -> service.get("404"), ErrorCode.NOT_FOUND);
		assertCode(() -> service.update("404", new NoticeInput("제목", "내용", false)), ErrorCode.NOT_FOUND);
		assertCode(() -> service.setPinned("404", true), ErrorCode.NOT_FOUND);
		assertCode(() -> service.delete("404"), ErrorCode.NOT_FOUND);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"abc", "1.5", "1 2", "-", "99999999999999999999", "0x10"})
	void 숫자가_아닌_공지_id는_저장소를_열지_않고_NOT_FOUND다(String id) {
		assertCode(() -> service.get(id), ErrorCode.NOT_FOUND);
		assertCode(() -> service.delete(id), ErrorCode.NOT_FOUND);
		verifyNoInteractions(repository);
	}

	@Test
	void 상세는_공지_한_건을_돌려준다() {
		Notice existing = notice(5, true, LocalDateTime.of(2026, 9, 1, 9, 0, 0));
		when(repository.findById(5L)).thenReturn(Optional.of(existing));

		NoticeResponse response = service.get("5");

		assertThat(response.id()).isEqualTo(5L);
		assertThat(response.title()).isEqualTo("공지 5");
		assertThat(response.isPinned()).isTrue();
		assertThat(response.isSystem()).isFalse();
	}

	// ----- 목록 -----

	private static List<Notice> notices(int count) {
		List<Notice> rows = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			rows.add(notice(1000 - i, false, LocalDateTime.of(2026, 9, 1, 9, 0, 0).minusMinutes(i)));
		}
		return rows;
	}

	@Test
	void 첫_페이지는_한_건을_더_읽어_20개만_돌려주고_다음_커서를_만든다() {
		List<Notice> rows = notices(21);
		when(repository.findFirstPage(PageRequest.of(0, 21))).thenReturn(rows);

		NoticePage page = service.list(null);

		assertThat(NoticeService.PAGE_SIZE).isEqualTo(20);
		assertThat(page.items()).hasSize(20);
		assertThat(page.items().get(0).id()).isEqualTo(1000L);
		assertThat(page.items().get(19).id()).isEqualTo(981L);
		assertThat(page.nextCursor()).isEqualTo(NoticeService.Cursor.of(rows.get(19)).encode());
	}

	@Test
	void 정확히_20개면_마지막_페이지라_커서가_null이다() {
		when(repository.findFirstPage(PageRequest.of(0, 21))).thenReturn(notices(20));

		NoticePage page = service.list(null);

		assertThat(page.items()).hasSize(20);
		assertThat(page.nextCursor()).isNull();
	}

	@Test
	void 공지가_없으면_빈_목록이다() {
		when(repository.findFirstPage(PageRequest.of(0, 21))).thenReturn(List.of());

		NoticePage page = service.list("");

		assertThat(page.items()).isEmpty();
		assertThat(page.nextCursor()).isNull();
	}

	@Test
	void 빈_문자열_커서는_첫_페이지다() {
		when(repository.findFirstPage(PageRequest.of(0, 21))).thenReturn(notices(2));

		assertThat(service.list("").items()).hasSize(2);
		verify(repository, never()).findPageAfter(anyBoolean(), any(), anyLong(), any());
	}

	@Test
	void 커서를_넘기면_커서가_가리키는_고정_여부_시각_id_뒤부터_읽는다() {
		Notice last = notice(77, true, LocalDateTime.of(2026, 9, 3, 10, 30, 15));
		String cursor = NoticeService.Cursor.of(last).encode();
		when(repository.findPageAfter(true, LocalDateTime.of(2026, 9, 3, 10, 30, 15), 77L, PageRequest.of(0, 21)))
				.thenReturn(notices(3));

		NoticePage page = service.list(cursor);

		assertThat(page.items()).hasSize(3);
		assertThat(page.nextCursor()).isNull();
	}

	@Test
	void 잘못된_커서는_INVALID_INPUT이고_조회하지_않는다() {
		assertCode(() -> service.list("!!!"), ErrorCode.INVALID_INPUT);
		assertCode(() -> service.list("abc"), ErrorCode.INVALID_INPUT);
		verifyNoInteractions(repository);
	}

}
