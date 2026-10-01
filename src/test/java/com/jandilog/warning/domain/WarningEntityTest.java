package com.jandilog.warning.domain;

import static com.jandilog.testsupport.warning.WeekScript.week;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.jandilog.judgment.domain.JudgmentStatus;

// 경고·경고 팀·이행 체크·재계산 입력 한 주의 상태 전이 (기능명세서 6장 소프트 삭제·복구)
class WarningEntityTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 2, 3, 10, 0);

	@Test
	void 새_경고는_살아_있고_삭제_사유가_없다() {
		Warning warning = Warning.create(7L, week(2), NOW);

		assertThat(warning.isAlive()).isTrue();
		assertThat(warning.getMemberId()).isEqualTo(7L);
		assertThat(warning.getWeekStart()).isEqualTo(week(2));
		assertThat(warning.getCreatedAt()).isEqualTo(NOW);
		assertThat(warning.getDeletedAt()).isNull();
		assertThat(warning.getDeleteReason()).isNull();
	}

	@Test
	void 추방으로_소프트_삭제하면_삭제_시각과_사유가_남고_살아_있지_않다() {
		Warning warning = Warning.create(7L, week(2), NOW);

		warning.softDelete(WarningDeleteReason.KICKED, NOW.plusDays(1));

		assertThat(warning.isAlive()).isFalse();
		assertThat(warning.getDeletedAt()).isEqualTo(NOW.plusDays(1));
		assertThat(warning.getDeleteReason()).isEqualTo(WarningDeleteReason.KICKED);
	}

	@Test
	void 팀_삭제로_소프트_삭제하면_사유가_TEAM_DELETED다() {
		Warning warning = Warning.create(7L, week(2), NOW);

		warning.softDelete(WarningDeleteReason.TEAM_DELETED, NOW);

		assertThat(warning.isAlive()).isFalse();
		assertThat(warning.getDeleteReason()).isEqualTo(WarningDeleteReason.TEAM_DELETED);
	}

	@Test
	void 복구하면_다시_살아나고_삭제_표시가_모두_사라진다() {
		Warning warning = Warning.create(7L, week(2), NOW);
		warning.softDelete(WarningDeleteReason.KICKED, NOW);

		warning.restore();

		assertThat(warning.isAlive()).isTrue();
		assertThat(warning.getDeletedAt()).isNull();
		assertThat(warning.getDeleteReason()).isNull();
		// 경고 자체의 식별 정보는 그대로다
		assertThat(warning.getWeekStart()).isEqualTo(week(2));
		assertThat(warning.getCreatedAt()).isEqualTo(NOW);
	}

	@Test
	void 삭제와_복구를_반복해도_마지막_상태를_따른다() {
		Warning warning = Warning.create(7L, week(2), NOW);

		warning.softDelete(WarningDeleteReason.KICKED, NOW);
		warning.restore();
		warning.softDelete(WarningDeleteReason.TEAM_DELETED, NOW.plusDays(5));

		assertThat(warning.isAlive()).isFalse();
		assertThat(warning.getDeleteReason()).isEqualTo(WarningDeleteReason.TEAM_DELETED);
		assertThat(warning.getDeletedAt()).isEqualTo(NOW.plusDays(5));
	}

	@Test
	void 자진_탈퇴용_삭제_사유는_없다() {
		// 자진 탈퇴는 경고를 유지하므로 사유 값 자체가 없다 (DB명세서 1-9)
		assertThat(WarningDeleteReason.values()).containsExactlyInAnyOrder(WarningDeleteReason.KICKED,
				WarningDeleteReason.TEAM_DELETED);
	}

	@Test
	void 경고_팀_카테고리는_처음에_빠지지_않은_상태다() {
		WarningTeam warningTeam = WarningTeam.of(11L, 22L);

		assertThat(warningTeam.getWarningId()).isEqualTo(11L);
		assertThat(warningTeam.getTeamId()).isEqualTo(22L);
		assertThat(warningTeam.isRemoved()).isFalse();
		assertThat(warningTeam.getRemovedAt()).isNull();
	}

	@Test
	void 추방하면_카테고리가_빠지고_재초대로_복귀하면_되살아난다() {
		WarningTeam warningTeam = WarningTeam.of(11L, 22L);

		warningTeam.remove(NOW);
		assertThat(warningTeam.isRemoved()).isTrue();
		assertThat(warningTeam.getRemovedAt()).isEqualTo(NOW);

		warningTeam.restore();
		assertThat(warningTeam.isRemoved()).isFalse();
		assertThat(warningTeam.getRemovedAt()).isNull();
	}

	@Test
	void 경고_팀_복합키는_경고와_팀_둘이_같아야_같다() {
		var key = new WarningTeam.Key(1L, 2L);

		assertThat(key).isEqualTo(new WarningTeam.Key(1L, 2L));
		assertThat(key.hashCode()).isEqualTo(new WarningTeam.Key(1L, 2L).hashCode());
		assertThat(key).isNotEqualTo(new WarningTeam.Key(1L, 3L));
		assertThat(key).isNotEqualTo(new WarningTeam.Key(2L, 2L));
		assertThat(key).isNotEqualTo(new WarningTeam.Key(2L, 1L));
		Set<WarningTeam.Key> keys = new HashSet<>();
		keys.add(key);
		keys.add(new WarningTeam.Key(1L, 2L));
		assertThat(keys).hasSize(1);
	}

	@Test
	void 이행_체크는_만든_값을_그대로_담는다() {
		PenaltyFulfillment fulfillment = PenaltyFulfillment.of(7L, NOW, 99L, week(5));

		assertThat(fulfillment.getMemberId()).isEqualTo(7L);
		assertThat(fulfillment.getFulfilledAt()).isEqualTo(NOW);
		assertThat(fulfillment.getAdminId()).isEqualTo(99L);
		assertThat(fulfillment.getReachedWeek()).isEqualTo(week(5));
	}

	@Test
	void 이행_체크에는_고치는_메서드가_없다() {
		// 되돌릴 수 없는 고정점이라 값을 바꾸는 public 메서드를 두지 않는다 (DB명세서 1-11)
		var mutators = java.util.Arrays.stream(PenaltyFulfillment.class.getDeclaredMethods())
				.filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
				.filter(method -> !method.getName().startsWith("get"))
				.filter(method -> !java.lang.reflect.Modifier.isStatic(method.getModifiers()))
				.toList();
		assertThat(mutators).isEmpty();
	}

	@Test
	void 미달이고_삭제되지_않은_주만_경고로_센다() {
		for (JudgmentStatus status : JudgmentStatus.values()) {
			assertThat(new WeekEntry(week(0), status, false).countsAsWarning()).as("%s 삭제 안 됨", status)
					.isEqualTo(status == JudgmentStatus.FAIL);
			assertThat(new WeekEntry(week(0), status, true).countsAsWarning()).as("%s 삭제됨", status).isFalse();
		}
	}

}
