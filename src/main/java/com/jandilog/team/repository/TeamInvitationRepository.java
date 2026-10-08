package com.jandilog.team.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.jandilog.team.domain.InvitationStatus;
import com.jandilog.team.domain.TeamInvitation;

import jakarta.persistence.LockModeType;

public interface TeamInvitationRepository extends JpaRepository<TeamInvitation, Long> {

	boolean existsByTeamIdAndInviteeIdAndStatus(long teamId, long inviteeId, InvitationStatus status);

	// TM-04: 받은 초대 첫 페이지, 최신순. 삭제된 팀의 초대는 뺀다
	@Query("""
			select i from TeamInvitation i, Team t
			where t.id = i.teamId and t.deletedAt is null
			  and i.inviteeId = :inviteeId and i.status = com.jandilog.team.domain.InvitationStatus.PENDING
			order by i.createdAt desc, i.id desc
			""")
	List<TeamInvitation> findReceivedPage(@Param("inviteeId") long inviteeId, Pageable pageable);

	// TM-04: 받은 초대 다음 페이지, 커서(받은 시각, id) 뒤부터
	@Query("""
			select i from TeamInvitation i, Team t
			where t.id = i.teamId and t.deletedAt is null
			  and i.inviteeId = :inviteeId and i.status = com.jandilog.team.domain.InvitationStatus.PENDING
			  and (i.createdAt < :createdAt or (i.createdAt = :createdAt and i.id < :id))
			order by i.createdAt desc, i.id desc
			""")
	List<TeamInvitation> findReceivedPageAfter(@Param("inviteeId") long inviteeId,
			@Param("createdAt") LocalDateTime createdAt, @Param("id") long id, Pageable pageable);

	// TM-06 ③: 보낸 초대(대기 중) 첫 페이지, 최신순
	@Query("""
			select i from TeamInvitation i
			where i.teamId = :teamId and i.status = com.jandilog.team.domain.InvitationStatus.PENDING
			order by i.createdAt desc, i.id desc
			""")
	List<TeamInvitation> findSentPage(@Param("teamId") long teamId, Pageable pageable);

	// TM-06 ③: 보낸 초대(대기 중) 다음 페이지, 커서(보낸 시각, id) 뒤부터
	@Query("""
			select i from TeamInvitation i
			where i.teamId = :teamId and i.status = com.jandilog.team.domain.InvitationStatus.PENDING
			  and (i.createdAt < :createdAt or (i.createdAt = :createdAt and i.id < :id))
			order by i.createdAt desc, i.id desc
			""")
	List<TeamInvitation> findSentPageAfter(@Param("teamId") long teamId, @Param("createdAt") LocalDateTime createdAt,
			@Param("id") long id, Pageable pageable);

	// 엔티티를 영속성 컨텍스트에 올리지 않고 팀 id만 읽는다. 팀 잠금을 잡은 뒤에 초대를 새로 읽기 위함
	@Query("select i.teamId from TeamInvitation i where i.id = :id")
	Optional<Long> findTeamIdById(@Param("id") long id);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select i from TeamInvitation i where i.id = :id")
	Optional<TeamInvitation> findByIdForUpdate(@Param("id") long id);

}
