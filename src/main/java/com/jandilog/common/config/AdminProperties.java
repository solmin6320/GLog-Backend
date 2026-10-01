package com.jandilog.common.config;

import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

// 첫 관리자 지정: ADMIN_GITHUB_IDS(GitHub 숫자 id, 쉼표 구분). 비어 있으면 아무도 승격되지 않는다.
// login 문자열은 다른 사람이 재사용할 수 있어서 숫자 id만 받는다. 형식이 틀리면 기동 때 실패한다
@ConfigurationProperties(prefix = "jandilog.admin")
public record AdminProperties(String githubIds) {

	private static final String ENV_NAME = "ADMIN_GITHUB_IDS";
	private static final int ECHO_MAX_LENGTH = 30;

	public AdminProperties {
		githubIds = githubIds == null ? "" : githubIds;
		// 잘못된 항목은 여기서 바로 실패시킨다
		parse(githubIds);
	}

	public Set<Long> githubIdSet() {
		return parse(githubIds);
	}

	// 값 전체가 비었으면 빈 목록. 항목은 앞뒤 공백만 잘라 받고, 빈 항목·숫자 아닌 값·음수·0·범위 초과는 거부한다
	static Set<Long> parse(String raw) {
		Set<Long> ids = new LinkedHashSet<>();
		if (raw == null || raw.isBlank()) {
			return ids;
		}
		String[] items = raw.split(",", -1);
		for (int i = 0; i < items.length; i++) {
			ids.add(parseItem(items[i].strip(), i + 1));
		}
		return ids;
	}

	private static long parseItem(String item, int position) {
		if (item.isEmpty()) {
			throw invalid(position, "빈 항목", item);
		}
		for (int i = 0; i < item.length(); i++) {
			char c = item.charAt(i);
			if (c < '0' || c > '9') {
				throw invalid(position, "GitHub 숫자 id가 아닌 항목", item);
			}
		}
		long id;
		try {
			id = Long.parseLong(item);
		} catch (NumberFormatException e) {
			throw invalid(position, "범위를 넘는 항목", item);
		}
		if (id < 1) {
			throw invalid(position, "1 이상이 아닌 항목", item);
		}
		return id;
	}

	// 키와 달리 민감하지 않아서 잘못된 항목을 그대로 알려준다 (길면 잘라서)
	private static IllegalArgumentException invalid(int position, String reason, String item) {
		String shown = item.length() > ECHO_MAX_LENGTH ? item.substring(0, ECHO_MAX_LENGTH) + "..." : item;
		return new IllegalArgumentException(ENV_NAME + "의 " + position + "번째 항목이 올바르지 않아요 (" + reason + ": '"
				+ shown + "'). GitHub 숫자 id만 쉼표로 구분해 주세요");
	}

}
