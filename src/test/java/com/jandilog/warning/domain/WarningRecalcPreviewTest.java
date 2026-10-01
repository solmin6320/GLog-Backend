package com.jandilog.warning.domain;

import static com.jandilog.testsupport.warning.WeekScript.endOf;
import static com.jandilog.testsupport.warning.WeekScript.parse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

// 정정·소급 면제·복구 미리보기의 비교값 (기능명세서 7장, E-42·E-49·E-50)
class WarningRecalcPreviewTest {

	private static WarningRecalcResult run(String script) {
		return WarningRecalculator.recalculate(parse(script), null);
	}

	private static WarningRecalcResult run(String script, LocalDateTime baseline) {
		return WarningRecalculator.recalculate(parse(script), baseline);
	}

	@Test
	void 경고_수가_n에서_n으로_바뀌는_값을_돌려준다() {
		var preview = new WarningRecalcPreview(run("FFP"), run("FFF"));
		assertThat(preview.comparable()).isTrue();
		assertThat(preview.warningCountBefore()).isEqualTo(2);
		assertThat(preview.warningCountAfter()).isEqualTo(3);
	}

	@Test
	void 경고가_2개에서_3개가_되면_벌칙_대상에_새로_들어간다() {
		var preview = new WarningRecalcPreview(run("FFP"), run("FFF"));
		assertThat(preview.entersPenalty()).isTrue();
		assertThat(preview.leavesPenalty()).isFalse();
	}

	@Test
	void 경고가_3개에서_2개가_되면_벌칙_대상에서_빠진다() {
		// 3번째 미달을 면제로 정정
		var preview = new WarningRecalcPreview(run("FFF"), run("FFE"));
		assertThat(preview.leavesPenalty()).isTrue();
		assertThat(preview.entersPenalty()).isFalse();
		assertThat(preview.warningCountBefore()).isEqualTo(3);
		assertThat(preview.warningCountAfter()).isEqualTo(2);
	}

	@Test
	void 벌칙_대상이_이어지면_들어가지도_빠지지도_않는다() {
		var preview = new WarningRecalcPreview(run("FFFF"), run("FFF"));
		assertThat(preview.entersPenalty()).isFalse();
		assertThat(preview.leavesPenalty()).isFalse();
	}

	@Test
	void 벌칙_대상이_아닌_채로_바뀌면_둘_다_아니다() {
		var preview = new WarningRecalcPreview(run("FP"), run("PP"));
		assertThat(preview.entersPenalty()).isFalse();
		assertThat(preview.leavesPenalty()).isFalse();
		assertThat(preview.warningCountBefore()).isEqualTo(1);
		assertThat(preview.warningCountAfter()).isZero();
	}

	@Test
	void 통과를_미달로_정정하면_연속이_끊겨_벌칙_대상이_될_수_있다() {
		// F F P P F: 차감으로 경고 2개. 4번째 주(통과)를 미달로 정정하면 차감이 사라져 4개
		var preview = new WarningRecalcPreview(run("FFPPF"), run("FFPFF"));
		assertThat(preview.warningCountBefore()).isEqualTo(2);
		assertThat(preview.warningCountAfter()).isEqualTo(4);
		assertThat(preview.entersPenalty()).isTrue();
	}

	@Test
	void 이행_기준선_이전_주를_가정해도_미리보기_값은_그대로다() {
		var baseline = endOf(2);
		var before = run("FFFFP", baseline);
		var preview = new WarningRecalcPreview(before, run("PFFFP", baseline));
		assertThat(preview.warningCountAfter()).isEqualTo(preview.warningCountBefore());
		assertThat(preview.entersPenalty()).isFalse();
		assertThat(preview.leavesPenalty()).isFalse();
	}

	@Test
	void 보류가_있으면_비교할_수_없다() {
		var onHold = new WarningRecalcPreview(run("FFH"), run("FFP"));
		assertThat(onHold.comparable()).isFalse();
		var afterHold = new WarningRecalcPreview(run("FFP"), run("FFH"));
		assertThat(afterHold.comparable()).isFalse();
	}

	@Test
	void 보류_상태에서는_숫자_접근이_거부된다() {
		var preview = new WarningRecalcPreview(run("FFH"), run("FFP"));
		assertThatThrownBy(preview::warningCountBefore).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(preview::entersPenalty).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(preview::leavesPenalty).isInstanceOf(IllegalStateException.class);
		var afterHold = new WarningRecalcPreview(run("FFP"), run("FFH"));
		assertThatThrownBy(afterHold::warningCountAfter).isInstanceOf(IllegalStateException.class);
	}

}
