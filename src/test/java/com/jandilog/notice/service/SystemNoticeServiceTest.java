package com.jandilog.notice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import com.jandilog.notice.domain.Notice;
import com.jandilog.notice.repository.NoticeRepository;
import com.jandilog.testsupport.auth.MutableClock;

// 잔디 인정 규칙 고정 공지: 앱 기동 때 멱등으로 한 건 보장 (기능명세서 4장, DB명세서 1-15)
@ExtendWith(MockitoExtension.class)
class SystemNoticeServiceTest {

	@Mock
	private NoticeRepository repository;

	private SystemNoticeService service;

	@BeforeEach
	void setUp() {
		MutableClock clock = new MutableClock();
		clock.fixAt(Instant.parse("2026-10-01T03:00:00Z"));
		service = new SystemNoticeService(repository, clock);
	}

	@Test
	void 시스템_공지가_없고_활성_관리자가_있으면_가장_먼저_가입한_관리자를_작성자로_만든다() {
		when(repository.existsBySystemTrue()).thenReturn(false);
		when(repository.findActiveAdminIds(PageRequest.of(0, 1))).thenReturn(List.of(4L));

		boolean created = service.ensureExists();

		assertThat(created).isTrue();
		ArgumentCaptor<Notice> saved = ArgumentCaptor.forClass(Notice.class);
		verify(repository).saveAndFlush(saved.capture());
		Notice notice = saved.getValue();
		assertThat(notice.getAuthorId()).isEqualTo(4L);
		assertThat(notice.getTitle()).isEqualTo("잔디 인정 규칙");
		assertThat(notice.isSystem()).isTrue();
		assertThat(notice.isPinned()).isTrue();
		assertThat(notice.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 10, 1, 12, 0, 0));
		assertThat(notice.getUpdatedAt()).isNull();
	}

	@Test
	void 이미_시스템_공지가_있으면_아무것도_하지_않는다() {
		when(repository.existsBySystemTrue()).thenReturn(true);

		assertThat(service.ensureExists()).isFalse();

		verify(repository, never()).findActiveAdminIds(any());
		verify(repository, never()).saveAndFlush(any());
	}

	@Test
	void 활성_관리자가_없으면_만들지_않고_false다() {
		when(repository.existsBySystemTrue()).thenReturn(false);
		when(repository.findActiveAdminIds(PageRequest.of(0, 1))).thenReturn(List.of());

		assertThat(service.ensureExists()).isFalse();

		verify(repository, never()).saveAndFlush(any());
	}

	@Test
	void 두_번_불러도_한_번만_만든다() {
		when(repository.existsBySystemTrue()).thenReturn(false, true);
		when(repository.findActiveAdminIds(PageRequest.of(0, 1))).thenReturn(List.of(4L));

		assertThat(service.ensureExists()).isTrue();
		assertThat(service.ensureExists()).isFalse();

		verify(repository).saveAndFlush(any(Notice.class));
	}

	@Test
	void 공지_내용은_기능명세서_4장_잔디_인정_규칙_6줄이다() {
		String content = SystemNoticeService.CONTENT;

		assertThat(content.split("\n")).hasSize(6);
		assertThat(content)
				.contains("기본 브랜치(main)에 들어간 커밋만 잔디에 표시된다")
				.contains("다른 브랜치의 커밋은 머지되기 전까지 표시되지 않는다")
				.contains("여러 날의 작업이 머지한 하루로 합쳐진다")
				.contains("포크한 레포의 커밋은 표시되지 않는다")
				.contains("GitHub 설정에서 비공개 기여 표시를 켜야 반영된다")
				.contains("PR과 이슈 작성도 잔디 1칸으로 인정된다");
		assertThat(SystemNoticeService.TITLE).isEqualTo("잔디 인정 규칙");
	}

}
