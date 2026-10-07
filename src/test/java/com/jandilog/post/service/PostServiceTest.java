package com.jandilog.post.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.post.domain.Post;
import com.jandilog.post.domain.PostSections;
import com.jandilog.post.domain.PostType;
import com.jandilog.post.dto.CreatePostInput;
import com.jandilog.post.dto.PostPageResponse;
import com.jandilog.post.dto.PostResponse;
import com.jandilog.post.dto.PostSectionsInput;
import com.jandilog.post.dto.TagCount;
import com.jandilog.post.dto.UpdatePostInput;
import com.jandilog.post.repository.PostRepository;
import com.jandilog.post.repository.PostSearchCondition;
import com.jandilog.team.service.TeamAccessService;
import com.jandilog.testsupport.auth.MutableClock;

// 글 작성·수정·삭제·목록 규칙. 쓰기 순서(Mongo 먼저, post_index 나중)와 실패 보상은 mock으로 확인한다 (DB명세서 4-1)
@ExtendWith(MockitoExtension.class)
class PostServiceTest {

	private static final long AUTHOR = 7L;
	private static final long OTHER = 8L;
	// 2026-10-04(일) 23:59:59 KST
	private static final Instant SUNDAY_LAST_SECOND = Instant.parse("2026-10-04T14:59:59Z");
	// 2026-10-05(월) 00:00:00 KST
	private static final Instant MONDAY_FIRST_SECOND = Instant.parse("2026-10-04T15:00:00Z");

	@Mock
	private PostRepository postRepository;
	@Mock
	private PostIndexService postIndexService;
	@Mock
	private TeamAccessService teamAccess;

	private MutableClock clock;
	private PostService service;

	@BeforeEach
	void setUp() {
		clock = new MutableClock();
		clock.fixAt(Instant.parse("2026-10-01T03:00:00Z"));
		service = new PostService(postRepository, postIndexService, teamAccess, clock);
	}

	private static CreatePostInput completeTroubleshooting() {
		return new CreatePostInput(PostType.TROUBLESHOOTING, "Redis TTL 문제",
				new PostSectionsInput("문제", "원인", "해결", null, null), List.of("Redis", "spring"),
				List.of("https://github.com/o/r/commit/abc1234"), "3");
	}

	private static Post storedPost(long authorId, PostSections sections, PostType type, Instant createdAt) {
		return Post.create(new ObjectId(Date.from(createdAt)), authorId, 3L, type, "원래 제목", sections,
				List.of("redis"), List.of(), LocalDate.of(2026, 10, 1), createdAt);
	}

	private static Post alivePost() {
		return storedPost(AUTHOR, new PostSections("문제", "", "", null, null), PostType.TROUBLESHOOTING,
				Instant.parse("2026-10-01T03:00:00Z"));
	}

	private static UpdatePostInput updateInput() {
		return new UpdatePostInput("새 제목", new PostSectionsInput("문제", "원인", "해결", "무시될 값", null), List.of("Spring"),
				List.of(), null);
	}

	private static void assertCode(Runnable action, ErrorCode expected) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(expected));
	}

	// ----- 작성 -----

	@Test
	void 글을_쓰면_Mongo에_먼저_저장하고_그_뒤에_post_index를_쓴다() {
		service.create(AUTHOR, completeTroubleshooting());

		InOrder order = inOrder(postRepository, postIndexService);
		ArgumentCaptor<Post> inserted = ArgumentCaptor.forClass(Post.class);
		ArgumentCaptor<Post> registered = ArgumentCaptor.forClass(Post.class);
		order.verify(postRepository).insert(inserted.capture());
		order.verify(postIndexService).register(registered.capture());
		assertThat(registered.getValue()).isSameAs(inserted.getValue());
	}

	@Test
	void 저장된_글은_입력을_정리한_값과_작성자_기록글_여부를_가진다() {
		PostResponse response = service.create(AUTHOR, completeTroubleshooting());

		ArgumentCaptor<Post> inserted = ArgumentCaptor.forClass(Post.class);
		verify(postRepository).insert(inserted.capture());
		Post post = inserted.getValue();
		assertThat(post.getAuthorId()).isEqualTo(AUTHOR);
		assertThat(post.getType()).isEqualTo(PostType.TROUBLESHOOTING);
		assertThat(post.getTitle()).isEqualTo("Redis TTL 문제");
		assertThat(post.getTags()).containsExactly("redis", "spring");
		assertThat(post.getCommitUrls()).containsExactly("https://github.com/o/r/commit/abc1234");
		assertThat(post.getTeamId()).isEqualTo(3L);
		assertThat(post.isRecord()).isTrue();
		assertThat(response.id()).isEqualTo(post.getId().toHexString());
		assertThat(response.isRecord()).isTrue();
	}

	@Test
	void 필수_항목이_빈_글도_저장되지만_기록글이_아니다() {
		CreatePostInput input = new CreatePostInput(PostType.DEVLOG, "일지", new PostSectionsInput(null, null, null, "한 일", " "),
				null, null, null);

		PostResponse response = service.create(AUTHOR, input);

		ArgumentCaptor<Post> registered = ArgumentCaptor.forClass(Post.class);
		verify(postIndexService).register(registered.capture());
		assertThat(registered.getValue().isRecord()).isFalse();
		assertThat(response.isRecord()).isFalse();
		assertThat(response.tags()).isEmpty();
		assertThat(response.teamId()).isNull();
	}

	@Test
	void 작성일은_서버_시각의_KST_날짜이고_일요일_23시_59분_59초는_그날이다() {
		clock.fixAt(SUNDAY_LAST_SECOND);

		PostResponse response = service.create(AUTHOR, completeTroubleshooting());

		assertThat(response.writtenDate()).isEqualTo("2026-10-04");
		assertThat(response.createdAt()).isEqualTo("2026-10-04T23:59:59");
	}

	@Test
	void 월요일_00시_00분_00초에_쓴_글은_다음_날_작성일이다() {
		clock.fixAt(MONDAY_FIRST_SECOND);

		PostResponse response = service.create(AUTHOR, completeTroubleshooting());

		assertThat(response.writtenDate()).isEqualTo("2026-10-05");
		assertThat(response.createdAt()).isEqualTo("2026-10-05T00:00:00");
	}

	@Test
	void UTC로는_전날이어도_KST_날짜를_작성일로_쓴다() {
		// UTC 2026-09-30 16:00 = KST 2026-10-01 01:00
		clock.fixAt(Instant.parse("2026-09-30T16:00:00Z"));

		PostResponse response = service.create(AUTHOR, completeTroubleshooting());

		assertThat(response.writtenDate()).isEqualTo("2026-10-01");
	}

	@Test
	void 새_글의_ObjectId_시각과_작성_시각은_서버_시계를_따른다() {
		clock.fixAt(SUNDAY_LAST_SECOND);

		service.create(AUTHOR, completeTroubleshooting());

		ArgumentCaptor<Post> inserted = ArgumentCaptor.forClass(Post.class);
		verify(postRepository).insert(inserted.capture());
		assertThat(inserted.getValue().getId().getDate().toInstant()).isEqualTo(SUNDAY_LAST_SECOND);
		assertThat(inserted.getValue().getCreatedAt()).isEqualTo(SUNDAY_LAST_SECOND);
		assertThat(inserted.getValue().getUpdatedAt()).isEqualTo(SUNDAY_LAST_SECOND);
	}

	@Test
	void 입력이_잘못되면_Mongo도_post_index도_건드리지_않는다() {
		CreatePostInput blankTitle = new CreatePostInput(PostType.DEVLOG, "  ",
				new PostSectionsInput(null, null, null, "a", "b"), null, null, null);
		CreatePostInput sevenTags = new CreatePostInput(PostType.DEVLOG, "제목",
				new PostSectionsInput(null, null, null, "a", "b"), List.of("1", "2", "3", "4", "5", "6"), null, null);
		CreatePostInput badUrl = new CreatePostInput(PostType.DEVLOG, "제목",
				new PostSectionsInput(null, null, null, "a", "b"), null, List.of("https://example.com"), null);
		CreatePostInput badTeam = new CreatePostInput(PostType.DEVLOG, "제목",
				new PostSectionsInput(null, null, null, "a", "b"), null, null, "abc");

		assertCode(() -> service.create(AUTHOR, blankTitle), ErrorCode.TITLE_REQUIRED);
		assertCode(() -> service.create(AUTHOR, sevenTags), ErrorCode.TAG_LIMIT_EXCEEDED);
		assertCode(() -> service.create(AUTHOR, badUrl), ErrorCode.INVALID_COMMIT_URL);
		assertCode(() -> service.create(AUTHOR, badTeam), ErrorCode.INVALID_INPUT);

		verifyNoInteractions(postRepository, postIndexService);
	}

	@Test
	void 팀을_연결해_쓰면_소속을_확인한_뒤에_Mongo에_저장한다() {
		service.create(AUTHOR, completeTroubleshooting());

		InOrder order = inOrder(teamAccess, postRepository, postIndexService);
		order.verify(teamAccess).requireLinkable(3L, AUTHOR);
		order.verify(postRepository).insert(any());
		order.verify(postIndexService).register(any());
	}

	@Test
	void 연결할_수_없는_팀이면_Mongo도_post_index도_건드리지_않는다() {
		doThrow(new ApiException(ErrorCode.POST_TEAM_INVALID)).when(teamAccess).requireLinkable(3L, AUTHOR);

		assertCode(() -> service.create(AUTHOR, completeTroubleshooting()), ErrorCode.POST_TEAM_INVALID);

		verifyNoInteractions(postRepository, postIndexService);
	}

	@Test
	void post_index_쓰기가_실패하면_방금_쓴_Mongo_글을_지우고_예외를_그대로_올린다() {
		IllegalStateException failure = new IllegalStateException("색인 실패");
		doThrow(failure).when(postIndexService).register(any());

		assertThatThrownBy(() -> service.create(AUTHOR, completeTroubleshooting())).isSameAs(failure);

		ArgumentCaptor<Post> inserted = ArgumentCaptor.forClass(Post.class);
		verify(postRepository).insert(inserted.capture());
		verify(postRepository).remove(inserted.getValue().getId());
	}

	@Test
	void 보상_삭제까지_실패해도_원래_예외가_올라간다() {
		IllegalStateException failure = new IllegalStateException("색인 실패");
		doThrow(failure).when(postIndexService).register(any());
		doThrow(new IllegalStateException("Mongo 장애")).when(postRepository).remove(any());

		assertThatThrownBy(() -> service.create(AUTHOR, completeTroubleshooting())).isSameAs(failure);
	}

	// ----- 수정 -----

	@Test
	void 수정하면_Mongo를_바꾼_뒤_post_index를_맞춘다() {
		Post current = alivePost();
		when(postRepository.findById(current.getId())).thenReturn(Optional.of(current));
		when(postRepository.replaceIfAlive(any())).thenReturn(Optional.of(current));

		service.update(AUTHOR, current.getId().toHexString(), updateInput());

		InOrder order = inOrder(postRepository, postIndexService);
		ArgumentCaptor<Post> replaced = ArgumentCaptor.forClass(Post.class);
		ArgumentCaptor<Post> synced = ArgumentCaptor.forClass(Post.class);
		order.verify(postRepository).replaceIfAlive(replaced.capture());
		order.verify(postIndexService).sync(synced.capture());
		assertThat(synced.getValue()).isSameAs(replaced.getValue());
	}

	@Test
	void 수정해도_작성일_종류_작성자_생성_시각은_그대로이고_기록글_여부는_다시_계산한다() {
		Post current = alivePost();
		assertThat(current.isRecord()).isFalse();
		when(postRepository.findById(current.getId())).thenReturn(Optional.of(current));
		when(postRepository.replaceIfAlive(any())).thenReturn(Optional.of(current));
		// 작성한 지 19일 뒤에 수정
		Instant editedAt = Instant.parse("2026-10-20T10:00:00Z");
		clock.fixAt(editedAt);

		PostResponse response = service.update(AUTHOR, current.getId().toHexString(), updateInput());

		assertThat(response.isRecord()).isTrue();
		assertThat(response.writtenDate()).isEqualTo("2026-10-01");
		assertThat(response.type()).isEqualTo(PostType.TROUBLESHOOTING);
		assertThat(response.authorId()).isEqualTo(AUTHOR);
		assertThat(response.createdAt()).isEqualTo("2026-10-01T12:00:00");
		assertThat(response.updatedAt()).isEqualTo("2026-10-20T19:00:00");
		assertThat(response.title()).isEqualTo("새 제목");
		assertThat(response.tags()).containsExactly("spring");
		// 종류에 맞지 않는 did 값은 버려진다
		assertThat(response.sections()).isEqualTo(new PostSections("문제", "원인", "해결", null, null));
	}

	@Test
	void 수정은_생략한_태그_커밋_링크_팀을_비운다() {
		Post current = alivePost();
		when(postRepository.findById(current.getId())).thenReturn(Optional.of(current));
		when(postRepository.replaceIfAlive(any())).thenReturn(Optional.of(current));
		UpdatePostInput input = new UpdatePostInput("제목", new PostSectionsInput("문제", "원인", "해결", null, null), null, null,
				null);

		PostResponse response = service.update(AUTHOR, current.getId().toHexString(), input);

		assertThat(response.tags()).isEmpty();
		assertThat(response.commitUrls()).isEmpty();
		assertThat(response.teamId()).isNull();
	}

	private static UpdatePostInput updateInputWithTeam(String teamId) {
		return new UpdatePostInput("새 제목", new PostSectionsInput("문제", "원인", "해결", null, null), List.of(), List.of(), teamId);
	}

	@Test
	void 다른_팀으로_바꾸는_수정은_새_팀의_소속을_확인한_뒤에_저장한다() {
		Post current = alivePost();
		when(postRepository.findById(current.getId())).thenReturn(Optional.of(current));
		when(postRepository.replaceIfAlive(any())).thenReturn(Optional.of(current));

		PostResponse response = service.update(AUTHOR, current.getId().toHexString(), updateInputWithTeam("5"));

		InOrder order = inOrder(teamAccess, postRepository);
		order.verify(teamAccess).requireLinkable(5L, AUTHOR);
		order.verify(postRepository).replaceIfAlive(any());
		assertThat(response.teamId()).isEqualTo(5L);
	}

	@Test
	void 연결할_수_없는_팀으로_바꾸는_수정은_Mongo도_post_index도_바꾸지_않는다() {
		Post current = alivePost();
		when(postRepository.findById(current.getId())).thenReturn(Optional.of(current));
		doThrow(new ApiException(ErrorCode.POST_TEAM_INVALID)).when(teamAccess).requireLinkable(5L, AUTHOR);

		assertCode(() -> service.update(AUTHOR, current.getId().toHexString(), updateInputWithTeam("5")),
				ErrorCode.POST_TEAM_INVALID);

		verify(postRepository, never()).replaceIfAlive(any());
		verifyNoInteractions(postIndexService);
	}

	// 팀을 나간 뒤에도 글을 고칠 수 있게 이미 연결된 팀은 다시 보지 않는다 (CLAUDE.md 결정 표 "글 팀 연결")
	@Test
	void 이미_연결된_팀을_그대로_두는_수정은_소속을_다시_확인하지_않는다() {
		Post current = alivePost();
		when(postRepository.findById(current.getId())).thenReturn(Optional.of(current));
		when(postRepository.replaceIfAlive(any())).thenReturn(Optional.of(current));

		PostResponse response = service.update(AUTHOR, current.getId().toHexString(), updateInputWithTeam("3"));

		assertThat(response.teamId()).isEqualTo(3L);
		verifyNoInteractions(teamAccess);
	}

	@Test
	void 팀을_비우는_수정은_소속을_확인하지_않는다() {
		Post current = alivePost();
		when(postRepository.findById(current.getId())).thenReturn(Optional.of(current));
		when(postRepository.replaceIfAlive(any())).thenReturn(Optional.of(current));

		PostResponse response = service.update(AUTHOR, current.getId().toHexString(), updateInput());

		assertThat(response.teamId()).isNull();
		verifyNoInteractions(teamAccess);
	}

	@Test
	void 없는_글이나_잘못된_id를_수정하면_NOT_FOUND다() {
		ObjectId missing = new ObjectId();
		when(postRepository.findById(missing)).thenReturn(Optional.empty());

		assertCode(() -> service.update(AUTHOR, missing.toHexString(), updateInput()), ErrorCode.NOT_FOUND);
		assertCode(() -> service.update(AUTHOR, "not-an-object-id", updateInput()), ErrorCode.NOT_FOUND);
		assertCode(() -> service.update(AUTHOR, null, updateInput()), ErrorCode.NOT_FOUND);
		verify(postRepository, never()).replaceIfAlive(any());
	}

	@Test
	void 삭제된_글을_수정하면_POST_DELETED다() {
		Post deleted = deletedPost();
		when(postRepository.findById(deleted.getId())).thenReturn(Optional.of(deleted));

		assertCode(() -> service.update(AUTHOR, deleted.getId().toHexString(), updateInput()), ErrorCode.POST_DELETED);
		verify(postRepository, never()).replaceIfAlive(any());
		verifyNoInteractions(postIndexService);
	}

	@Test
	void 작성자가_아니면_수정할_수_없다() {
		Post current = alivePost();
		when(postRepository.findById(current.getId())).thenReturn(Optional.of(current));

		assertCode(() -> service.update(OTHER, current.getId().toHexString(), updateInput()), ErrorCode.FORBIDDEN);
		verify(postRepository, never()).replaceIfAlive(any());
		verifyNoInteractions(postIndexService);
	}

	@Test
	void 수정_사이에_글이_삭제되면_POST_DELETED이고_색인은_건드리지_않는다() {
		Post current = alivePost();
		when(postRepository.findById(current.getId())).thenReturn(Optional.of(current));
		when(postRepository.replaceIfAlive(any())).thenReturn(Optional.empty());

		assertCode(() -> service.update(AUTHOR, current.getId().toHexString(), updateInput()), ErrorCode.POST_DELETED);
		verifyNoInteractions(postIndexService);
	}

	@Test
	void 수정_입력이_잘못되면_Mongo를_바꾸지_않는다() {
		Post current = alivePost();
		when(postRepository.findById(current.getId())).thenReturn(Optional.of(current));
		UpdatePostInput blankTitle = new UpdatePostInput(" ", new PostSectionsInput("문제", "원인", "해결", null, null), null,
				null, null);

		assertCode(() -> service.update(AUTHOR, current.getId().toHexString(), blankTitle), ErrorCode.TITLE_REQUIRED);
		verify(postRepository, never()).replaceIfAlive(any());
	}

	@Test
	void 수정_뒤_색인_쓰기가_실패하면_바꾸기_전_글로_되돌리고_예외를_올린다() {
		Post current = alivePost();
		when(postRepository.findById(current.getId())).thenReturn(Optional.of(current));
		when(postRepository.replaceIfAlive(any())).thenReturn(Optional.of(current));
		IllegalStateException failure = new IllegalStateException("색인 실패");
		doThrow(failure).when(postIndexService).sync(any());

		assertThatThrownBy(() -> service.update(AUTHOR, current.getId().toHexString(), updateInput())).isSameAs(failure);

		verify(postRepository).restore(current);
	}

	// ----- 삭제 -----

	@Test
	void 삭제하면_Mongo에_삭제_표시를_먼저_하고_post_index에_삭제_시각을_찍는다() {
		Post current = alivePost();
		when(postRepository.findById(current.getId())).thenReturn(Optional.of(current));
		when(postRepository.softDelete(eq(current.getId()), any())).thenReturn(true);

		service.delete(AUTHOR, current.getId().toHexString());

		InOrder order = inOrder(postRepository, postIndexService);
		order.verify(postRepository).softDelete(current.getId(), clock.instant());
		order.verify(postIndexService).markDeleted(current.getId().toHexString());
	}

	@Test
	void 작성자가_아니면_삭제할_수_없다() {
		Post current = alivePost();
		when(postRepository.findById(current.getId())).thenReturn(Optional.of(current));

		assertCode(() -> service.delete(OTHER, current.getId().toHexString()), ErrorCode.FORBIDDEN);
		verify(postRepository, never()).softDelete(any(), any());
		verifyNoInteractions(postIndexService);
	}

	@Test
	void 이미_삭제된_글을_다시_삭제하면_POST_DELETED다() {
		Post deleted = deletedPost();
		when(postRepository.findById(deleted.getId())).thenReturn(Optional.of(deleted));

		assertCode(() -> service.delete(AUTHOR, deleted.getId().toHexString()), ErrorCode.POST_DELETED);
		verify(postRepository, never()).softDelete(any(), any());
	}

	@Test
	void 없는_글을_삭제하면_NOT_FOUND다() {
		ObjectId missing = new ObjectId();
		when(postRepository.findById(missing)).thenReturn(Optional.empty());

		assertCode(() -> service.delete(AUTHOR, missing.toHexString()), ErrorCode.NOT_FOUND);
		assertCode(() -> service.delete(AUTHOR, "zzz"), ErrorCode.NOT_FOUND);
	}

	@Test
	void 동시에_다른_곳에서_먼저_삭제하면_POST_DELETED이고_색인은_건드리지_않는다() {
		Post current = alivePost();
		when(postRepository.findById(current.getId())).thenReturn(Optional.of(current));
		when(postRepository.softDelete(eq(current.getId()), any())).thenReturn(false);

		assertCode(() -> service.delete(AUTHOR, current.getId().toHexString()), ErrorCode.POST_DELETED);
		verifyNoInteractions(postIndexService);
	}

	@Test
	void 삭제_뒤_색인_쓰기가_실패하면_삭제_표시를_되돌리고_예외를_올린다() {
		Post current = alivePost();
		when(postRepository.findById(current.getId())).thenReturn(Optional.of(current));
		when(postRepository.softDelete(eq(current.getId()), any())).thenReturn(true);
		IllegalStateException failure = new IllegalStateException("색인 실패");
		doThrow(failure).when(postIndexService).markDeleted(any());

		assertThatThrownBy(() -> service.delete(AUTHOR, current.getId().toHexString())).isSameAs(failure);

		verify(postRepository).clearDeleted(current.getId());
	}

	// ----- 상세 -----

	@Test
	void 상세는_삭제되지_않은_글을_돌려준다() {
		Post current = alivePost();
		when(postRepository.findById(current.getId())).thenReturn(Optional.of(current));

		PostResponse response = service.get(current.getId().toHexString());

		assertThat(response.id()).isEqualTo(current.getId().toHexString());
		assertThat(response.title()).isEqualTo("원래 제목");
	}

	@Test
	void 삭제된_글_상세는_404가_아니라_POST_DELETED다() {
		Post deleted = deletedPost();
		when(postRepository.findById(deleted.getId())).thenReturn(Optional.of(deleted));

		assertCode(() -> service.get(deleted.getId().toHexString()), ErrorCode.POST_DELETED);
	}

	@Test
	void 없는_글_상세는_NOT_FOUND다() {
		ObjectId missing = new ObjectId();
		when(postRepository.findById(missing)).thenReturn(Optional.empty());

		assertCode(() -> service.get(missing.toHexString()), ErrorCode.NOT_FOUND);
		assertCode(() -> service.get("12345"), ErrorCode.NOT_FOUND);
	}

	// ----- 목록 -----

	private static List<Post> newestFirst(int count) {
		List<Post> posts = new ArrayList<>();
		Instant newest = Instant.parse("2026-10-01T03:00:00Z");
		for (int i = 0; i < count; i++) {
			posts.add(storedPost(AUTHOR, new PostSections(null, null, null, "한 일", "배운 점"), PostType.DEVLOG,
					newest.minusSeconds(i)));
		}
		return posts;
	}

	@Test
	void 한_페이지는_20개이고_한_건_더_읽어_다음_페이지를_판단한다() {
		when(postRepository.findPage(any(), isNull(), eq(21))).thenReturn(newestFirst(21));

		PostPageResponse page = service.list(PostType.DEVLOG, null, null, null);

		assertThat(PostService.PAGE_SIZE).isEqualTo(20);
		assertThat(page.items()).hasSize(20);
		assertThat(page.nextCursor()).isNotNull();
		// 커서는 이 페이지의 마지막(20번째) 글 id다
		ObjectId last = new ObjectId(page.items().get(19).id());
		assertThat(PostService.decodeCursor(page.nextCursor())).isEqualTo(last);
	}

	@Test
	void 정확히_20개면_다음_페이지가_없어_커서가_null이다() {
		when(postRepository.findPage(any(), isNull(), eq(21))).thenReturn(newestFirst(20));

		PostPageResponse page = service.list(PostType.DEVLOG, null, null, null);

		assertThat(page.items()).hasSize(20);
		assertThat(page.nextCursor()).isNull();
	}

	@Test
	void 글이_없으면_빈_목록과_null_커서다() {
		when(postRepository.findPage(any(), isNull(), eq(21))).thenReturn(List.of());

		PostPageResponse page = service.list(PostType.TROUBLESHOOTING, null, null, null);

		assertThat(page.items()).isEmpty();
		assertThat(page.nextCursor()).isNull();
	}

	@Test
	void 커서를_넘기면_그_글_다음부터_읽는다() {
		ObjectId after = new ObjectId();
		when(postRepository.findPage(any(), eq(after), eq(21))).thenReturn(newestFirst(3));

		PostPageResponse page = service.list(PostType.DEVLOG, null, null, PostService.encodeCursor(after));

		assertThat(page.items()).hasSize(3);
		assertThat(page.nextCursor()).isNull();
	}

	@Test
	void 잘못된_커서는_INVALID_INPUT이고_조회하지_않는다() {
		assertCode(() -> service.list(PostType.DEVLOG, null, null, "!!!"), ErrorCode.INVALID_INPUT);
		verifyNoInteractions(postRepository);
	}

	@Test
	void 목록_조건은_종류와_정규화한_태그와_검색어_단어로_만든다() {
		when(postRepository.findPage(any(), isNull(), eq(21))).thenReturn(List.of());

		PostPageResponse page = service.list(PostType.DEVLOG, "  Redis ", "  캐시   서버 ", null);

		assertThat(page.condition()).isEqualTo(new PostSearchCondition(PostType.DEVLOG, "redis", List.of("캐시", "서버")));
		verify(postRepository).findPage(page.condition(), null, 21);
	}

	@Test
	void 태그와_검색어가_없으면_조건에도_없다() {
		when(postRepository.findPage(any(), isNull(), eq(21))).thenReturn(List.of());

		PostPageResponse page = service.list(PostType.TROUBLESHOOTING, "  ", "", null);

		assertThat(page.condition()).isEqualTo(new PostSearchCondition(PostType.TROUBLESHOOTING, null, List.of()));
	}

	@Test
	void 검색어가_1자거나_51자면_조회하지_않고_오류다() {
		assertCode(() -> service.list(PostType.DEVLOG, null, "a", null), ErrorCode.SEARCH_TOO_SHORT);
		assertCode(() -> service.list(PostType.DEVLOG, null, "a".repeat(51), null), ErrorCode.SEARCH_TOO_LONG);
		verifyNoInteractions(postRepository);
	}

	@Test
	void 건수는_조건_그대로_Mongo에_센다() {
		PostSearchCondition condition = new PostSearchCondition(PostType.DEVLOG, "redis", List.of());
		when(postRepository.count(condition)).thenReturn(37L);

		assertThat(service.count(condition)).isEqualTo(37);
	}

	// ----- 인기 태그 -----

	@Test
	void 인기_태그는_상위_10개를_태그와_글_수로_바꿔_돌려준다() {
		when(postRepository.popularTags(isNull(), eq(10))).thenReturn(List.of(new Document("_id", "redis").append("count", 5),
				new Document("_id", "spring").append("count", 3)));

		List<TagCount> tags = service.popularTags(null);

		assertThat(tags).containsExactly(new TagCount("redis", 5), new TagCount("spring", 3));
	}

	@Test
	void 인기_태그는_종류를_넘기면_그_종류만_센다() {
		when(postRepository.popularTags(PostType.DEVLOG, 10)).thenReturn(List.of());

		assertThat(service.popularTags(PostType.DEVLOG)).isEmpty();
		verify(postRepository).popularTags(PostType.DEVLOG, 10);
	}

	// ----- 커서 -----

	@Test
	void 커서는_마지막_글_id를_Base64url로_감싼_값이고_되돌릴_수_있다() {
		ObjectId id = new ObjectId();

		String cursor = PostService.encodeCursor(id);

		assertThat(cursor).matches("^[A-Za-z0-9_-]+$");
		assertThat(PostService.decodeCursor(cursor)).isEqualTo(id);
	}

	@Test
	void 커서가_없거나_비어_있으면_처음부터다() {
		assertThat(PostService.decodeCursor(null)).isNull();
		assertThat(PostService.decodeCursor("")).isNull();
	}

	@Test
	void ObjectId가_아닌_커서는_INVALID_INPUT이다() {
		for (String cursor : new String[] {"!!!", "abc", "한글", "====", "a+b/",
				PostService.encodeCursor(new ObjectId()).substring(2),
				Base64.getUrlEncoder().withoutPadding().encodeToString("not-hex".getBytes()),
				Base64.getUrlEncoder().withoutPadding().encodeToString("123".getBytes())}) {
			assertCode(() -> PostService.decodeCursor(cursor), ErrorCode.INVALID_INPUT);
		}
	}

	// Mongo 문서에 deletedAt이 채워진 상태
	private static Post deletedPost() {
		Post post = alivePost();
		ReflectionTestUtils.setField(post, "deletedAt", Instant.parse("2026-10-02T00:00:00Z"));
		return post;
	}

}
