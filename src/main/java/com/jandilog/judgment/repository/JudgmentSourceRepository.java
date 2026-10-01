package com.jandilog.judgment.repository;

import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

// 판정 입력 조회 전용. 소속·글 색인·면제 테이블은 다른 도메인이 소유하므로 엔티티를 중복 매핑하지 않고 읽기 SQL만 둔다.
// 판정은 MariaDB만 읽는다(Mongo 접근 금지, DB명세서 4-2)
@Repository
@Transactional(readOnly = true)
public class JudgmentSourceRepository {

	public record MemberRef(long id, String githubLogin) {
	}

	@PersistenceContext
	private EntityManager em;

	// 기준 시각의 소속 팀. 참가는 그 시각 이전이고 나간 시각이 없거나 그 시각 이후 (주 종료 스냅샷, Q-04)
	public Set<Long> findTeamIdsAt(long memberId, LocalDateTime at) {
		List<?> rows = em.createNativeQuery("""
				select distinct team_id from team_member
				where member_id = :memberId and joined_at <= :at and (left_at is null or left_at > :at)
				order by team_id
				""")
				.setParameter("memberId", memberId)
				.setParameter("at", at)
				.getResultList();
		Set<Long> teamIds = new LinkedHashSet<>();
		for (Object row : rows) {
			teamIds.add(((Number) row).longValue());
		}
		return teamIds;
	}

	// 지금 소속된 팀이 있는가
	public boolean hasCurrentTeam(long memberId) {
		return count("select count(*) from team_member where member_id = :memberId and left_at is null",
				"memberId", memberId) > 0;
	}

	// 일자별 기록글 수. 필수 항목을 채우고 지워지지 않은 글만, 날짜는 최초 저장일 (Q-11)
	public Map<LocalDate, Integer> countRecordPostsByDay(long memberId, LocalDate from, LocalDate to) {
		List<?> rows = em.createNativeQuery("""
				select written_date, count(*) from post_index
				where author_id = :memberId and is_record = true and deleted_at is null
				  and written_date between :from and :to
				group by written_date
				""")
				.setParameter("memberId", memberId)
				.setParameter("from", from)
				.setParameter("to", to)
				.getResultList();
		Map<LocalDate, Integer> counts = new HashMap<>();
		for (Object row : rows) {
			Object[] columns = (Object[]) row;
			counts.put(toLocalDate(columns[0]), ((Number) columns[1]).intValue());
		}
		return counts;
	}

	// 관리자가 등록한 전체 면제 기간인 주인가
	public boolean isExemptionPeriod(LocalDate weekStart) {
		return count("select count(*) from exemption_period where week_start = :weekStart", "weekStart", weekStart) > 0;
	}

	// 승인된 개인 면제가 있는 주인가
	public boolean hasApprovedPersonalExemption(long memberId, LocalDate weekStart) {
		Number count = (Number) em.createNativeQuery("""
				select count(*) from personal_exemption
				where member_id = :memberId and week_start = :weekStart and status = 'APPROVED'
				""")
				.setParameter("memberId", memberId)
				.setParameter("weekStart", weekStart)
				.getSingleResult();
		return count.longValue() > 0;
	}

	// 판정·잔디 갱신 대상인 승인된 회원
	public List<MemberRef> findActiveMembers() {
		List<?> rows = em.createNativeQuery("select id, github_login from member where status = 'ACTIVE' order by id")
				.getResultList();
		return rows.stream()
				.map(row -> (Object[]) row)
				.map(columns -> new MemberRef(((Number) columns[0]).longValue(), (String) columns[1]))
				.toList();
	}

	private long count(String sql, String name, Object value) {
		return ((Number) em.createNativeQuery(sql).setParameter(name, value).getSingleResult()).longValue();
	}

	private static LocalDate toLocalDate(Object value) {
		if (value instanceof LocalDate date) {
			return date;
		}
		if (value instanceof Date date) {
			return date.toLocalDate();
		}
		if (value instanceof java.util.Date date) {
			return new Date(date.getTime()).toLocalDate();
		}
		throw new IllegalStateException("날짜 컬럼 형식을 알 수 없어요: " + value.getClass().getName());
	}

}
