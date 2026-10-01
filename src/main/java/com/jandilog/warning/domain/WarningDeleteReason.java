package com.jandilog.warning.domain;

// warning.delete_reason ENUM과 1:1. 자진 탈퇴는 경고를 유지하므로 사유가 없다 (DB명세서 1-9)
public enum WarningDeleteReason {
	KICKED,
	TEAM_DELETED
}
