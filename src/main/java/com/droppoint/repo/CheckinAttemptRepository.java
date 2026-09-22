package com.droppoint.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CheckinAttemptRepository extends JpaRepository<CheckinAttempt, Long> {

    List<CheckinAttempt> findTop20ByAgentIdOrderByAttemptedAtDesc(Long agentId);
}
