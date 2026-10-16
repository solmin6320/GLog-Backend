package com.jandilog.warning.dto;

import java.util.List;

// 경고 이력 한 페이지 (Q-06). 판정 보류가 남아 있으면 유효·차감 여부를 정할 수 없어(Q-03) onHold만 true이고 items는 비어 있다
public record WarningHistoryPage(boolean onHold, List<WarningHistoryItem> items, String nextCursor) {

	public static WarningHistoryPage held() {
		return new WarningHistoryPage(true, List.of(), null);
	}

}
