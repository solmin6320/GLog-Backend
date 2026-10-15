package com.jandilog.notice.dto;

import java.time.format.DateTimeFormatter;

import com.jandilog.notice.domain.Notice;

// 공지 한 건. 작성자는 관리자로 고정이라 싣지 않는다 (NT-02). 시각은 KST ISO-8601
public record NoticeResponse(
		Long id,
		String title,
		String content,
		boolean isPinned,
		boolean isSystem,
		String createdAt,
		String updatedAt) {

	public static NoticeResponse from(Notice notice) {
		return new NoticeResponse(notice.getId(), notice.getTitle(), notice.getContent(), notice.isPinned(),
				notice.isSystem(), DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(notice.getCreatedAt()),
				notice.getUpdatedAt() == null ? null : DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(notice.getUpdatedAt()));
	}

}
