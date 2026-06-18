package com.example.partnerfilereader.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor  // ← thêm dòng này
@AllArgsConstructor // ← thêm dòng này (Builder cần cái này)
@Entity
@Table(
        name = "data_container",
        indexes = {
                @Index(name = "idx_data_container_identify_recon_date", columnList = "identify,reconciliation_date"),
                @Index(name = "idx_data_container_identify_created", columnList = "identify,created_date"),
                @Index(name = "idx_data_container_recon_trace", columnList = "identify,reconciliation_date,partner_trace"),
                @Index(name = "idx_data_container_recon_transaction", columnList = "identify,reconciliation_date,partner_transaction_id")
        }
)
public class DataContainer {

    @Id
    @Column(name = "request_id")
    private String requestId;

    private String identify;
    private String reconciliationDate;
    private String operationStatus;
    private String reconciliationStatus;
    private String workflowType;

    @Column(columnDefinition = "TEXT")
    private String connectorData;

    @Column(columnDefinition = "TEXT")
    private String extraData;

    private String createdBy;
    private LocalDateTime createdDate;
    private String lastModifiedBy;
    private LocalDateTime lastModifiedDate;

    @Column(name = "partner_transaction_id", length = 64)
    private String partnerTransactionId;

    @Column(name = "partner_trace", length = 128)
    private String partnerTrace;

    @Column(name = "partner_amount", precision = 19, scale = 2)
    private BigDecimal partnerAmount;

    @Column(name = "partner_currency", length = 16)
    private String partnerCurrency;

    @Column(name = "partner_status", length = 32)
    private String partnerStatus;

    @Column(name = "partner_trans_date")
    private LocalDateTime partnerTransDate;

    @Column(columnDefinition = "TEXT")
    private String partnerData;
}
