package com.jandilog.judgment.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.jandilog.judgment.domain.JudgmentTeam;

public interface JudgmentTeamRepository extends JpaRepository<JudgmentTeam, JudgmentTeam.Key> {

	List<JudgmentTeam> findByJudgmentId(long judgmentId);

}
