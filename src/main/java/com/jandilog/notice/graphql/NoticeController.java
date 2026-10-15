package com.jandilog.notice.graphql;

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;

import com.jandilog.common.security.AuthenticatedMember;
import com.jandilog.notice.dto.NoticeInput;
import com.jandilog.notice.dto.NoticePage;
import com.jandilog.notice.dto.NoticeResponse;
import com.jandilog.notice.service.NoticeService;

// 공지: 조회는 전체 회원, 작성·수정·삭제·고정은 관리자 (기능명세서 4장)
@Controller
public class NoticeController {

	private final NoticeService noticeService;

	public NoticeController(NoticeService noticeService) {
		this.noticeService = noticeService;
	}

	@QueryMapping
	@PreAuthorize("hasRole('MEMBER')")
	public NoticePage notices(@Argument String after) {
		return noticeService.list(after);
	}

	@QueryMapping
	@PreAuthorize("hasRole('MEMBER')")
	public NoticeResponse notice(@Argument String id) {
		return noticeService.get(id);
	}

	@MutationMapping
	@PreAuthorize("hasRole('ADMIN')")
	public NoticeResponse createNotice(@AuthenticationPrincipal AuthenticatedMember admin,
			@Argument NoticeInput input) {
		return noticeService.create(admin.id(), input);
	}

	@MutationMapping
	@PreAuthorize("hasRole('ADMIN')")
	public NoticeResponse updateNotice(@Argument String id, @Argument NoticeInput input) {
		return noticeService.update(id, input);
	}

	@MutationMapping
	@PreAuthorize("hasRole('ADMIN')")
	public NoticeResponse setNoticePinned(@Argument String id, @Argument boolean isPinned) {
		return noticeService.setPinned(id, isPinned);
	}

	@MutationMapping
	@PreAuthorize("hasRole('ADMIN')")
	public boolean deleteNotice(@Argument String id) {
		noticeService.delete(id);
		return true;
	}

}
