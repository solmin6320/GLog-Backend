package com.jandilog.judgment.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jandilog.judgment.domain.DayResult;
import com.jandilog.judgment.domain.JudgmentDay;
import com.jandilog.judgment.domain.JudgmentResult;
import com.jandilog.judgment.domain.JudgmentTeam;
import com.jandilog.judgment.domain.WeeklyJudgment;
import com.jandilog.judgment.repository.JudgmentDayRepository;
import com.jandilog.judgment.repository.JudgmentTeamRepository;
import com.jandilog.judgment.repository.WeeklyJudgmentRepository;
import com.jandilog.warning.domain.Warning;
import com.jandilog.warning.domain.WarningTeam;
import com.jandilog.warning.repository.WarningRepository;
import com.jandilog.warning.repository.WarningTeamRepository;
import com.jandilog.warning.service.WarningRecalculationService;

// 판정 계산 결과를 MariaDB에 저장한다 (DB명세서 4-2 1·4·5단계). 월요일 스케줄러·소속 스냅샷 조회는 2단계에서 이 위에 얹는다.
// 회원 락을 잡고 한 트랜잭션으로 weekly_judgment, judgment_team, judgment_day, 미달이면 warning + warning_team까지 쓴다.
// (member_id, week_start) UNIQUE 기반 멱등: 확정된 주는 건드리지 않고, 보류 주만 같은 행을 갱신한다 (E-35)
@Service
public class JudgmentRecordService {

	public enum Type {
		// 새로 저장함
		CREATED,
		// 보류 주를 다시 돌려 갱신함
		UPDATED,
		// 이미 확정된 주라 아무것도 하지 않음
		SKIPPED_CONFIRMED
	}

	public record Outcome(Type type, WeeklyJudgment judgment) {
	}

	private final WeeklyJudgmentRepository judgmentRepository;
	private final JudgmentTeamRepository teamRepository;
	private final JudgmentDayRepository dayRepository;
	private final WarningRepository warningRepository;
	private final WarningTeamRepository warningTeamRepository;
	private final WarningRecalculationService recalculationService;

	public JudgmentRecordService(WeeklyJudgmentRepository judgmentRepository, JudgmentTeamRepository teamRepository,
			JudgmentDayRepository dayRepository, WarningRepository warningRepository,
			WarningTeamRepository warningTeamRepository, WarningRecalculationService recalculationService) {
		this.judgmentRepository = judgmentRepository;
		this.teamRepository = teamRepository;
		this.dayRepository = dayRepository;
		this.warningRepository = warningRepository;
		this.warningTeamRepository = warningTeamRepository;
		this.recalculationService = recalculationService;
	}

	// snapshotTeamIds: 주 종료 시각 소속 스냅샷. 처음 저장할 때만 쓰고 보류 재실행에서는 저장된 스냅샷을 그대로 쓴다
	@Transactional
	public Outcome record(long memberId, LocalDate weekStart, JudgmentResult result, Set<Long> snapshotTeamIds) {
		return record(memberId, weekStart, result, snapshotTeamIds, false);
	}

	// autoRetry: 스케줄러의 자동 재시도 결과일 때 true. 그래도 보류면 재시도 횟수를 올린다 (Q-07)
	@Transactional
	public Outcome record(long memberId, LocalDate weekStart, JudgmentResult result, Set<Long> snapshotTeamIds,
			boolean autoRetry) {
		recalculationService.lockMember(memberId);

		Optional<WeeklyJudgment> existing = judgmentRepository.findByMemberIdAndWeekStart(memberId, weekStart);
		if (existing.isPresent() && existing.get().isConfirmed()) {
			return new Outcome(Type.SKIPPED_CONFIRMED, existing.get());
		}

		WeeklyJudgment judgment;
		Type type;
		if (existing.isPresent()) {
			judgment = existing.get();
			judgment.apply(result);
			if (autoRetry && !result.isConfirmed()) {
				judgment.increaseRetryCount();
			}
			type = Type.UPDATED;
		}
		else {
			judgment = judgmentRepository.saveAndFlush(WeeklyJudgment.of(memberId, weekStart, result));
			snapshotTeamIds.forEach(teamId -> teamRepository.save(JudgmentTeam.of(judgment.getId(), teamId)));
			type = Type.CREATED;
		}

		if (result.isConfirmed()) {
			for (DayResult day : result.days()) {
				dayRepository.save(JudgmentDay.of(judgment.getId(), day));
			}
			if (result.warningRequired()) {
				grantWarning(judgment, result);
			}
		}
		return new Outcome(type, judgment);
	}

	// 미달이면 경고 1개(사람당 1개)를 부여하고 스냅샷 소속 팀 전부를 카테고리로 붙인다 (E-38)
	private void grantWarning(WeeklyJudgment judgment, JudgmentResult result) {
		if (warningRepository.findByMemberIdAndWeekStart(judgment.getMemberId(), judgment.getWeekStart()).isPresent()) {
			return;
		}
		List<JudgmentTeam> teams = teamRepository.findByJudgmentId(judgment.getId());
		if (teams.isEmpty()) {
			throw new IllegalStateException("소속 스냅샷이 없는 미달 판정이에요: " + judgment.getWeekStart());
		}
		Warning warning = warningRepository.saveAndFlush(
				Warning.create(judgment.getMemberId(), judgment.getWeekStart(), result.judgedAt()));
		for (JudgmentTeam team : teams) {
			warningTeamRepository.save(WarningTeam.of(warning.getId(), team.getTeamId()));
		}
	}

}
