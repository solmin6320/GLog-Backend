package com.jandilog.notice.dto;

// 공지 작성·수정 입력. isPinned를 생략하면 고정하지 않는다
public record NoticeInput(String title, String content, Boolean isPinned) {
}
