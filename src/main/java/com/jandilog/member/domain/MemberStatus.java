package com.jandilog.member.domain;

// member.status ENUM과 1:1 (DB명세서 1-1)
public enum MemberStatus {
	PENDING,
	ACTIVE,
	REJECTED
}
