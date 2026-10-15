package com.jandilog.post.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.post.domain.Comment;
import com.jandilog.post.domain.Post;
import com.jandilog.post.domain.PostSections;
import com.jandilog.post.domain.PostType;
import com.jandilog.post.dto.CommentResponse;
import com.jandilog.post.repository.CommentRepository;
import com.jandilog.testsupport.auth.MutableClock;

// 댓글 작성·수정·삭제 규칙 (기능명세서 3장, E-31, E-53). 대댓글 없음, 작성자 본인만 수정·삭제
@ExtendWith(MockitoExtension.class)
class CommentServiceTest {

	private static final long AUTHOR = 7L;
	private static final long OTHER = 8L;
	private static final Instant NOW = Instant.parse("2026-10-01T03:00:00Z");

	@Mock
	private CommentRepository commentRepository;
	@Mock
	private PostService postService;

	private MutableClock clock;
	private CommentService service;

	@BeforeEach
	void setUp() {
		clock = new MutableClock();
		clock.fixAt(NOW);
		service = new CommentService(commentRepository, postService, clock);
	}

	private static Post post() {
		return Post.create(new ObjectId(), 1L, null, PostType.DEVLOG, "제목",
				new PostSections(null, null, null, "한 일", "배운 점"), List.of(), List.of(), LocalDate.of(2026, 10, 1), NOW);
	}

	private static Comment comment(ObjectId postId, long authorId) {
		return Comment.create(new ObjectId(Date.from(NOW)), postId, authorId, "원래 댓글", NOW);
	}

	private static void assertCode(Runnable action, ErrorCode expected) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(expected));
	}

	// ----- 작성 -----

	@Test
	void 삭제되지_않은_글에_댓글을_쓰면_정리한_내용으로_저장한다() {
		Post post = post();
		when(postService.loadAlive(post.getId().toHexString())).thenReturn(post);

		CommentResponse response = service.create(AUTHOR, post.getId().toHexString(), "  저도 같은 문제 겪었어요  ");

		ArgumentCaptor<Comment> inserted = ArgumentCaptor.forClass(Comment.class);
		verify(commentRepository).insert(inserted.capture());
		Comment saved = inserted.getValue();
		assertThat(saved.getPostId()).isEqualTo(post.getId());
		assertThat(saved.getAuthorId()).isEqualTo(AUTHOR);
		assertThat(saved.getContent()).isEqualTo("저도 같은 문제 겪었어요");
		assertThat(saved.getCreatedAt()).isEqualTo(NOW);
		assertThat(saved.getUpdatedAt()).isEqualTo(NOW);
		assertThat(saved.getDeletedAt()).isNull();
		assertThat(response.id()).isEqualTo(saved.getId().toHexString());
		assertThat(response.postId()).isEqualTo(post.getId().toHexString());
		assertThat(response.createdAt()).isEqualTo("2026-10-01T12:00:00");
		assertThat(response.updatedAt()).isEqualTo(response.createdAt());
	}

	@Test
	void 삭제된_글에는_댓글을_달_수_없다() {
		String postId = new ObjectId().toHexString();
		when(postService.loadAlive(postId)).thenThrow(new ApiException(ErrorCode.POST_DELETED));

		assertCode(() -> service.create(AUTHOR, postId, "댓글"), ErrorCode.POST_DELETED);
		verifyNoInteractions(commentRepository);
	}

	@Test
	void 없는_글에는_댓글을_달_수_없다() {
		String postId = new ObjectId().toHexString();
		when(postService.loadAlive(postId)).thenThrow(new ApiException(ErrorCode.NOT_FOUND));

		assertCode(() -> service.create(AUTHOR, postId, "댓글"), ErrorCode.NOT_FOUND);
		verifyNoInteractions(commentRepository);
	}

	@Test
	void 댓글이_비었거나_501자면_저장하지_않는다() {
		Post post = post();
		when(postService.loadAlive(post.getId().toHexString())).thenReturn(post);

		assertCode(() -> service.create(AUTHOR, post.getId().toHexString(), "   "), ErrorCode.INVALID_INPUT);
		assertCode(() -> service.create(AUTHOR, post.getId().toHexString(), null), ErrorCode.INVALID_INPUT);
		assertCode(() -> service.create(AUTHOR, post.getId().toHexString(), "a".repeat(501)), ErrorCode.INVALID_INPUT);
		verify(commentRepository, never()).insert(any());
	}

	@Test
	void 댓글은_500자까지_저장한다() {
		Post post = post();
		when(postService.loadAlive(post.getId().toHexString())).thenReturn(post);

		CommentResponse response = service.create(AUTHOR, post.getId().toHexString(), "a".repeat(500));

		assertThat(response.content()).hasSize(500);
	}

	// ----- 수정 -----

	@Test
	void 작성자는_댓글_내용과_수정_시각만_바꾼다() {
		ObjectId postId = new ObjectId();
		Comment original = comment(postId, AUTHOR);
		Instant editedAt = Instant.parse("2026-10-02T03:00:00Z");
		clock.fixAt(editedAt);
		Comment updated = comment(postId, AUTHOR);
		ReflectionTestUtils.setField(updated, "content", "고친 댓글");
		ReflectionTestUtils.setField(updated, "updatedAt", editedAt);
		ReflectionTestUtils.setField(updated, "id", original.getId());
		when(commentRepository.findById(original.getId())).thenReturn(Optional.of(original), Optional.of(updated));
		when(postService.loadAlive(postId.toHexString())).thenReturn(post());
		when(commentRepository.updateContentIfAlive(original.getId(), "고친 댓글", editedAt)).thenReturn(true);

		CommentResponse response = service.update(AUTHOR, original.getId().toHexString(), "  고친 댓글 ");

		assertThat(response.content()).isEqualTo("고친 댓글");
		assertThat(response.createdAt()).isEqualTo("2026-10-01T12:00:00");
		assertThat(response.updatedAt()).isEqualTo("2026-10-02T12:00:00");
	}

	@Test
	void 작성자가_아니면_댓글을_수정할_수_없다() {
		Comment original = comment(new ObjectId(), AUTHOR);
		when(commentRepository.findById(original.getId())).thenReturn(Optional.of(original));

		assertCode(() -> service.update(OTHER, original.getId().toHexString(), "고침"), ErrorCode.FORBIDDEN);
		verify(commentRepository, never()).updateContentIfAlive(any(), any(), any());
	}

	@Test
	void 삭제됐거나_없는_댓글은_수정할_때_NOT_FOUND다() {
		Comment deleted = comment(new ObjectId(), AUTHOR);
		ReflectionTestUtils.setField(deleted, "deletedAt", NOW);
		ObjectId missing = new ObjectId();
		when(commentRepository.findById(deleted.getId())).thenReturn(Optional.of(deleted));
		when(commentRepository.findById(missing)).thenReturn(Optional.empty());

		assertCode(() -> service.update(AUTHOR, deleted.getId().toHexString(), "고침"), ErrorCode.NOT_FOUND);
		assertCode(() -> service.update(AUTHOR, missing.toHexString(), "고침"), ErrorCode.NOT_FOUND);
		assertCode(() -> service.update(AUTHOR, "not-an-id", "고침"), ErrorCode.NOT_FOUND);
		verify(commentRepository, never()).updateContentIfAlive(any(), any(), any());
	}

	@Test
	void 글이_삭제됐으면_댓글을_수정할_수_없다() {
		ObjectId postId = new ObjectId();
		Comment original = comment(postId, AUTHOR);
		when(commentRepository.findById(original.getId())).thenReturn(Optional.of(original));
		when(postService.loadAlive(postId.toHexString())).thenThrow(new ApiException(ErrorCode.POST_DELETED));

		assertCode(() -> service.update(AUTHOR, original.getId().toHexString(), "고침"), ErrorCode.POST_DELETED);
		verify(commentRepository, never()).updateContentIfAlive(any(), any(), any());
	}

	@Test
	void 수정할_내용이_잘못되면_바꾸지_않는다() {
		ObjectId postId = new ObjectId();
		Comment original = comment(postId, AUTHOR);
		when(commentRepository.findById(original.getId())).thenReturn(Optional.of(original));
		when(postService.loadAlive(postId.toHexString())).thenReturn(post());

		assertCode(() -> service.update(AUTHOR, original.getId().toHexString(), " "), ErrorCode.INVALID_INPUT);
		assertCode(() -> service.update(AUTHOR, original.getId().toHexString(), "a".repeat(501)), ErrorCode.INVALID_INPUT);
		verify(commentRepository, never()).updateContentIfAlive(any(), any(), any());
	}

	@Test
	void 수정하는_사이_댓글이_삭제되면_NOT_FOUND다() {
		ObjectId postId = new ObjectId();
		Comment original = comment(postId, AUTHOR);
		when(commentRepository.findById(original.getId())).thenReturn(Optional.of(original));
		when(postService.loadAlive(postId.toHexString())).thenReturn(post());
		when(commentRepository.updateContentIfAlive(any(), any(), any())).thenReturn(false);

		assertCode(() -> service.update(AUTHOR, original.getId().toHexString(), "고침"), ErrorCode.NOT_FOUND);
	}

	// ----- 삭제 -----

	@Test
	void 작성자는_자기_댓글을_소프트_삭제한다() {
		ObjectId postId = new ObjectId();
		Comment original = comment(postId, AUTHOR);
		when(commentRepository.findById(original.getId())).thenReturn(Optional.of(original));
		when(postService.loadAlive(postId.toHexString())).thenReturn(post());
		when(commentRepository.softDelete(original.getId(), NOW)).thenReturn(true);

		service.delete(AUTHOR, original.getId().toHexString());

		verify(commentRepository).softDelete(original.getId(), NOW);
	}

	@Test
	void 작성자가_아니면_댓글을_삭제할_수_없다() {
		Comment original = comment(new ObjectId(), AUTHOR);
		when(commentRepository.findById(original.getId())).thenReturn(Optional.of(original));

		assertCode(() -> service.delete(OTHER, original.getId().toHexString()), ErrorCode.FORBIDDEN);
		verify(commentRepository, never()).softDelete(any(), any());
	}

	@Test
	void 이미_삭제된_댓글을_다시_삭제하면_NOT_FOUND다() {
		Comment deleted = comment(new ObjectId(), AUTHOR);
		ReflectionTestUtils.setField(deleted, "deletedAt", NOW);
		when(commentRepository.findById(deleted.getId())).thenReturn(Optional.of(deleted));

		assertCode(() -> service.delete(AUTHOR, deleted.getId().toHexString()), ErrorCode.NOT_FOUND);
		verify(commentRepository, never()).softDelete(any(), any());
	}

	@Test
	void 글이_삭제됐으면_댓글을_삭제할_수_없다() {
		ObjectId postId = new ObjectId();
		Comment original = comment(postId, AUTHOR);
		when(commentRepository.findById(original.getId())).thenReturn(Optional.of(original));
		when(postService.loadAlive(postId.toHexString())).thenThrow(new ApiException(ErrorCode.POST_DELETED));

		assertCode(() -> service.delete(AUTHOR, original.getId().toHexString()), ErrorCode.POST_DELETED);
		verify(commentRepository, never()).softDelete(any(), any());
	}

	@Test
	void 삭제하는_사이_다른_곳에서_먼저_지우면_NOT_FOUND다() {
		ObjectId postId = new ObjectId();
		Comment original = comment(postId, AUTHOR);
		when(commentRepository.findById(original.getId())).thenReturn(Optional.of(original));
		when(postService.loadAlive(postId.toHexString())).thenReturn(post());
		when(commentRepository.softDelete(any(), any())).thenReturn(false);

		assertCode(() -> service.delete(AUTHOR, original.getId().toHexString()), ErrorCode.NOT_FOUND);
	}

	// ----- 글 상세용 목록·건수 -----

	@Test
	void 글별_댓글_목록은_글_id로_묶어_저장소가_준_순서를_지킨다() {
		ObjectId postA = new ObjectId();
		ObjectId postB = new ObjectId();
		Comment a1 = comment(postA, AUTHOR);
		Comment b1 = comment(postB, OTHER);
		Comment a2 = comment(postA, OTHER);
		when(commentRepository.findAliveByPostIds(List.of(postA, postB))).thenReturn(List.of(a1, b1, a2));

		Map<ObjectId, List<CommentResponse>> byPost = service.findByPostIds(List.of(postA, postB));

		assertThat(byPost.get(postA)).extracting(CommentResponse::id)
				.containsExactly(a1.getId().toHexString(), a2.getId().toHexString());
		assertThat(byPost.get(postB)).extracting(CommentResponse::id).containsExactly(b1.getId().toHexString());
	}

	@Test
	void 댓글이_없는_글은_목록_맵에_들어가지_않는다() {
		ObjectId postA = new ObjectId();
		when(commentRepository.findAliveByPostIds(List.of(postA))).thenReturn(List.of());

		assertThat(service.findByPostIds(List.of(postA))).doesNotContainKey(postA);
	}

	@Test
	void 댓글_수는_저장소_집계를_그대로_돌려준다() {
		ObjectId postA = new ObjectId();
		when(commentRepository.countAliveByPostIds(List.of(postA))).thenReturn(Map.of(postA, 4));

		assertThat(service.countByPostIds(List.of(postA))).containsEntry(postA, 4);
	}

}
