package com.example.partnerfilereader.repository;

import com.example.partnerfilereader.model.ReconciliationResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReconciliationResultRepository extends JpaRepository<ReconciliationResult, Long> {
    List<ReconciliationResult> findTop100ByOrderByCreatedDateDesc();

    List<ReconciliationResult> findByRunIdOrderByCreatedDateDesc(String runId);

    long countByRunIdAndResultStatusAndDifferenceFieldsContaining(
            String runId,
            String resultStatus,
            String differenceField
    );
}
