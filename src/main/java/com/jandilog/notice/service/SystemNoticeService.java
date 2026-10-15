package com.jandilog.notice.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jandilog.notice.domain.Notice;
import com.jandilog.notice.repository.NoticeRepository;

// 잔디 인정 규칙 고정 공지(is_system)를 한 건 보장한다. 마이그레이션이 아니라 앱 기동 때 멱등으로 만든다:
// author_id가 NOT NULL FK라 마이그레이션 시점에는 쓸 수 있는 관리자 회원이 보장되지 않는다
@Service
public class SystemNoticeService {

	static final String TITLE = "잔디 인정 규칙";
	// 기능명세서 4장 표 6줄 그대로 ("항목: 규칙")
	static final String CONTENT = String.join("\n",
			"기본 브랜치: 기본 브랜치(main)에 들어간 커밋만 잔디에 표시된다",
			"브랜치 작업: 다른 브랜치의 커밋은 머지되기 전까지 표시되지 않는다",
			"스쿼시 머지: 여러 날의 작업이 머지한 하루로 합쳐진다",
			"포크 레포: 포크한 레포의 커밋은 표시되지 않는다",
			"비공개 레포: GitHub 설정에서 비공개 기여 표시를 켜야 반영된다",
			"PR · 이슈: PR과 이슈 작성도 잔디 1칸으로 인정된다");

	private static final Logger log = LoggerFactory.getLogger(SystemNoticeService.class);

	private final NoticeRepository noticeRepository;
	private final Clock clock;

	public SystemNoticeService(NoticeRepository noticeRepository, Clock clock) {
		this.noticeRepository = noticeRepository;
		this.clock = clock;
	}

	// 이미 있으면 건드리지 않는다. 활성 관리자가 아직 없으면 만들지 않고 false (관리자 지정 뒤 다음 기동 때 만든다)
	@Transactional
	public boolean ensureExists() {
		if (noticeRepository.existsBySystemTrue()) {
			return false;
		}
		List<Long> adminIds = noticeRepository.findActiveAdminIds(PageRequest.of(0, 1));
		if (adminIds.isEmpty()) {
			log.warn("활성 관리자가 없어 잔디 인정 규칙 공지를 만들지 못했어요. 관리자를 지정한 뒤 다시 기동해 주세요.");
			return false;
		}
		noticeRepository.saveAndFlush(Notice.createSystem(TITLE, CONTENT, adminIds.get(0), LocalDateTime.now(clock)));
		log.info("잔디 인정 규칙 공지를 만들었어요. (authorId={})", adminIds.get(0));
		return true;
	}

}
