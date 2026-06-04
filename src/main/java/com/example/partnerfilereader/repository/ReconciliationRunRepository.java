package com.example.partnerfilereader.repository;

import com.example.partnerfilereader.model.ReconciliationRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ReconciliationRunRepository extends JpaRepository<ReconciliationRun, String> {
    Optional<ReconciliationRun> findTopByOrderByStartedAtDesc();
}
