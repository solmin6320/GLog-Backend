package com.jandilog.admin.dto;

// [전체 재시도] 결과. attempted 중 resolved는 확정, stillHold는 여전히 보류, failed는 예외 (토스트 "{n}명 처리했어요.")
public record AdminHoldRerunSummary(int attempted, int resolved, int stillHold, int failed) {
}
