package com.example.partnerfilereader.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
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
@Table(name = "internal_transactions")
public class InternalTransaction {

    @Id
    @Column(name = "transaction_id", length = 64)
    private String transactionId;

    @Column(name = "trace", length = 128)
    private String trace;

    @Column(name = "amount", precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", length = 16)
    private String currency;

    @Column(name = "status", length = 32)
    private String status;

    @Column(name = "trans_date")
    private LocalDateTime transDate;

    @Column(name = "service", length = 64)
    private String service;

    @Column(name = "portal", length = 64)
    private String portal;

    @Column(name = "provider", length = 64)
    private String provider;

    @Column(name = "method", length = 64)
    private String method;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "created_date")
    private LocalDateTime createdDate;

    @Column(name = "last_modified_by", length = 64)
    private String lastModifiedBy;

    @Column(name = "last_modified_date")
    private LocalDateTime lastModifiedDate;
}
