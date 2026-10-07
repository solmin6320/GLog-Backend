package com.jandilog.team.board.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.jandilog.judgment.domain.JudgmentDay;
import com.jandilog.judgment.domain.JudgmentStatus;
import com.jandilog.judgment.domain.WeeklyJudgment;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

// 팀 현황판이 읽는 MariaDB 묶음 조회. 회원 id·팀 id 목록으로 한 번에 읽어 팀·팀원 수만큼 쿼리가 늘지 않게 한다.
// 소속·글 색인·판정·경고는 다른 도메인 테이블이라 읽기 전용으로만 쓴다
@Repository
@Transactional(readOnly = true)
public class TeamBoardRepository {

	@PersistenceContext
	private EntityManager em;

	// 팀들의 현재 팀원(나간 시각이 없는 소속). 팀 → 참가한 순서
	public List<BoardMemberRow> findCurrentMembers(Collection<Long> teamIds) {
		if (teamIds.isEmpty()) {
			return List.of();
		}
		return em.createQuery("""
				select new com.jandilog.team.board.repository.BoardMemberRow(
				    tm.teamId, m.id, m.nickname, m.githubLogin, m.firstTeamJoinedAt, tm.joinedAt)
				from TeamMember tm, Member m
				where tm.memberId = m.id and tm.teamId in :teamIds and tm.leftAt is null
				order by tm.teamId, tm.joinedAt, tm.id
				""", BoardMemberRow.class)
				.setParameter("teamIds", teamIds)
				.getResultList();
	}

	// 그 주에 확정된 판정(보류 제외)
	public List<WeeklyJudgment> findConfirmedJudgments(Collection<Long> memberIds, LocalDate weekStart) {
		if (memberIds.isEmpty()) {
			return List.of();
		}
		return em.createQuery("""
				select j from WeeklyJudgment j
				where j.memberId in :memberIds and j.weekStart = :weekStart and j.status <> :hold
				""", WeeklyJudgment.class)
				.setParameter("memberIds", memberIds)
				.setParameter("weekStart", weekStart)
				.setParameter("hold", JudgmentStatus.HOLD)
				.getResultList();
	}

	// 판정 시점에 저장한 일자별 근거
	public List<JudgmentDay> findJudgmentDays(Collection<Long> judgmentIds) {
		if (judgmentIds.isEmpty()) {
			return List.of();
		}
		return em.createQuery("select d from JudgmentDay d where d.judgmentId in :judgmentIds", JudgmentDay.class)
				.setParameter("judgmentIds", judgmentIds)
				.getResultList();
	}

	// 일자별 기록글 수. 필수 항목을 채우고 지워지지 않은 글만, 날짜는 최초 저장일 (Q-11). 팀 연결과 무관하게 개인 기준
	public List<RecordDayCount> countRecordPostsByDay(Collection<Long> memberIds, LocalDate from, LocalDate to) {
		if (memberIds.isEmpty()) {
			return List.of();
		}
		return em.createQuery("""
				select new com.jandilog.team.board.repository.RecordDayCount(p.authorId, p.writtenDate, count(p))
				from PostIndex p
				where p.authorId in :memberIds and p.record = true and p.deletedAt is null
				  and p.writtenDate between :from and :to
				group by p.authorId, p.writtenDate
				""", RecordDayCount.class)
				.setParameter("memberIds", memberIds)
				.setParameter("from", from)
				.setParameter("to", to)
				.getResultList();
	}

	// 승인된 개인 면제가 있는 회원
	public Set<Long> findMembersWithApprovedExemption(Collection<Long> memberIds, LocalDate weekStart) {
		if (memberIds.isEmpty()) {
			return Set.of();
		}
		List<?> rows = em.createNativeQuery("""
				select distinct member_id from personal_exemption
				where member_id in (:memberIds) and week_start = :weekStart and status = 'APPROVED'
				""")
				.setParameter("memberIds", memberIds)
				.setParameter("weekStart", weekStart)
				.getResultList();
		Set<Long> found = new HashSet<>();
		for (Object row : rows) {
			found.add(((Number) row).longValue());
		}
		return found;
	}

	// 그 팀 카테고리가 붙어 있는(빠지지 않은) 살아 있는 경고. 다른 팀 카테고리만 붙은 경고는 나오지 않는다 (기능명세서 2장)
	public List<WarningWeek> findTeamWarnings(long teamId, Collection<Long> memberIds) {
		if (memberIds.isEmpty()) {
			return List.of();
		}
		return em.createQuery("""
				select new com.jandilog.team.board.repository.WarningWeek(w.memberId, w.weekStart)
				from Warning w, WarningTeam wt
				where wt.warningId = w.id and wt.teamId = :teamId and wt.removedAt is null
				  and w.deletedAt is null and w.memberId in :memberIds
				""", WarningWeek.class)
				.setParameter("teamId", teamId)
				.setParameter("memberIds", memberIds)
				.getResultList();
	}

}
