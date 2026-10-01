package com.jandilog.team.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.jandilog.team.domain.Team;

import jakarta.persistence.LockModeType;

public interface TeamRepository extends JpaRepository<Team, Long> {

	// 삭제된 팀 포함. 호출하는 쪽이 deleted_at을 본다
	Optional<Team> findByInviteCodeAndDeletedAtIsNull(String inviteCode);

	boolean existsByInviteCode(String inviteCode);

	// 팀 행 잠금. 참가·탈퇴·추방·위임·삭제·초대처럼 팀 소속을 바꾸는 흐름은 모두 이 잠금부터 잡아 팀 하나씩 직렬화한다
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select t from Team t where t.id = :id")
	Optional<Team> findByIdForUpdate(@Param("id") long id);

	List<Team> findByIdIn(Collection<Long> ids);

	// 공개이고 삭제되지 않은 팀의 이름. 글에 붙는 팀 이름표용 (비공개·삭제된 팀은 빠진다)
	@Query("select t from Team t where t.id in :ids and t.deletedAt is null and t.publicTeam = true")
	List<Team> findPublicAlive(@Param("ids") Collection<Long> ids);

}
