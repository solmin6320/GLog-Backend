package com.jandilog.warning.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.jandilog.warning.domain.WarningTeam;

public interface WarningTeamRepository extends JpaRepository<WarningTeam, WarningTeam.Key> {

	List<WarningTeam> findByWarningId(long warningId);

	List<WarningTeam> findByWarningIdIn(Collection<Long> warningIds);

}
