package com.jandilog;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

// test 프로필에서 GitHub·JWT 더미 값을 받는다 (환경변수 없이도 기동)
@SpringBootTest
@ActiveProfiles("test")
class JandilogApplicationTests {

	// 컨텍스트 로딩 스모크: DB 연결과 Flyway 적용까지 확인
	@Test
	void contextLoads() {
	}

}
