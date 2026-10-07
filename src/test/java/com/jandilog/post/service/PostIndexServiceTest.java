package com.jandilog.post.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.jandilog.post.domain.Post;
import com.jandilog.post.domain.PostIndex;
import com.jandilog.post.domain.PostSections;
import com.jandilog.post.domain.PostType;
import com.jandilog.post.repository.PostIndexRepository;
import com.jandilog.testsupport.auth.MutableClock;

// MariaDB post_index 쓰기: 기록글 여부·작성일·삭제 시각 동기화 (DB명세서 1-5, Q-11)
@ExtendWith(MockitoExtension.class)
class PostIndexServiceTest {

	private static final Instant CREATED = Instant.parse("2026-10-04T14:59:59Z");

	@Mock
	private PostIndexRepository repository;

	private MutableClock clock;
	private PostIndexService service;

	@BeforeEach
	void setUp() {
		clock = new MutableClock();
		clock.fixAt(Instant.parse("2026-10-06T01:00:00Z"));
		service = new PostIndexService(repository, clock);
	}

	private static Post post(PostSections sections, PostType type, Long teamId) {
		return Post.create(new ObjectId(), 7L, teamId, type, "제목", sections, List.of(), List.of(),
				LocalDate.of(2026, 10, 4), CREATED);
	}

	private static Post troubleshooting(boolean complete) {
		return post(new PostSections("문제", complete ? "원인" : "", "해결", null, null), PostType.TROUBLESHOOTING, 3L);
	}

	@Test
	void 새_글은_작성자_종류_기록글_여부_작성일_팀을_색인에_적는다() {
		Post post = troubleshooting(true);

		service.register(post);

		ArgumentCaptor<PostIndex> saved = ArgumentCaptor.forClass(PostIndex.class);
		verify(repository).saveAndFlush(saved.capture());
		PostIndex index = saved.getValue();
		assertThat(index.getMongoPostId()).isEqualTo(post.getId().toHexString());
		assertThat(index.getAuthorId()).isEqualTo(7L);
		assertThat(index.getTeamId()).isEqualTo(3L);
		assertThat(index.getPostType()).isEqualTo(PostType.TROUBLESHOOTING);
		assertThat(index.isRecord()).isTrue();
		assertThat(index.getWrittenDate()).isEqualTo(LocalDate.of(2026, 10, 4));
		assertThat(index.getDeletedAt()).isNull();
	}

	@Test
	void 필수_항목이_빈_글은_기록글_아님으로_적는다() {
		service.register(troubleshooting(false));

		ArgumentCaptor<PostIndex> saved = ArgumentCaptor.forClass(PostIndex.class);
		verify(repository).saveAndFlush(saved.capture());
		assertThat(saved.getValue().isRecord()).isFalse();
	}

	@Test
	void 색인의_생성_시각은_서버_시계_KST다() {
		service.register(troubleshooting(true));

		ArgumentCaptor<PostIndex> saved = ArgumentCaptor.forClass(PostIndex.class);
		verify(repository).saveAndFlush(saved.capture());
		assertThat(saved.getValue().getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 10, 6, 10, 0, 0));
	}

	@Test
	void 수정하면_기록글_여부와_팀만_바꾸고_작성일은_그대로다() {
		Post original = troubleshooting(false);
		PostIndex index = PostIndex.of(original.getId().toHexString(), 7L, 3L, PostType.TROUBLESHOOTING, false,
				LocalDate.of(2026, 10, 4), LocalDateTime.of(2026, 10, 4, 23, 59, 59));
		when(repository.findByMongoPostId(original.getId().toHexString())).thenReturn(Optional.of(index));
		// 닷새 뒤 필수 항목을 채우고 팀을 풀어 수정
		Post edited = original.edited(null, "제목", new PostSections("문제", "원인", "해결", null, null), List.of(), List.of(),
				Instant.parse("2026-10-09T01:00:00Z"));

		service.sync(edited);

		assertThat(index.isRecord()).isTrue();
		assertThat(index.getTeamId()).isNull();
		assertThat(index.getWrittenDate()).isEqualTo(LocalDate.of(2026, 10, 4));
		assertThat(index.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 10, 4, 23, 59, 59));
		verify(repository, never()).saveAndFlush(any());
	}

	@Test
	void 기록글을_고쳐서_필수_항목을_비우면_색인도_기록글_아님이_된다() {
		Post original = troubleshooting(true);
		PostIndex index = PostIndex.of(original.getId().toHexString(), 7L, 3L, PostType.TROUBLESHOOTING, true,
				LocalDate.of(2026, 10, 4), LocalDateTime.of(2026, 10, 4, 23, 59, 59));
		when(repository.findByMongoPostId(original.getId().toHexString())).thenReturn(Optional.of(index));
		Post edited = original.edited(3L, "제목", new PostSections("문제", "원인", "", null, null), List.of(), List.of(),
				Instant.parse("2026-10-05T01:00:00Z"));

		service.sync(edited);

		assertThat(index.isRecord()).isFalse();
	}

	@Test
	void 색인이_빠져_있던_글을_수정하면_원래_작성일과_생성_시각으로_채운다() {
		Post original = troubleshooting(false);
		when(repository.findByMongoPostId(original.getId().toHexString())).thenReturn(Optional.empty());
		Post edited = original.edited(3L, "제목", new PostSections("문제", "원인", "해결", null, null), List.of(), List.of(),
				Instant.parse("2026-10-09T01:00:00Z"));

		service.sync(edited);

		ArgumentCaptor<PostIndex> saved = ArgumentCaptor.forClass(PostIndex.class);
		verify(repository).saveAndFlush(saved.capture());
		PostIndex index = saved.getValue();
		assertThat(index.getMongoPostId()).isEqualTo(original.getId().toHexString());
		assertThat(index.isRecord()).isTrue();
		assertThat(index.getWrittenDate()).isEqualTo(LocalDate.of(2026, 10, 4));
		// 글의 최초 저장 시각(KST)을 쓴다. 수정 시각이 아니다
		assertThat(index.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 10, 4, 23, 59, 59));
	}

	@Test
	void 삭제하면_삭제_시각을_서버_시계로_찍는다() {
		PostIndex index = PostIndex.of("a".repeat(24), 7L, null, PostType.DEVLOG, true, LocalDate.of(2026, 10, 4),
				LocalDateTime.of(2026, 10, 4, 23, 59, 59));
		when(repository.findByMongoPostId("a".repeat(24))).thenReturn(Optional.of(index));

		service.markDeleted("a".repeat(24));

		assertThat(index.getDeletedAt()).isEqualTo(LocalDateTime.of(2026, 10, 6, 10, 0, 0));
		// 기록글 여부와 작성일은 그대로 남아 판정이 삭제 시각으로만 제외한다
		assertThat(index.isRecord()).isTrue();
		assertThat(index.getWrittenDate()).isEqualTo(LocalDate.of(2026, 10, 4));
	}

	@Test
	void 색인이_없는_글을_삭제해도_오류가_나지_않는다() {
		when(repository.findByMongoPostId("b".repeat(24))).thenReturn(Optional.empty());

		service.markDeleted("b".repeat(24));

		verify(repository, never()).saveAndFlush(any());
	}

	@Test
	void 보상으로_삭제_표시를_되돌리면_삭제_시각이_비워진다() {
		PostIndex index = PostIndex.of("c".repeat(24), 7L, null, PostType.DEVLOG, true, LocalDate.of(2026, 10, 4),
				LocalDateTime.of(2026, 10, 4, 23, 59, 59));
		index.markDeleted(LocalDateTime.of(2026, 10, 5, 0, 0, 0));
		when(repository.findByMongoPostId("c".repeat(24))).thenReturn(Optional.of(index));

		service.clearDeleted("c".repeat(24));

		assertThat(index.getDeletedAt()).isNull();
	}

}
