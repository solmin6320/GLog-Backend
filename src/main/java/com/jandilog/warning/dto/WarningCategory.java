package com.jandilog.warning.dto;

import java.util.Comparator;

import com.jandilog.team.query.TeamReadModel;

// 경고에 붙는 팀 카테고리의 표시 이름 (기능명세서 6·8장, AC-02 ③, PR-01 ⑨).
// 공개 팀은 이름, 비공개 팀은 "비공개 팀", 삭제된 팀은 "삭제된 팀"이고 열람자를 구분하지 않는다.
// 비공개·삭제된 팀은 팀이 여럿이어도 같은 이름 하나로 묶인다(어느 팀인지 드러내지 않는다)
public record WarningCategory(Kind kind, Long teamId, String name) {

	public enum Kind {
		PUBLIC,
		PRIVATE,
		DELETED
	}

	public static final String PRIVATE_NAME = "비공개 팀";
	public static final String DELETED_NAME = "삭제된 팀";

	// 공개 팀 → 비공개 팀 → 삭제된 팀 순, 공개 팀끼리는 이름순
	public static final Comparator<WarningCategory> DISPLAY_ORDER = Comparator
			.comparing(WarningCategory::kind)
			.thenComparing(WarningCategory::name);

	public static WarningCategory deleted() {
		return new WarningCategory(Kind.DELETED, null, DELETED_NAME);
	}

	// 팀 행이 없거나 삭제된 팀이면 삭제된 팀으로 본다
	public static WarningCategory of(TeamReadModel.TeamName team) {
		if (team == null || team.deleted()) {
			return deleted();
		}
		if (!team.publicTeam()) {
			return new WarningCategory(Kind.PRIVATE, null, PRIVATE_NAME);
		}
		return new WarningCategory(Kind.PUBLIC, team.id(), team.name());
	}

	// 같은 경고·같은 집계 안에서 한 카테고리로 세는 기준
	public String groupKey() {
		return kind == Kind.PUBLIC ? "T" + teamId : kind.name();
	}

}
