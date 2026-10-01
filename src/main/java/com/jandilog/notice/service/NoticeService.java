package com.jandilog.notice.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.notice.domain.Notice;
import com.jandilog.notice.dto.NoticeInput;
import com.jandilog.notice.dto.NoticePage;
import com.jandilog.notice.dto.NoticeResponse;
import com.jandilog.notice.repository.NoticeRepository;

// 공지 목록·상세(전체 회원)와 작성·수정·삭제·고정(관리자) (기능명세서 4장)
@Service
public class NoticeService {

	static final int PAGE_SIZE = 20;
	private static final int TITLE_MAX_LENGTH = 200;
	// notice.content는 TEXT(65,535바이트)
	private static final int CONTENT_MAX_BYTES = 65_535;

	private final NoticeRepository noticeRepository;
	private final Clock clock;

	public NoticeService(NoticeRepository noticeRepository, Clock clock) {
		this.noticeRepository = noticeRepository;
		this.clock = clock;
	}

	// 고정 공지가 위, 그 아래 최신순. 커서는 (고정 여부, 작성 시각, id)를 감싼 불투명 문자열
	@Transactional(readOnly = true)
	public NoticePage list(String cursor) {
		PageRequest pageRequest = PageRequest.of(0, PAGE_SIZE + 1);
		List<Notice> rows;
		if (cursor == null || cursor.isEmpty()) {
			rows = noticeRepository.findFirstPage(pageRequest);
		} else {
			Cursor after = Cursor.decode(cursor);
			rows = noticeRepository.findPageAfter(after.pinned(), after.createdAt(), after.id(), pageRequest);
		}
		// 한 건 더 읽어서 다음 페이지가 있는지 판단한다
		boolean hasNext = rows.size() > PAGE_SIZE;
		List<Notice> page = hasNext ? rows.subList(0, PAGE_SIZE) : rows;
		String nextCursor = hasNext ? Cursor.of(page.get(page.size() - 1)).encode() : null;
		return new NoticePage(page.stream().map(NoticeResponse::from).toList(), nextCursor);
	}

	@Transactional(readOnly = true)
	public NoticeResponse get(String noticeId) {
		return NoticeResponse.from(find(noticeId));
	}

	@Transactional
	public NoticeResponse create(long adminId, NoticeInput input) {
		String title = normalizeTitle(input.title());
		String content = normalizeContent(input.content());
		Notice notice = Notice.create(title, content, Boolean.TRUE.equals(input.isPinned()), adminId,
				LocalDateTime.now(clock));
		return NoticeResponse.from(noticeRepository.saveAndFlush(notice));
	}

	@Transactional
	public NoticeResponse update(String noticeId, NoticeInput input) {
		Notice notice = find(noticeId);
		notice.edit(normalizeTitle(input.title()), normalizeContent(input.content()),
				Boolean.TRUE.equals(input.isPinned()), LocalDateTime.now(clock));
		return NoticeResponse.from(noticeRepository.saveAndFlush(notice));
	}

	@Transactional
	public NoticeResponse setPinned(String noticeId, boolean pinned) {
		Notice notice = find(noticeId);
		notice.changePinned(pinned);
		return NoticeResponse.from(noticeRepository.saveAndFlush(notice));
	}

	// 잔디 인정 규칙(is_system) 공지는 서버가 삭제를 거부한다 (DB명세서 1-15)
	@Transactional
	public void delete(String noticeId) {
		Notice notice = find(noticeId);
		if (notice.isSystem()) {
			throw new ApiException(ErrorCode.SYSTEM_NOTICE_UNDELETABLE);
		}
		noticeRepository.delete(notice);
		noticeRepository.flush();
	}

	private Notice find(String noticeId) {
		long id;
		try {
			id = Long.parseLong(noticeId);
		} catch (NumberFormatException e) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return noticeRepository.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	private static String normalizeTitle(String title) {
		String stripped = title == null ? "" : title.strip();
		if (stripped.isEmpty() || stripped.codePointCount(0, stripped.length()) > TITLE_MAX_LENGTH) {
			throw new ApiException(ErrorCode.INVALID_INPUT);
		}
		return stripped;
	}

	private static String normalizeContent(String content) {
		String stripped = content == null ? "" : content.strip();
		if (stripped.isEmpty() || stripped.getBytes(StandardCharsets.UTF_8).length > CONTENT_MAX_BYTES) {
			throw new ApiException(ErrorCode.INVALID_INPUT);
		}
		return stripped;
	}

	// 목록 위치. "P|N" + 작성 시각 + id 를 Base64url로 감싼다
	record Cursor(boolean pinned, LocalDateTime createdAt, long id) {

		static Cursor of(Notice notice) {
			return new Cursor(notice.isPinned(), notice.getCreatedAt(), notice.getId());
		}

		String encode() {
			String raw = (pinned ? "P" : "N") + "|" + DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(createdAt) + "|" + id;
			return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
		}

		static Cursor decode(String cursor) {
			try {
				String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
				String[] parts = raw.split("\\|", -1);
				if (parts.length == 3 && (parts[0].equals("P") || parts[0].equals("N"))) {
					long id = Long.parseLong(parts[2]);
					if (id > 0) {
						return new Cursor(parts[0].equals("P"),
								LocalDateTime.parse(parts[1], DateTimeFormatter.ISO_LOCAL_DATE_TIME), id);
					}
				}
			} catch (IllegalArgumentException | DateTimeParseException e) {
				// 아래에서 같은 오류로 처리
			}
			throw new ApiException(ErrorCode.INVALID_INPUT);
		}

	}

}
