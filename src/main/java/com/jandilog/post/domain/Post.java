package com.jandilog.post.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

// MongoDB posts 문서 (DB명세서 3-1). 삭제는 deletedAt만 채우는 소프트 삭제
@Document("posts")
public class Post {

	@Id
	private ObjectId id;

	private long authorId;

	// 연결된 팀. 없으면 null. 팀 소속 검증은 팀 모듈 합류 뒤에 한다
	private Long teamId;

	private PostType type;

	private String title;

	private PostSections sections;

	private List<String> tags = new ArrayList<>();

	private List<String> commitUrls = new ArrayList<>();

	// 필수 항목을 모두 채웠는가. post_index.is_record와 같은 값
	@Field("isRecord")
	private boolean record;

	// 최초 저장일(KST, yyyy-MM-dd). 수정해도 바뀌지 않는다 (Q-11)
	private String writtenDate;

	private Instant createdAt;

	private Instant updatedAt;

	private Instant deletedAt;

	protected Post() {
	}

	public static Post create(ObjectId id, long authorId, Long teamId, PostType type, String title,
			PostSections sections, List<String> tags, List<String> commitUrls, LocalDate writtenDate, Instant now) {
		Post post = new Post();
		post.id = id;
		post.authorId = authorId;
		post.teamId = teamId;
		post.type = type;
		post.title = title;
		post.sections = sections;
		post.tags = new ArrayList<>(tags);
		post.commitUrls = new ArrayList<>(commitUrls);
		post.record = sections.isComplete(type);
		post.writtenDate = writtenDate.toString();
		post.createdAt = now;
		post.updatedAt = now;
		return post;
	}

	// 수정본 복사본. 작성일·작성자·종류·생성 시각은 그대로 두고 isRecord를 다시 계산한다
	public Post edited(Long teamId, String title, PostSections sections, List<String> tags, List<String> commitUrls,
			Instant now) {
		Post copy = new Post();
		copy.id = id;
		copy.authorId = authorId;
		copy.teamId = teamId;
		copy.type = type;
		copy.title = title;
		copy.sections = sections;
		copy.tags = new ArrayList<>(tags);
		copy.commitUrls = new ArrayList<>(commitUrls);
		copy.record = sections.isComplete(type);
		copy.writtenDate = writtenDate;
		copy.createdAt = createdAt;
		copy.updatedAt = now;
		return copy;
	}

	public ObjectId getId() {
		return id;
	}

	public long getAuthorId() {
		return authorId;
	}

	public Long getTeamId() {
		return teamId;
	}

	public PostType getType() {
		return type;
	}

	public String getTitle() {
		return title;
	}

	public PostSections getSections() {
		return sections;
	}

	public List<String> getTags() {
		return tags == null ? List.of() : tags;
	}

	public List<String> getCommitUrls() {
		return commitUrls == null ? List.of() : commitUrls;
	}

	public boolean isRecord() {
		return record;
	}

	// 저장 형식(문자열)과 getter 타입이 어긋나지 않게 getXxx 이름을 쓰지 않는다
	public LocalDate writtenLocalDate() {
		return LocalDate.parse(writtenDate);
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public Instant getDeletedAt() {
		return deletedAt;
	}

	public boolean isDeleted() {
		return deletedAt != null;
	}

}
