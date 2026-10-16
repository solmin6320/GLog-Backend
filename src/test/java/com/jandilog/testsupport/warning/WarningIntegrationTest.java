package com.jandilog.testsupport.warning;

import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.jandilog.warning.service.WarningRecalculationService;

// 로컬 docker-compose MariaDB를 쓰는 경고 재계산 통합 테스트 공통 바탕. HTTP 서버는 띄우지 않는다
@SpringBootTest
@ActiveProfiles("test")
public abstract class WarningIntegrationTest {

	@Autowired
	protected JdbcTemplate jdbc;
	@Autowired
	protected WarningRecalculationService recalculation;
	@Autowired
	protected PlatformTransactionManager transactionManager;

	protected WarningFixture fixture;

	@BeforeEach
	protected void setUpFixture() {
		fixture = new WarningFixture(jdbc);
	}

	@AfterEach
	protected void tearDownFixture() {
		fixture.cleanup();
	}

	protected <T> T inTransaction(Supplier<T> work) {
		return new TransactionTemplate(transactionManager).execute(status -> work.get());
	}

	protected void runInTransaction(Runnable work) {
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
	}

}
