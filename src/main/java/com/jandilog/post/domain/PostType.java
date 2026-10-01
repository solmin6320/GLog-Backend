package com.jandilog.post.domain;

// 글 종류. Mongo posts.type, MariaDB post_index.post_type과 같은 값 (DB명세서 1-5, 3-1)
public enum PostType {
	TROUBLESHOOTING,
	DEVLOG
}
