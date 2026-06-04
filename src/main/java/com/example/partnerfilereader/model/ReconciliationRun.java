package com.example.partnerfilereader.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "reconciliation_runs")
public class ReconciliationRun {

    @Id
    @Column(name = "run_id", length = 64)
    private String runId;

    @Column(name = "identify", length = 64)
    private String identify;

    @Column(name = "reconciliation_date", length = 32)
    private String reconciliationDate;

    @Column(name = "status", length = 32)
    private String status;

    @Column(name = "total_partner_records")
    private int totalPartnerRecords;

    @Column(name = "total_internal_records")
    private int totalInternalRecords;

    @Column(name = "matched_count")
    private int matchedCount;

    @Column(name = "mismatched_count")
    private int mismatchedCount;

    @Column(name = "partner_only_count")
    private int partnerOnlyCount;

    @Column(name = "internal_only_count")
    private int internalOnlyCount;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;
}
