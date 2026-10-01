package com.jandilog.post.dto;

// 글 종류별 본문 항목 입력. 종류에 해당하지 않는 항목은 무시한다
public record PostSectionsInput(String problem, String cause, String solution, String did, String learned) {
}
