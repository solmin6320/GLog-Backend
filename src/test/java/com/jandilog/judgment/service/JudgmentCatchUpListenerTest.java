package com.jandilog.judgment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

// 기동 시 따라잡기 리스너 (E-34). 기동이 끝나면 한 번 부르고, 실패해도 서버 기동은 막지 않는다.
// test 프로필이나 스케줄러를 끈 환경에서는 등록하지 않는다
class JudgmentCatchUpListenerTest {

	private final JudgmentCatchUpService catchUpService = mock(JudgmentCatchUpService.class);

	@Test
	void 기동이_끝나면_따라잡기를_한_번_부른다() {
		new JudgmentCatchUpListener(catchUpService).onApplicationReady();

		verify(catchUpService, times(1)).catchUp();
	}

	@Test
	void 따라잡기가_예외로_실패해도_기동을_막지_않고_삼킨다() {
		doThrow(new IllegalStateException("DB 연결 끊김")).when(catchUpService).catchUp();
		JudgmentCatchUpListener listener = new JudgmentCatchUpListener(catchUpService);

		assertThatCode(listener::onApplicationReady).doesNotThrowAnyException();

		verify(catchUpService, times(1)).catchUp();
	}

	@Test
	void 프로필과_스케줄러_설정이_기본이면_리스너가_등록된다() {
		runner().run(context -> assertThat(context).hasSingleBean(JudgmentCatchUpListener.class));
	}

	@Test
	void 스케줄러가_켜져_있고_운영_프로필이어도_등록된다() {
		runner().withPropertyValues("spring.profiles.active=prod", "jandilog.scheduler.enabled=true")
				.run(context -> assertThat(context).hasSingleBean(JudgmentCatchUpListener.class));
	}

	@Test
	void test_프로필에서는_리스너가_등록되지_않는다() {
		runner().withPropertyValues("spring.profiles.active=test")
				.run(context -> assertThat(context).doesNotHaveBean(JudgmentCatchUpListener.class));
	}

	@Test
	void 스케줄러를_끄면_리스너가_등록되지_않는다() {
		runner().withPropertyValues("jandilog.scheduler.enabled=false")
				.run(context -> assertThat(context).doesNotHaveBean(JudgmentCatchUpListener.class));
	}

	@Test
	void 여러_프로필_중_test가_섞여_있으면_등록되지_않는다() {
		runner().withPropertyValues("spring.profiles.active=local,test")
				.run(context -> assertThat(context).doesNotHaveBean(JudgmentCatchUpListener.class));
	}

	private ApplicationContextRunner runner() {
		return new ApplicationContextRunner().withUserConfiguration(JudgmentCatchUpListener.class)
				.withBean(JudgmentCatchUpService.class, () -> catchUpService);
	}

}
