package com.jandilog.judgment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

// 인증일·기록글 수를 보여주는 상태는 통과·미달뿐이다 (화면설계서 AC-01 ⑤ "—")
class JudgmentStatusCountsTest {

	@Test
	void 통과와_미달만_인증일과_기록글을_보여준다() {
		assertThat(JudgmentStatus.PASS.showsCounts()).isTrue();
		assertThat(JudgmentStatus.FAIL.showsCounts()).isTrue();
	}

	@Test
	void 면제_제외_보류는_인증일과_기록글을_보여주지_않는다() {
		assertThat(JudgmentStatus.EXEMPT.showsCounts()).isFalse();
		assertThat(JudgmentStatus.EXCLUDED.showsCounts()).isFalse();
		assertThat(JudgmentStatus.HOLD.showsCounts()).isFalse();
	}

	@Test
	void 수치를_보여주는_상태는_모든_상태_중_통과와_미달_두_개다() {
		List<JudgmentStatus> shown = Arrays.stream(JudgmentStatus.values()).filter(JudgmentStatus::showsCounts)
				.toList();

		assertThat(shown).containsExactlyInAnyOrder(JudgmentStatus.PASS, JudgmentStatus.FAIL);
	}

}
