package com.jandilog.post.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.post.domain.PostSections;
import com.jandilog.post.domain.PostType;
import com.jandilog.post.dto.PostSectionsInput;

// 글·댓글·검색 입력 검사와 정리. 규칙 근거는 화면설계서 BD-01·BD-03·BD-04의 예외 표
final class PostInputRules {

	// BD-04 ③ 제목 1~60자 (제안)
	static final int TITLE_MAX_LENGTH = 60;
	// E-30 태그 최대 5개, 개당 20자 (제안)
	static final int TAGS_MAX_COUNT = 5;
	static final int TAG_MAX_LENGTH = 20;
	// 필수 항목 항목당 5000자, 관련 커밋 링크 5개 (사용자 확정 2026-10-06)
	static final int SECTION_MAX_LENGTH = 5000;
	static final int COMMIT_URLS_MAX_COUNT = 5;
	// BD-03 ⑫ 댓글 1~500자 (제안)
	static final int COMMENT_MAX_LENGTH = 500;
	// E-61, E-63 검색어 2자 이상 50자 이하
	static final int SEARCH_MIN_LENGTH = 2;
	static final int SEARCH_MAX_LENGTH = 50;

	// https://github.com/{owner}/{repo}/commit/{sha}. 형식만 보고 실제 존재는 확인하지 않는다 (E-29)
	private static final Pattern COMMIT_URL = Pattern.compile(
			"^https://github\\.com/[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})/(?!\\.{1,2}/)[A-Za-z0-9._-]{1,100}/commit/[0-9a-fA-F]{7,40}$");

	private PostInputRules() {
	}

	static String title(String raw) {
		String title = raw == null ? "" : raw.strip();
		if (title.isEmpty()) {
			throw new ApiException(ErrorCode.TITLE_REQUIRED);
		}
		if (length(title) > TITLE_MAX_LENGTH) {
			throw new ApiException(ErrorCode.INVALID_INPUT);
		}
		return title;
	}

	// 해당 종류의 항목만 남기고 앞뒤 공백을 지운다. 비어 있으면 빈 문자열로 저장해 항목 자리는 유지한다.
	// 항목마다 공백을 지운 글자 수가 5000자를 넘으면 오류다
	static PostSections sections(PostType type, PostSectionsInput raw) {
		if (raw == null) {
			throw new ApiException(ErrorCode.INVALID_INPUT);
		}
		return switch (type) {
			case TROUBLESHOOTING -> new PostSections(text(raw.problem(), ErrorCode.POST_PROBLEM_TOO_LONG),
					text(raw.cause(), ErrorCode.POST_CAUSE_TOO_LONG),
					text(raw.solution(), ErrorCode.POST_SOLUTION_TOO_LONG), null, null);
			case DEVLOG -> new PostSections(null, null, null, text(raw.did(), ErrorCode.POST_DID_TOO_LONG),
					text(raw.learned(), ErrorCode.POST_LEARNED_TOO_LONG));
		};
	}

	// 앞뒤 공백을 지우고 소문자로 통일해 같은 태그가 갈라지지 않게 한다. 빈 태그와 중복은 버린다
	static List<String> tags(List<String> raw) {
		LinkedHashSet<String> tags = new LinkedHashSet<>();
		if (raw != null) {
			for (String value : raw) {
				String tag = normalizeTag(value);
				if (tag != null) {
					if (length(tag) > TAG_MAX_LENGTH) {
						throw new ApiException(ErrorCode.TAG_TOO_LONG);
					}
					tags.add(tag);
				}
			}
		}
		if (tags.size() > TAGS_MAX_COUNT) {
			throw new ApiException(ErrorCode.TAG_LIMIT_EXCEEDED);
		}
		return new ArrayList<>(tags);
	}

	// 목록의 태그 필터용. 비었으면 null
	static String normalizeTag(String raw) {
		if (raw == null) {
			return null;
		}
		String tag = raw.strip().toLowerCase(Locale.ROOT);
		return tag.isEmpty() ? null : tag;
	}

	static List<String> commitUrls(List<String> raw) {
		LinkedHashSet<String> urls = new LinkedHashSet<>();
		if (raw != null) {
			for (String value : raw) {
				String url = value == null ? "" : value.strip();
				if (url.isEmpty()) {
					continue;
				}
				if (!COMMIT_URL.matcher(url).matches()) {
					throw new ApiException(ErrorCode.INVALID_COMMIT_URL);
				}
				urls.add(url);
				// 중복·빈 값을 뺀 개수가 5개를 넘는 순간 멈춘다
				if (urls.size() > COMMIT_URLS_MAX_COUNT) {
					throw new ApiException(ErrorCode.POST_COMMIT_URLS_LIMIT_EXCEEDED);
				}
			}
		}
		return new ArrayList<>(urls);
	}

	// 팀 연결은 id만 읽는다. 소속·삭제 여부 검증은 PostService가 TeamAccessService로 한다
	static Long teamId(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			long id = Long.parseLong(raw.strip());
			if (id > 0) {
				return id;
			}
		} catch (NumberFormatException e) {
			// 아래에서 같은 오류로 처리
		}
		throw new ApiException(ErrorCode.INVALID_INPUT);
	}

	static String commentContent(String raw) {
		String content = raw == null ? "" : raw.strip();
		if (content.isEmpty() || length(content) > COMMENT_MAX_LENGTH) {
			throw new ApiException(ErrorCode.INVALID_INPUT);
		}
		return content;
	}

	// 검색어를 공백 기준 토큰으로 나눈다. 비었으면 빈 목록(검색 안 함), 앞뒤 공백을 뺀 길이가 2자 미만·50자 초과면 오류 (E-61, E-63)
	static List<String> searchTerms(String raw) {
		String query = raw == null ? "" : raw.strip();
		if (query.isEmpty()) {
			return List.of();
		}
		int length = length(query);
		if (length < SEARCH_MIN_LENGTH) {
			throw new ApiException(ErrorCode.SEARCH_TOO_SHORT);
		}
		if (length > SEARCH_MAX_LENGTH) {
			throw new ApiException(ErrorCode.SEARCH_TOO_LONG);
		}
		// 텍스트 검색 문법에 쓰이는 따옴표·역슬래시는 글자로 취급하지 않고 뺀다
		List<String> terms = new ArrayList<>();
		for (String token : query.replace("\"", " ").replace("\\", " ").split("\\s+")) {
			if (!token.isEmpty()) {
				terms.add(token);
			}
		}
		if (terms.isEmpty()) {
			throw new ApiException(ErrorCode.SEARCH_TOO_SHORT);
		}
		return terms;
	}

	private static String text(String value, ErrorCode tooLong) {
		String text = value == null ? "" : value.strip();
		if (length(text) > SECTION_MAX_LENGTH) {
			throw new ApiException(tooLong);
		}
		return text;
	}

	private static int length(String value) {
		return value.codePointCount(0, value.length());
	}

}
