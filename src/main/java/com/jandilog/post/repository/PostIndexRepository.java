package com.jandilog.post.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.jandilog.post.domain.PostIndex;

public interface PostIndexRepository extends JpaRepository<PostIndex, Long> {

	Optional<PostIndex> findByMongoPostId(String mongoPostId);

}
