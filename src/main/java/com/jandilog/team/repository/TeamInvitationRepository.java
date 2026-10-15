package com.jandilog.team.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.jandilog.team.domain.InvitationStatus;
import com.jandilog.team.domain.TeamInvitation;

import jakarta.persistence.LockModeType;

public interface TeamInvitationRepository extends JpaRepository<TeamInvitation, Long> {

	boolean existsByTeamIdAndInviteeIdAndStatus(long teamId, long inviteeId, InvitationStatus status);

	// TM-04: 받은 초대, 최신순
	List<TeamInvitation> findByInviteeIdAndStatusOrderByCreatedAtDescIdDesc(long inviteeId, InvitationStatus status);

	// TM-06 ③: 보낸 초대, 최신순
	List<TeamInvitation> findByTeamIdAndStatusOrderByCreatedAtDescIdDesc(long teamId, InvitationStatus status);

	// 엔티티를 영속성 컨텍스트에 올리지 않고 팀 id만 읽는다. 팀 잠금을 잡은 뒤에 초대를 새로 읽기 위함
	@Query("select i.teamId from TeamInvitation i where i.id = :id")
	Optional<Long> findTeamIdById(@Param("id") long id);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select i from TeamInvitation i where i.id = :id")
	Optional<TeamInvitation> findByIdForUpdate(@Param("id") long id);

}
