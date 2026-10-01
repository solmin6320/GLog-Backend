package com.jandilog.post.service;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jandilog.post.domain.Post;
import com.jandilog.post.domain.PostIndex;
import com.jandilog.post.repository.PostIndexRepository;

// MariaDB post_index 쓰기. 호출하는 쪽이 MongoDB를 먼저 쓴 뒤 부른다 (DB명세서 4-1)
@Service
public class PostIndexService {

	private final PostIndexRepository postIndexRepository;
	private final Clock clock;

	public PostIndexService(PostIndexRepository postIndexRepository, Clock clock) {
		this.postIndexRepository = postIndexRepository;
		this.clock = clock;
	}

	// 새 글: 작성자·기록글 여부·최초 저장일(KST)을 적는다
	@Transactional
	public void register(Post post) {
		postIndexRepository.saveAndFlush(PostIndex.of(post.getId().toHexString(), post.getAuthorId(), post.getTeamId(),
				post.getType(), post.isRecord(), post.writtenLocalDate(), LocalDateTime.now(clock)));
	}

	// 수정: 기록글 여부와 팀 연결을 다시 적는다. 작성일은 그대로. 색인이 빠져 있던 글은 이때 채운다
	@Transactional
	public void sync(Post post) {
		String mongoPostId = post.getId().toHexString();
		postIndexRepository.findByMongoPostId(mongoPostId).ifPresentOrElse(
				index -> index.update(post.isRecord(), post.getTeamId()),
				() -> postIndexRepository.saveAndFlush(PostIndex.of(mongoPostId, post.getAuthorId(), post.getTeamId(),
						post.getType(), post.isRecord(), post.writtenLocalDate(),
						LocalDateTime.ofInstant(post.getCreatedAt(), clock.getZone()))));
	}

	// 삭제: deleted_at을 찍는다. 확정된 판정은 건드리지 않고, 판정 전에 삭제된 글은 판정이 제외한다 (Q-11)
	@Transactional
	public void markDeleted(String mongoPostId) {
		postIndexRepository.findByMongoPostId(mongoPostId)
				.ifPresent(index -> index.markDeleted(LocalDateTime.now(clock)));
	}

	// 보상용: 삭제 표시를 되돌린다
	@Transactional
	public void clearDeleted(String mongoPostId) {
		postIndexRepository.findByMongoPostId(mongoPostId).ifPresent(PostIndex::clearDeleted);
	}

}
