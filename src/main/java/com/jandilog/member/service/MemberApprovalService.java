package com.jandilog.member.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jandilog.admin.domain.AdminActionLog;
import com.jandilog.admin.domain.AdminActionType;
import com.jandilog.admin.repository.AdminActionLogRepository;
import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.member.domain.Member;
import com.jandilog.member.domain.MemberStatus;
import com.jandilog.member.dto.AdminMemberPage;
import com.jandilog.member.dto.AdminMemberResponse;
import com.jandilog.member.dto.ApprovalStatusFilter;
import com.jandilog.member.repository.MemberRepository;

// 가입 승인 탭: 목록, 승인, 거절, 거절 회원을 승인 대기로 되돌리기 (기능명세서 1·9장)
@Service
public class MemberApprovalService {

	static final int PAGE_SIZE = 20;
	private static final int KEYWORD_MAX_LENGTH = 50;
	private static final char LIKE_ESCAPE = '!';
	private static final String TARGET_TYPE_MEMBER = "MEMBER";

	private final MemberRepository memberRepository;
	private final AdminActionLogRepository actionLogRepository;
	private final Clock clock;

	public MemberApprovalService(MemberRepository memberRepository, AdminActionLogRepository actionLogRepository,
			Clock clock) {
		this.memberRepository = memberRepository;
		this.actionLogRepository = actionLogRepository;
		this.clock = clock;
	}

	// 커서 기반 20개. 오래된 신청부터 id 오름차순이고, 커서는 마지막 행 id를 감싼 불투명 문자열
	@Transactional(readOnly = true)
	public AdminMemberPage list(ApprovalStatusFilter filter, String keyword, String cursor) {
		List<MemberStatus> statuses = (filter == null ? ApprovalStatusFilter.PENDING : filter).statuses();
		String pattern = likePattern(keyword);
		long afterId = decodeCursor(cursor);

		// 한 건 더 읽어서 다음 페이지가 있는지 판단한다
		List<Member> rows = memberRepository.findApprovalPage(statuses, pattern, afterId,
				PageRequest.of(0, PAGE_SIZE + 1));
		boolean hasNext = rows.size() > PAGE_SIZE;
		List<Member> page = hasNext ? rows.subList(0, PAGE_SIZE) : rows;
		String nextCursor = hasNext ? encodeCursor(page.get(page.size() - 1).getId()) : null;

		int totalCount = (int) memberRepository.countApproval(statuses, pattern);
		return new AdminMemberPage(page.stream().map(AdminMemberResponse::from).toList(), nextCursor, totalCount);
	}

	// 승인 대기 → 활성, 승인 시각 기록
	@Transactional
	public AdminMemberResponse approve(String memberId) {
		long id = parseId(memberId);
		int updated = memberRepository.approve(id, MemberStatus.PENDING, MemberStatus.ACTIVE,
				LocalDateTime.now(clock));
		return afterTransition(id, updated);
	}

	// 승인 대기 → 거절. 되돌릴 수 없는 행동이라 admin_action_log에 남긴다
	@Transactional
	public AdminMemberResponse reject(long adminId, String memberId) {
		long id = parseId(memberId);
		int updated = memberRepository.changeStatus(id, MemberStatus.PENDING, MemberStatus.REJECTED);
		AdminMemberResponse result = afterTransition(id, updated);
		actionLogRepository.save(AdminActionLog.of(adminId, AdminActionType.REJECT_MEMBER, TARGET_TYPE_MEMBER,
				Long.toString(id), null, LocalDateTime.now(clock)));
		return result;
	}

	// 거절 → 승인 대기. 오조작 복구용이라 로그 대상이 아니다
	@Transactional
	public AdminMemberResponse revertToPending(String memberId) {
		long id = parseId(memberId);
		int updated = memberRepository.changeStatus(id, MemberStatus.REJECTED, MemberStatus.PENDING);
		return afterTransition(id, updated);
	}

	// 갱신 0건이면 없는 회원(404)과 이미 다른 상태(409)를 구분한다
	private AdminMemberResponse afterTransition(long id, int updated) {
		Member member = memberRepository.findById(id)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (updated == 0) {
			throw new ApiException(ErrorCode.CONFLICT);
		}
		return AdminMemberResponse.from(member);
	}

	private long parseId(String memberId) {
		try {
			long id = Long.parseLong(memberId);
			if (id > 0) {
				return id;
			}
		} catch (NumberFormatException e) {
			// 아래에서 같은 오류로 처리
		}
		throw new ApiException(ErrorCode.INVALID_INPUT);
	}

	// 검색어는 이름·GitHub 아이디 부분 일치. 비었으면 전체, LIKE 와일드카드는 글자 그대로 찾는다
	private String likePattern(String keyword) {
		if (keyword == null || keyword.isBlank()) {
			return "%";
		}
		String trimmed = keyword.strip();
		if (trimmed.length() > KEYWORD_MAX_LENGTH) {
			throw new ApiException(ErrorCode.INVALID_INPUT);
		}
		String escaped = trimmed.toLowerCase(Locale.ROOT)
				.replace("" + LIKE_ESCAPE, "" + LIKE_ESCAPE + LIKE_ESCAPE)
				.replace("%", LIKE_ESCAPE + "%")
				.replace("_", LIKE_ESCAPE + "_");
		return "%" + escaped + "%";
	}

	static String encodeCursor(long lastId) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(Long.toString(lastId).getBytes(StandardCharsets.UTF_8));
	}

	static long decodeCursor(String cursor) {
		if (cursor == null || cursor.isEmpty()) {
			return 0L;
		}
		try {
			long id = Long.parseLong(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
			if (id >= 0) {
				return id;
			}
		} catch (IllegalArgumentException e) {
			// 아래에서 같은 오류로 처리
		}
		throw new ApiException(ErrorCode.INVALID_INPUT);
	}

}
