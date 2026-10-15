package com.jandilog.post.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

// 글 문서: 기록글 여부 계산과 수정본의 고정 필드 (Q-11 최초 저장일 고정)
class PostTest {

	private static final Instant CREATED = Instant.parse("2026-10-01T03:00:00Z");
	private static final Instant EDITED = Instant.parse("2026-10-05T09:30:00Z");

	private static Post troubleshooting(PostSections sections) {
		return Post.create(new ObjectId(), 7L, 3L, PostType.TROUBLESHOOTING, "제목", sections, List.of("redis"),
				List.of("https://github.com/o/r/commit/abc1234"), LocalDate.of(2026, 10, 1), CREATED);
	}

	@Test
	void 필수_항목을_모두_채워_만들면_기록글이다() {
		Post post = troubleshooting(new PostSections("문제", "원인", "해결", null, null));

		assertThat(post.isRecord()).isTrue();
		assertThat(post.isDeleted()).isFalse();
	}

	@Test
	void 필수_항목이_비면_저장은_되지만_기록글이_아니다() {
		Post post = troubleshooting(new PostSections("문제", "", "해결", null, null));

		assertThat(post.isRecord()).isFalse();
	}

	@Test
	void 작성일은_문자열_yyyy_MM_dd로_저장하고_작성_시각과_수정_시각은_같다() {
		Post post = troubleshooting(new PostSections("문제", "원인", "해결", null, null));

		assertThat(post.writtenLocalDate()).isEqualTo(LocalDate.of(2026, 10, 1));
		assertThat(post.getCreatedAt()).isEqualTo(CREATED);
		assertThat(post.getUpdatedAt()).isEqualTo(CREATED);
		assertThat(post.getDeletedAt()).isNull();
	}

	@Test
	void 수정본은_작성일_작성자_종류_생성_시각을_그대로_둔다() {
		Post original = troubleshooting(new PostSections("문제", "", "해결", null, null));

		Post edited = original.edited(null, "새 제목", new PostSections("문제", "원인", "해결", null, null),
				List.of("spring"), List.of(), EDITED);

		assertThat(edited.getId()).isEqualTo(original.getId());
		assertThat(edited.getAuthorId()).isEqualTo(7L);
		assertThat(edited.getType()).isEqualTo(PostType.TROUBLESHOOTING);
		assertThat(edited.writtenLocalDate()).isEqualTo(LocalDate.of(2026, 10, 1));
		assertThat(edited.getCreatedAt()).isEqualTo(CREATED);
		assertThat(edited.getUpdatedAt()).isEqualTo(EDITED);
	}

	@Test
	void 수정해서_필수_항목을_채우면_기록글이_되지만_작성일은_바뀌지_않는다() {
		Post original = troubleshooting(new PostSections("문제", "", "", null, null));
		assertThat(original.isRecord()).isFalse();

		Post edited = original.edited(3L, "제목", new PostSections("문제", "원인", "해결", null, null), List.of(),
				List.of(), EDITED);

		assertThat(edited.isRecord()).isTrue();
		assertThat(edited.writtenLocalDate()).isEqualTo(LocalDate.of(2026, 10, 1));
	}

	@Test
	void 기록글을_고쳐서_필수_항목을_비우면_기록글이_아니다() {
		Post original = troubleshooting(new PostSections("문제", "원인", "해결", null, null));

		Post edited = original.edited(3L, "제목", new PostSections("문제", "원인", " ", null, null), List.of(),
				List.of(), EDITED);

		assertThat(edited.isRecord()).isFalse();
	}

	@Test
	void 수정본은_태그_커밋_링크_팀을_통째로_바꾼다() {
		Post original = troubleshooting(new PostSections("문제", "원인", "해결", null, null));

		Post edited = original.edited(null, "제목", original.getSections(), List.of(), List.of(), EDITED);

		assertThat(edited.getTags()).isEmpty();
		assertThat(edited.getCommitUrls()).isEmpty();
		assertThat(edited.getTeamId()).isNull();
		// 원본은 그대로
		assertThat(original.getTags()).containsExactly("redis");
		assertThat(original.getTeamId()).isEqualTo(3L);
	}

	@Test
	void 넘겨준_목록을_나중에_바꿔도_글에는_영향이_없다() {
		ArrayList<String> tags = new ArrayList<>(List.of("a"));
		Post post = Post.create(new ObjectId(), 1L, null, PostType.DEVLOG, "제목",
				new PostSections(null, null, null, "한 일", "배운 점"), tags, List.of(), LocalDate.of(2026, 10, 1), CREATED);

		tags.add("b");

		assertThat(post.getTags()).containsExactly("a");
	}

	@Test
	void 태그와_커밋_링크가_없는_글도_빈_목록으로_읽힌다() {
		Post post = Post.create(new ObjectId(), 1L, null, PostType.DEVLOG, "제목",
				new PostSections(null, null, null, "한 일", "배운 점"), List.of(), List.of(), LocalDate.of(2026, 10, 1),
				CREATED);

		assertThat(post.getTags()).isEmpty();
		assertThat(post.getCommitUrls()).isEmpty();
		assertThat(post.getTeamId()).isNull();
	}

}
