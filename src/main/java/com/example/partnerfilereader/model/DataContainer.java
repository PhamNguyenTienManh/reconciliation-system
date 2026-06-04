package com.example.partnerfilereader.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor  // ← thêm dòng này
@AllArgsConstructor // ← thêm dòng này (Builder cần cái này)
@Entity
@Table(name = "data_container")
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

    @Column(columnDefinition = "TEXT")
    private String partnerData;
}