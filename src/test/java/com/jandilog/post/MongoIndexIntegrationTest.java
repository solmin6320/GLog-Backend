package com.jandilog.post;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.mongodb.core.index.IndexField;
import org.springframework.data.mongodb.core.index.IndexInfo;

import com.jandilog.post.repository.MongoIndexInitializer;
import com.jandilog.testsupport.board.BoardIntegrationTest;

// posts·comments 인덱스 정의가 DB명세서 3-1, 3-2와 같은지 확인한다
class MongoIndexIntegrationTest extends BoardIntegrationTest {

	@Autowired
	private MongoIndexInitializer initializer;

	private Map<String, IndexInfo> indexes(String collection) {
		Map<String, IndexInfo> byName = new HashMap<>();
		for (IndexInfo info : mongo.indexOps(collection).getIndexInfo()) {
			byName.put(info.getName(), info);
		}
		return byName;
	}

	// "필드:방향" 목록으로 바꾼다
	private static List<String> keys(IndexInfo info) {
		return info.getIndexFields().stream()
				.map(field -> field.getKey() + ":" + field.getDirection())
				.toList();
	}

	@Test
	void posts에_목록_태그_작성자_팀_인덱스가_있다() {
		Map<String, IndexInfo> posts = indexes("posts");

		assertThat(posts).containsKeys("posts_type_createdAt", "posts_tags_createdAt", "posts_authorId_writtenDate",
				"posts_teamId_createdAt");
		assertThat(keys(posts.get("posts_type_createdAt"))).containsExactly("type:ASC", "createdAt:DESC");
		assertThat(keys(posts.get("posts_tags_createdAt"))).containsExactly("tags:ASC", "createdAt:DESC");
		assertThat(keys(posts.get("posts_authorId_writtenDate"))).containsExactly("authorId:ASC", "writtenDate:ASC");
		assertThat(keys(posts.get("posts_teamId_createdAt"))).containsExactly("teamId:ASC", "createdAt:DESC");
	}

	@Test
	void posts_텍스트_인덱스는_제목_본문_항목_태그를_한_인덱스로_묶고_언어는_none이다() {
		IndexInfo text = indexes("posts").get("posts_text");

		assertThat(text).isNotNull();
		assertThat(text.getIndexFields().stream().filter(IndexField::isText).map(IndexField::getKey).toList())
				.containsExactlyInAnyOrder("title", "sections.problem", "sections.cause", "sections.solution",
						"sections.did", "sections.learned", "tags");
		// 형태소 분석이 없으니 언어 규칙 없이 공백·기호 단위로만 나눈다
		assertThat(text.getLanguage()).isEqualTo("none");
	}

	@Test
	void posts에는_텍스트_인덱스가_하나뿐이다() {
		long textIndexes = indexes("posts").values().stream()
				.filter(info -> info.getIndexFields().stream().anyMatch(IndexField::isText))
				.count();

		assertThat(textIndexes).isEqualTo(1);
	}

	@Test
	void comments에_글별_댓글_목록과_작성자_인덱스가_있다() {
		Map<String, IndexInfo> comments = indexes("comments");

		assertThat(comments).containsKeys("comments_postId_createdAt", "comments_authorId");
		assertThat(keys(comments.get("comments_postId_createdAt"))).containsExactly("postId:ASC", "createdAt:ASC");
		assertThat(keys(comments.get("comments_authorId"))).containsExactly("authorId:ASC");
	}

	@Test
	void 인덱스_초기화를_다시_돌려도_인덱스_개수가_그대로이고_오류가_없다() {
		int postsBefore = indexes("posts").size();
		int commentsBefore = indexes("comments").size();

		initializer.run(new DefaultApplicationArguments());

		assertThat(indexes("posts")).hasSize(postsBefore);
		assertThat(indexes("comments")).hasSize(commentsBefore);
	}

}
