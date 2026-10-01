package com.jandilog.common.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// 잔디 조회용 GitHub API 설정. 토큰은 환경변수(GITHUB_API_TOKEN)로만 받고 기본값을 두지 않는다
@ConfigurationProperties(prefix = "jandilog.grass")
public record GrassProperties(
		String githubApiToken,
		@DefaultValue("https://api.github.com/graphql") String githubGraphqlUrl,
		@DefaultValue("10s") Duration timeout) {

	public GrassProperties {
		if (githubApiToken == null || githubApiToken.isBlank()) {
			throw new IllegalArgumentException("GITHUB_API_TOKEN이 비었어요");
		}
		githubApiToken = githubApiToken.strip();
		// 헤더에 그대로 실리므로 공백·제어문자가 섞이면 거부한다
		if (githubApiToken.chars().anyMatch(c -> c <= ' ' || c == 0x7f)) {
			throw new IllegalArgumentException("GITHUB_API_TOKEN에 공백이나 제어문자가 있어요");
		}
		if (githubGraphqlUrl == null
				|| !(githubGraphqlUrl.startsWith("https://") || githubGraphqlUrl.startsWith("http://"))) {
			throw new IllegalArgumentException("jandilog.grass.github-graphql-url은 http(s)://로 시작해야 해요");
		}
		if (timeout == null || timeout.isNegative() || timeout.isZero()) {
			throw new IllegalArgumentException("jandilog.grass.timeout은 0보다 커야 해요");
		}
	}

	// 로그에 토큰이 찍히지 않게 가린다
	@Override
	public String toString() {
		return "GrassProperties[githubApiToken=****, githubGraphqlUrl=" + githubGraphqlUrl + ", timeout=" + timeout
				+ "]";
	}

}
