package com.example.partnerfilereader.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "reconciliation_results",
        indexes = {
                @Index(name = "idx_recon_result_run_status", columnList = "run_id,result_status"),
                @Index(name = "idx_recon_result_run_created", columnList = "run_id,created_date")
        }
)
public class ReconciliationResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "result_id")
    private Long resultId;

    @Column(name = "run_id", length = 64)
    private String runId;

    @Column(name = "match_key", length = 128)
    private String matchKey;

    @Column(name = "partner_request_id", length = 64)
    private String partnerRequestId;

    @Column(name = "partner_transaction_id", length = 64)
    private String partnerTransactionId;

    @Column(name = "internal_transaction_id", length = 64)
    private String internalTransactionId;

    @Column(name = "result_status", length = 32)
    private String resultStatus;

    @Column(name = "difference_fields", columnDefinition = "TEXT")
    private String differenceFields;

    @Column(name = "partner_amount", precision = 19, scale = 2)
    private BigDecimal partnerAmount;

    @Column(name = "internal_amount", precision = 19, scale = 2)
    private BigDecimal internalAmount;

    @Column(name = "partner_currency", length = 16)
    private String partnerCurrency;

    @Column(name = "internal_currency", length = 16)
    private String internalCurrency;

    @Column(name = "partner_status", length = 32)
    private String partnerStatus;

    @Column(name = "internal_status", length = 32)
    private String internalStatus;

    @Column(name = "partner_trans_date")
    private LocalDateTime partnerTransDate;

    @Column(name = "internal_trans_date")
    private LocalDateTime internalTransDate;

    @Column(name = "created_date")
    private LocalDateTime createdDate;
}
