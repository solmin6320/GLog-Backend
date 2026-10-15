package com.jandilog.warning.repository;

import java.time.LocalDateTime;

// 회원별 가장 최근 이행 시각 묶음 조회 결과. 경고 재계산의 기준선이다
public record LatestFulfillment(long memberId, LocalDateTime fulfilledAt) {
}
