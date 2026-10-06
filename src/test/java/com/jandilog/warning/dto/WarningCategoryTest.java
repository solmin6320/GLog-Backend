package com.jandilog.warning.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.jandilog.team.query.TeamReadModel.TeamName;

// 경고 카테고리 표시 이름 규칙 (기능명세서 2장 공개 설정 표, 6장 E-43)
class WarningCategoryTest {

	@Test
	@DisplayName("공개 팀은 이름 그대로, 비공개 팀은 '비공개 팀'으로 나오고 비공개 팀은 팀 id를 싣지 않는다")
	void namesFollowPublicSetting() {
		WarningCategory open = WarningCategory.of(new TeamName(7, "알고리즘팀", true, false));
		WarningCategory secret = WarningCategory.of(new TeamName(8, "비밀팀", false, false));

		assertThat(open.kind()).isEqualTo(WarningCategory.Kind.PUBLIC);
		assertThat(open.name()).isEqualTo("알고리즘팀");
		assertThat(open.teamId()).isEqualTo(7L);
		assertThat(secret.kind()).isEqualTo(WarningCategory.Kind.PRIVATE);
		assertThat(secret.name()).isEqualTo("비공개 팀");
		assertThat(secret.teamId()).isNull();
	}

	@Test
	@DisplayName("삭제된 팀이거나 팀 행이 없으면 공개 여부와 상관없이 '삭제된 팀'이다")
	void deletedOrMissingTeamIsDeletedTeam() {
		assertThat(WarningCategory.of(new TeamName(1, "지운 공개팀", true, true)).name()).isEqualTo("삭제된 팀");
		assertThat(WarningCategory.of(new TeamName(2, "지운 비공개팀", false, true)).name()).isEqualTo("삭제된 팀");
		assertThat(WarningCategory.of(null)).isEqualTo(WarningCategory.deleted());
		assertThat(WarningCategory.deleted().teamId()).isNull();
	}

	@Test
	@DisplayName("묶는 기준은 공개 팀은 팀마다 따로, 비공개 팀과 삭제된 팀은 각각 하나다")
	void groupKeyMergesPrivateAndDeletedTeams() {
		WarningCategory firstPrivate = WarningCategory.of(new TeamName(1, "하나", false, false));
		WarningCategory secondPrivate = WarningCategory.of(new TeamName(2, "둘", false, false));
		WarningCategory firstDeleted = WarningCategory.of(new TeamName(3, "셋", true, true));
		WarningCategory secondDeleted = WarningCategory.of(new TeamName(4, "넷", false, true));
		WarningCategory firstOpen = WarningCategory.of(new TeamName(5, "다섯", true, false));
		WarningCategory secondOpen = WarningCategory.of(new TeamName(6, "여섯", true, false));

		assertThat(firstPrivate.groupKey()).isEqualTo(secondPrivate.groupKey());
		assertThat(firstDeleted.groupKey()).isEqualTo(secondDeleted.groupKey());
		assertThat(firstOpen.groupKey()).isNotEqualTo(secondOpen.groupKey());
		assertThat(List.of(firstPrivate.groupKey(), firstDeleted.groupKey(), firstOpen.groupKey())).doesNotHaveDuplicates();
	}

	@Test
	@DisplayName("표시 순서는 공개 팀(이름순) → 비공개 팀 → 삭제된 팀이다")
	void displayOrderPutsPublicFirstThenPrivateThenDeleted() {
		List<WarningCategory> categories = new ArrayList<>(List.of(
				WarningCategory.deleted(),
				WarningCategory.of(new TeamName(2, "나팀", true, false)),
				WarningCategory.of(new TeamName(3, "비밀", false, false)),
				WarningCategory.of(new TeamName(1, "가팀", true, false))));

		categories.sort(WarningCategory.DISPLAY_ORDER);

		assertThat(categories).extracting(WarningCategory::name).containsExactly("가팀", "나팀", "비공개 팀", "삭제된 팀");
	}

}
