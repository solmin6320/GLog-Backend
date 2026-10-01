package com.jandilog.warning.domain;

// 정정·소급 면제 미리보기: 지금 값(before)과 가정을 적용한 값(after). 계산만 하고 아무것도 바꾸지 않는다 (기능명세서 7장)
public record WarningRecalcPreview(WarningRecalcResult before, WarningRecalcResult after) {

	// 둘 중 하나라도 보류 중이면 비교할 수 없다
	public boolean comparable() {
		return before instanceof WarningRecalcResult.Calculated && after instanceof WarningRecalcResult.Calculated;
	}

	// "경고가 {n}개에서 {n}개로 바뀝니다" (E-49)
	public int warningCountBefore() {
		return calculated(before).warningCount();
	}

	public int warningCountAfter() {
		return calculated(after).warningCount();
	}

	// 벌칙 대상에 새로 들어간다
	public boolean entersPenalty() {
		return !calculated(before).penaltyTarget() && calculated(after).penaltyTarget();
	}

	// 벌칙 대상에서 빠진다 (E-50)
	public boolean leavesPenalty() {
		return calculated(before).penaltyTarget() && !calculated(after).penaltyTarget();
	}

	private static WarningRecalcResult.Calculated calculated(WarningRecalcResult result) {
		if (result instanceof WarningRecalcResult.Calculated calculated) {
			return calculated;
		}
		throw new IllegalStateException("판정 보류 중이라 비교할 수 없어요");
	}

}
