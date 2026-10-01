package com.jandilog.admin.domain;

// admin_action_log.action ENUM과 1:1 (DB명세서 1-16)
public enum AdminActionType {
	REJECT_MEMBER,
	DELETE_POST,
	DELETE_COMMENT,
	FULFILL_PENALTY,
	CORRECT_JUDGMENT,
	RESTORE_WARNING
}
