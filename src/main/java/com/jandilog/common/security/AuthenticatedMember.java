package com.jandilog.common.security;

import com.jandilog.member.domain.MemberRole;
import com.jandilog.member.domain.MemberStatus;

// 요청 시점 DB 기준의 로그인 회원. 컨트롤러에서 @AuthenticationPrincipal로 받는다
public record AuthenticatedMember(long id, MemberStatus status, MemberRole role) {
}
