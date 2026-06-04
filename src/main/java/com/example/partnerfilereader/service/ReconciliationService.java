package com.example.partnerfilereader.service;

import com.example.partnerfilereader.config.PartnerConfig;
import com.example.partnerfilereader.model.DataContainer;
import com.example.partnerfilereader.model.InternalTransaction;
import com.example.partnerfilereader.model.PartnerData;
import com.example.partnerfilereader.model.ReconciliationResult;
import com.example.partnerfilereader.model.ReconciliationRun;
import com.example.partnerfilereader.repository.DataContainerRepository;
import com.example.partnerfilereader.repository.InternalTransactionRepository;
import com.example.partnerfilereader.repository.ReconciliationResultRepository;
import com.example.partnerfilereader.repository.ReconciliationRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReconciliationService {

    private final DataContainerRepository repository;
    private final InternalTransactionRepository internalTransactionRepository;
    private final ReconciliationRunRepository reconciliationRunRepository;
    private final ReconciliationResultRepository reconciliationResultRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final ZoneId RECONCILIATION_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    public void process(PartnerConfig config, String dataFilePath) throws IOException {
        int totalRead = 0, totalSaved = 0, totalError = 0;

        try (FileInputStream fis = new FileInputStream(dataFilePath);
             Workbook wb = new XSSFWorkbook(fis)) {

            Sheet sheet = wb.getSheetAt(0);

            // rowBegin = 7 là dòng header, data bắt đầu từ index = rowBegin (dòng 8)
            for (int i = config.getRowBegin(); i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                totalRead++;
                try {
                    DataContainer dc = mapRowToDataContainer(row, config);
                    repository.save(dc);
                    totalSaved++;
                } catch (Exception e) {
                    totalError++;
                    log.error("Lỗi dòng {}: {}", i + 1, e.getMessage(), e);
                }
            }
        }

        log.info("Tổng đọc: {} | Lưu thành công: {} | Lỗi: {}",
                totalRead, totalSaved, totalError);
    }

    public ReconciliationRun reconcile(String identify, String reconciliationDate) {
        ReconciliationRun run = ReconciliationRun.builder()
                .runId(UUID.randomUUID().toString())
                .identify(identify)
                .reconciliationDate(reconciliationDate)
                .status("RUNNING")
                .startedAt(LocalDateTime.now())
                .createdBy("system")
                .build();
        reconciliationRunRepository.save(run);

        try {
            List<DataContainer> containers = repository
                    .findByIdentifyAndReconciliationDateOrderByCreatedDateDesc(identify, reconciliationDate);
            if (containers.isEmpty()) {
                containers = repository.findByIdentifyOrderByCreatedDateDesc(identify);
            }

            Map<String, PartnerRecord> partnerRecords = loadPartnerRecords(containers);
            Map<String, InternalTransaction> internalRecords = loadInternalRecords(identify);
            List<ReconciliationResult> results = new ArrayList<>();
            Set<String> handledInternalKeys = new HashSet<>();

            int matchedCount = 0;
            int mismatchedCount = 0;
            int partnerOnlyCount = 0;
            int internalOnlyCount = 0;

            for (PartnerRecord partnerRecord : partnerRecords.values()) {
                InternalTransaction internalTransaction = internalRecords.get(partnerRecord.matchKey());

                if (internalTransaction == null) {
                    partnerOnlyCount++;
                    results.add(buildResult(
                            run.getRunId(),
                            partnerRecord,
                            null,
                            "PARTNER_ONLY",
                            List.of("missing_internal_transaction")
                    ));
                    continue;
                }

                handledInternalKeys.add(partnerRecord.matchKey());
                List<String> differences = compare(partnerRecord, internalTransaction);
                if (differences.isEmpty()) {
                    matchedCount++;
                    results.add(buildResult(
                            run.getRunId(),
                            partnerRecord,
                            internalTransaction,
                            "MATCHED",
                            List.of()
                    ));
                } else {
                    mismatchedCount++;
                    results.add(buildResult(
                            run.getRunId(),
                            partnerRecord,
                            internalTransaction,
                            "MISMATCHED",
                            differences
                    ));
                }
            }

            for (Map.Entry<String, InternalTransaction> entry : internalRecords.entrySet()) {
                if (handledInternalKeys.contains(entry.getKey())) {
                    continue;
                }

                internalOnlyCount++;
                results.add(buildResult(
                        run.getRunId(),
                        null,
                        entry.getValue(),
                        "INTERNAL_ONLY",
                        List.of("missing_partner_transaction")
                ));
            }

            reconciliationResultRepository.saveAll(results);
            run.setTotalPartnerRecords(partnerRecords.size());
            run.setTotalInternalRecords(internalRecords.size());
            run.setMatchedCount(matchedCount);
            run.setMismatchedCount(mismatchedCount);
            run.setPartnerOnlyCount(partnerOnlyCount);
            run.setInternalOnlyCount(internalOnlyCount);
            run.setStatus("COMPLETED");
            run.setFinishedAt(LocalDateTime.now());
            reconciliationRunRepository.save(run);

            log.info(
                    "Reconciliation run {} completed: partner={} | internal={} | matched={} | mismatched={} | partnerOnly={} | internalOnly={}",
                    run.getRunId(),
                    run.getTotalPartnerRecords(),
                    run.getTotalInternalRecords(),
                    run.getMatchedCount(),
                    run.getMismatchedCount(),
                    run.getPartnerOnlyCount(),
                    run.getInternalOnlyCount()
            );

            return run;
        } catch (Exception e) {
            run.setStatus("FAILED");
            run.setErrorMessage(e.getMessage());
            run.setFinishedAt(LocalDateTime.now());
            reconciliationRunRepository.save(run);
            throw new IllegalStateException("Failed to run reconciliation", e);
        }
    }

    private DataContainer mapRowToDataContainer(Row row, PartnerConfig config) throws Exception {
        String id       = getCellString(row, config.getColId() - 1);
        String trace    = getCellString(row, config.getColTrace() - 1);
        String amountStr = getCellString(row, config.getColAmount() - 1)
                .replace(",", "")
                .trim();
        log.info("DEBUG amount raw: '{}'", amountStr);
        BigDecimal amount = new BigDecimal(amountStr);
        String rawStatus = getCellString(row, config.getColStatus() - 1);
        String transDateStr = getCellString(row, config.getColTransDate() - 1);

        // Map status
        String status = config.getStatusMapping()
                .getOrDefault(rawStatus,
                        config.getStatusMapping().getOrDefault("others", "FAILED"));

        // Convert date → milliseconds
        long transDateMs = LocalDateTime.parse(transDateStr, DATE_FMT)
                .atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh"))
                .toInstant().toEpochMilli();

        // Build partnerData
        PartnerData partnerData = PartnerData.builder()
                ._id(id)
                .trace(trace)
                .amount(amount)
                .currency(config.getCurrency())
                .status(status)
                .transDate(transDateMs)
                .extra(PartnerData.Extra.builder()
                        .vspTransId(trace)
                        .service(config.getService())
                        .portal(config.getPortal())
                        .provider(config.getProvider())
                        .method(config.getMethod())
                        .merchantSettleDate(transDateMs)
                        .providerSettleDate(transDateMs)
                        .build())
                .build();

        return DataContainer.builder()
                .requestId(UUID.randomUUID().toString())
                .identify(config.getIdentify())
                .reconciliationDate("07/07/2024 00:00:00")
                .operationStatus("IN_PROGRESS")
                .reconciliationStatus("")
                .workflowType("UPC")
                .connectorData("")
                .extraData("")
                .createdBy("system")
                .createdDate(LocalDateTime.now())
                .lastModifiedBy("system")
                .lastModifiedDate(LocalDateTime.now())
                .partnerData(objectMapper.writeValueAsString(partnerData))
                .build();
    }

    private String getCellString(Row row, int colIndex) {
        Cell cell = row.getCell(colIndex);
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    yield DATE_FMT.format(cell.getLocalDateTimeCellValue());
                }
                yield BigDecimal.valueOf(cell.getNumericCellValue())
                        .stripTrailingZeros()
                        .toPlainString();
            }
            default -> "";
        };
    }

    private Map<String, PartnerRecord> loadPartnerRecords(List<DataContainer> containers) {
        Map<String, PartnerRecord> records = new LinkedHashMap<>();

        for (DataContainer container : containers) {
            try {
                PartnerRecord record = toPartnerRecord(container);
                if (!hasText(record.matchKey())) {
                    log.warn("Skip partner record without match key, requestId={}", container.getRequestId());
                    continue;
                }

                records.putIfAbsent(record.matchKey(), record);
            } catch (Exception e) {
                log.error("Cannot parse partnerData, requestId={}: {}",
                        container.getRequestId(), e.getMessage(), e);
            }
        }

        return records;
    }

    private Map<String, InternalTransaction> loadInternalRecords(String identify) {
        Map<String, InternalTransaction> records = new LinkedHashMap<>();
        for (InternalTransaction transaction : internalTransactionRepository.findByProvider(identify)) {
            String matchKey = resolveMatchKey(transaction.getTrace(), transaction.getTransactionId());
            if (!hasText(matchKey)) {
                log.warn("Skip internal transaction without match key, transactionId={}",
                        transaction.getTransactionId());
                continue;
            }

            records.putIfAbsent(matchKey, transaction);
        }

        return records;
    }

    private PartnerRecord toPartnerRecord(DataContainer container) throws Exception {
        JsonNode root = objectMapper.readTree(container.getPartnerData());
        String transactionId = text(root, "_id");
        String trace = text(root, "trace");

        return new PartnerRecord(
                container.getRequestId(),
                transactionId,
                trace,
                decimal(root.get("amount")),
                text(root, "currency"),
                text(root, "status"),
                partnerDate(root.get("transDate")),
                resolveMatchKey(trace, transactionId)
        );
    }

    private List<String> compare(PartnerRecord partnerRecord, InternalTransaction internalTransaction) {
        List<String> differences = new ArrayList<>();

        if (partnerRecord.amount() == null
                || internalTransaction.getAmount() == null
                || partnerRecord.amount().compareTo(internalTransaction.getAmount()) != 0) {
            differences.add("amount");
        }

        if (!equalsIgnoreCase(partnerRecord.currency(), internalTransaction.getCurrency())) {
            differences.add("currency");
        }

        if (!equalsIgnoreCase(partnerRecord.status(), internalTransaction.getStatus())) {
            differences.add("status");
        }

        if (partnerRecord.transDate() == null
                || internalTransaction.getTransDate() == null
                || !partnerRecord.transDate().equals(internalTransaction.getTransDate())) {
            differences.add("transDate");
        }

        return differences;
    }

    private ReconciliationResult buildResult(
            String runId,
            PartnerRecord partnerRecord,
            InternalTransaction internalTransaction,
            String resultStatus,
            List<String> differenceFields
    ) {
        String matchKey = partnerRecord != null
                ? partnerRecord.matchKey()
                : resolveMatchKey(internalTransaction.getTrace(), internalTransaction.getTransactionId());

        return ReconciliationResult.builder()
                .resultId(UUID.randomUUID().toString())
                .runId(runId)
                .matchKey(matchKey)
                .partnerRequestId(partnerRecord == null ? null : partnerRecord.requestId())
                .partnerTransactionId(partnerRecord == null ? null : partnerRecord.transactionId())
                .internalTransactionId(internalTransaction == null ? null : internalTransaction.getTransactionId())
                .resultStatus(resultStatus)
                .differenceFields(String.join(",", differenceFields))
                .partnerAmount(partnerRecord == null ? null : partnerRecord.amount())
                .internalAmount(internalTransaction == null ? null : internalTransaction.getAmount())
                .partnerCurrency(partnerRecord == null ? null : partnerRecord.currency())
                .internalCurrency(internalTransaction == null ? null : internalTransaction.getCurrency())
                .partnerStatus(partnerRecord == null ? null : partnerRecord.status())
                .internalStatus(internalTransaction == null ? null : internalTransaction.getStatus())
                .partnerTransDate(partnerRecord == null ? null : partnerRecord.transDate())
                .internalTransDate(internalTransaction == null ? null : internalTransaction.getTransDate())
                .createdDate(LocalDateTime.now())
                .build();
    }

    private String resolveMatchKey(String trace, String transactionId) {
        if (hasText(trace)) {
            return trace.trim();
        }

        if (hasText(transactionId)) {
            return transactionId.trim();
        }

        return "";
    }

    private String text(JsonNode root, String fieldName) {
        JsonNode node = root.get(fieldName);
        if (node == null || node.isNull()) {
            return "";
        }

        return node.asText("").trim();
    }

    private BigDecimal decimal(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }

        if (node.isNumber()) {
            return node.decimalValue();
        }

        String value = node.asText("").replace(",", "").trim();
        return value.isEmpty() ? null : new BigDecimal(value);
    }

    private LocalDateTime partnerDate(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }

        if (node.isNumber()) {
            return fromEpochMillis(node.asLong());
        }

        JsonNode numberLong = node.path("$date").path("$numberLong");
        if (!numberLong.isMissingNode()) {
            return fromEpochMillis(numberLong.asLong());
        }

        String value = node.asText("").trim();
        if (value.isEmpty()) {
            return null;
        }

        return LocalDateTime.parse(value, DATE_FMT);
    }

    private LocalDateTime fromEpochMillis(long epochMillis) {
        return LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(epochMillis),
                RECONCILIATION_ZONE
        );
    }

    private boolean equalsIgnoreCase(String left, String right) {
        return normalize(left).equalsIgnoreCase(normalize(right));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private record PartnerRecord(
            String requestId,
            String transactionId,
            String trace,
            BigDecimal amount,
            String currency,
            String status,
            LocalDateTime transDate,
            String matchKey
    ) {
    }
}
