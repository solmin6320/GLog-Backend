package com.jandilog.profile.dto;

// 프로필 팀별 경고 한 줄 (PR-01 ⑨): "{팀 이름} {count}회". 공개 팀은 이름, 비공개 팀은 "비공개 팀", 삭제된 팀은 "삭제된 팀"이다
public record ProfileTeamWarning(String name, int count) {
}
