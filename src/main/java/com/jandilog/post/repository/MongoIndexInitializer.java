package com.jandilog.post.repository;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexDefinition;
import org.springframework.data.mongodb.core.index.TextIndexDefinition;
import org.springframework.stereotype.Component;

import com.jandilog.post.domain.Post;

// 기동할 때 posts 인덱스를 만든다 (DB명세서 3-1). 같은 정의는 다시 만들어도 변화가 없어서 멱등이고,
// 자동 인덱스 생성 옵션은 쓰지 않는다. Atlas M0에서도 일반·텍스트 인덱스는 쓸 수 있다
@Component
public class MongoIndexInitializer implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(MongoIndexInitializer.class);

	// "sections.$**"는 텍스트 인덱스 키로 쓸 수 없어서(필드명이 $로 시작) 본문 항목을 하나씩 적는다.
	// tags도 넣어 제목·내용·태그를 한 인덱스로 검색한다
	private static final String[] TEXT_FIELDS = {
			"title", "sections.problem", "sections.cause", "sections.solution", "sections.did", "sections.learned",
			"tags" };

	private final MongoTemplate mongo;

	public MongoIndexInitializer(MongoTemplate mongo) {
		this.mongo = mongo;
	}

	@Override
	public void run(ApplicationArguments args) {
		ensure(Post.class, List.of(
				new Index().on("type", Sort.Direction.ASC).on("createdAt", Sort.Direction.DESC).named("posts_type_createdAt"),
				new Index().on("tags", Sort.Direction.ASC).on("createdAt", Sort.Direction.DESC).named("posts_tags_createdAt"),
				new Index().on("authorId", Sort.Direction.ASC).on("writtenDate", Sort.Direction.ASC)
						.named("posts_authorId_writtenDate"),
				new Index().on("teamId", Sort.Direction.ASC).on("createdAt", Sort.Direction.DESC).named("posts_teamId_createdAt"),
				// 한국어는 형태소 분석이 없어 언어를 none으로 두고 공백·기호 단위로만 나눈다
				TextIndexDefinition.builder().named("posts_text").onFields(TEXT_FIELDS).withDefaultLanguage("none")
						.build()));
	}

	// 인덱스 하나가 실패해도 나머지는 계속 만들고, 앱 기동은 막지 않는다
	private void ensure(Class<?> type, List<IndexDefinition> definitions) {
		for (IndexDefinition definition : definitions) {
			try {
				mongo.indexOps(type).createIndex(definition);
			} catch (RuntimeException e) {
				log.error("MongoDB 인덱스를 만들지 못했어요. (collection={})", mongo.getCollectionName(type), e);
			}
		}
	}

}
