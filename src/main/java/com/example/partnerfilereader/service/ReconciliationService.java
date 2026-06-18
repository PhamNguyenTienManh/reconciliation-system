package com.example.partnerfilereader.service;

import com.example.partnerfilereader.config.PartnerConfig;
import com.example.partnerfilereader.model.DataContainer;
import com.example.partnerfilereader.model.InternalTransaction;
import com.example.partnerfilereader.model.PartnerData;
import com.example.partnerfilereader.model.ReconciliationResult;
import com.example.partnerfilereader.model.ReconciliationRun;
import com.example.partnerfilereader.repository.DataContainerRepository;
import com.example.partnerfilereader.repository.InternalTransactionRepository;
import com.example.partnerfilereader.repository.ReconciliationRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
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
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final ZoneId RECONCILIATION_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final int PAGE_SIZE = 10000;
    private static final int BATCH_SIZE = 10000;
    private static final String MATCHED_CONDITION = """
            p.partner_amount IS NOT NULL
            AND i.internal_amount IS NOT NULL
            AND p.partner_amount = i.internal_amount
            AND LOWER(TRIM(COALESCE(p.partner_currency, ''))) = LOWER(TRIM(COALESCE(i.internal_currency, '')))
            AND LOWER(TRIM(COALESCE(p.partner_status, ''))) = LOWER(TRIM(COALESCE(i.internal_status, '')))
            AND p.partner_trans_date IS NOT NULL
            AND i.internal_trans_date IS NOT NULL
            AND p.partner_trans_date = i.internal_trans_date
            """;
    private static final String INSERT_RECONCILIATION_RESULT_SQL = """
            INSERT INTO reconciliation_results (
                run_id,
                match_key,
                partner_request_id,
                partner_transaction_id,
                internal_transaction_id,
                result_status,
                difference_fields,
                partner_amount,
                internal_amount,
                partner_currency,
                internal_currency,
                partner_status,
                internal_status,
                partner_trans_date,
                internal_trans_date,
                created_date
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    public ImportSummary process(PartnerConfig config, String dataFilePath) throws IOException {
        if (isCsvFile(dataFilePath)) {
            return processCsv(config, dataFilePath);
        }

        return processExcel(config, dataFilePath);
    }

    private ImportSummary processExcel(PartnerConfig config, String dataFilePath) throws IOException {
        long tTotal = System.currentTimeMillis();
        int totalRead = 0, totalSaved = 0, totalError = 0;
        List<DataContainer> dataContainers = new ArrayList<>();

        try (FileInputStream fis = new FileInputStream(dataFilePath);
             Workbook wb = new XSSFWorkbook(fis)) {

            Sheet sheet = wb.getSheetAt(0);
            log.info("BENCHMARK [process-1] mở file Excel: {}ms",
                    System.currentTimeMillis() - tTotal);
            long tLoop = System.currentTimeMillis();

            // rowBegin = 7 là dòng header, data bắt đầu từ index = rowBegin (dòng 8)
            for (int i = config.getRowBegin(); i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                totalRead++;
                try {
                    DataContainer dc = mapRowToDataContainer(row, config);
                    dataContainers.add(dc);
                    totalSaved++;
                    if (dataContainers.size() >= BATCH_SIZE) {
                        long tSave = System.currentTimeMillis();
                        repository.saveAll(dataContainers);
                        log.info("BENCHMARK [process-2] saveAll DataContainer: {}ms | size={}",
                                System.currentTimeMillis() - tSave, dataContainers.size());
                        dataContainers.clear();
                    }
                } catch (Exception e) {
                    totalError++;
                    log.error("Lỗi dòng {}: {}", i + 1, e.getMessage(), e);
                }
            }

            log.info("BENCHMARK [process-3] toàn bộ vòng for đọc Excel + save: {}ms | totalRead={}",
                    System.currentTimeMillis() - tLoop, totalRead);

            if (!dataContainers.isEmpty()) {
                long tSave = System.currentTimeMillis();
                repository.saveAll(dataContainers);
                log.info("BENCHMARK [process-2] saveAll DataContainer: {}ms | size={}",
                        System.currentTimeMillis() - tSave, dataContainers.size());
                dataContainers.clear();
            }
        }

        log.info("Tổng đọc: {} | Lưu thành công: {} | Lỗi: {}",
                totalRead, totalSaved, totalError);
        log.info("BENCHMARK [process-TOTAL] process hoàn tất: {}ms",
                System.currentTimeMillis() - tTotal);
        return new ImportSummary(totalRead, totalSaved, totalError);
    }

    private ImportSummary processCsv(PartnerConfig config, String dataFilePath) throws IOException {
        long tTotal = System.currentTimeMillis();
        int totalRead = 0, totalSaved = 0, totalError = 0;
        List<DataContainer> dataContainers = new ArrayList<>();

        try (BufferedReader reader = Files.newBufferedReader(Path.of(dataFilePath), StandardCharsets.UTF_8)) {
            log.info("BENCHMARK [process-1] mở file CSV: {}ms",
                    System.currentTimeMillis() - tTotal);
            long tLoop = System.currentTimeMillis();

            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (lineNo <= config.getRowBegin() || line.isBlank()) {
                    continue;
                }

                totalRead++;
                try {
                    DataContainer dc = mapCsvLineToDataContainer(line, config);
                    dataContainers.add(dc);
                    totalSaved++;
                    if (dataContainers.size() >= BATCH_SIZE) {
                        long tSave = System.currentTimeMillis();
                        repository.saveAll(dataContainers);
                        log.info("BENCHMARK [process-2] saveAll DataContainer: {}ms | size={}",
                                System.currentTimeMillis() - tSave, dataContainers.size());
                        dataContainers.clear();
                    }
                } catch (Exception e) {
                    totalError++;
                    log.error("Lỗi dòng {}: {}", lineNo, e.getMessage(), e);
                }
            }

            log.info("BENCHMARK [process-3] toàn bộ vòng for đọc CSV + save: {}ms | totalRead={}",
                    System.currentTimeMillis() - tLoop, totalRead);

            if (!dataContainers.isEmpty()) {
                long tSave = System.currentTimeMillis();
                repository.saveAll(dataContainers);
                log.info("BENCHMARK [process-2] saveAll DataContainer: {}ms | size={}",
                        System.currentTimeMillis() - tSave, dataContainers.size());
                dataContainers.clear();
            }
        }

        log.info("Tổng đọc: {} | Lưu thành công: {} | Lỗi: {}",
                totalRead, totalSaved, totalError);
        log.info("BENCHMARK [process-TOTAL] process hoàn tất: {}ms",
                System.currentTimeMillis() - tTotal);
        return new ImportSummary(totalRead, totalSaved, totalError);
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
        long tTotal = System.currentTimeMillis();

        try {
            SqlReconciliationSummary summary = runSqlReconciliation(
                    run.getRunId(),
                    identify,
                    reconciliationDate,
                    tTotal
            );

            run.setTotalPartnerRecords(summary.totalPartnerRecords());
            run.setTotalInternalRecords(summary.totalInternalRecords());
            run.setMatchedCount(summary.matchedCount());
            run.setMismatchedCount(summary.mismatchedCount());
            run.setPartnerOnlyCount(summary.partnerOnlyCount());
            run.setInternalOnlyCount(summary.internalOnlyCount());
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

            log.info("BENCHMARK [TOTAL] reconcile: {}ms",
                    System.currentTimeMillis() - tTotal);
            return run;
        } catch (Exception e) {
            run.setStatus("FAILED");
            run.setErrorMessage(e.getMessage());
            run.setFinishedAt(LocalDateTime.now());
            reconciliationRunRepository.save(run);
            throw new IllegalStateException("Failed to run reconciliation", e);
        }
    }

    private SqlReconciliationSummary runSqlReconciliation(
            String runId,
            String identify,
            String reconciliationDate,
            long tTotal
    ) {
        return jdbcTemplate.execute((ConnectionCallback<SqlReconciliationSummary>) connection -> {
            long tIndex = System.currentTimeMillis();
            ensureReconciliationIndexes(connection);
            log.info("BENCHMARK [-1] SQL ensure reconciliation indexes: {}ms",
                    System.currentTimeMillis() - tIndex);

            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                long tSetup = System.currentTimeMillis();
                execute(connection, "SET time_zone = '+07:00'");
                execute(connection, "DROP TEMPORARY TABLE IF EXISTS tmp_partner_reconciliation");
                execute(connection, "DROP TEMPORARY TABLE IF EXISTS tmp_internal_reconciliation");
                execute(connection, """
                        CREATE TEMPORARY TABLE tmp_partner_reconciliation (
                            match_key VARCHAR(128) NOT NULL PRIMARY KEY,
                            request_id VARCHAR(255),
                            partner_transaction_id VARCHAR(64),
                            partner_amount DECIMAL(19, 2),
                            partner_currency VARCHAR(16),
                            partner_status VARCHAR(32),
                            partner_trans_date DATETIME
                        ) ENGINE=InnoDB
                        """);
                execute(connection, """
                        CREATE TEMPORARY TABLE tmp_internal_reconciliation (
                            match_key VARCHAR(128) NOT NULL PRIMARY KEY,
                            internal_transaction_id VARCHAR(64),
                            internal_amount DECIMAL(19, 2),
                            internal_currency VARCHAR(16),
                            internal_status VARCHAR(32),
                            internal_trans_date DATETIME
                        ) ENGINE=InnoDB
                        """);
                log.info("BENCHMARK [0] SQL temp table setup: {}ms",
                        System.currentTimeMillis() - tSetup);

                long tBackfill = System.currentTimeMillis();
                int backfilledPartnerRecords = backfillPartnerProjectionIfNeeded(
                        connection,
                        identify,
                        reconciliationDate
                );
                log.info("BENCHMARK [0.5] SQL backfill partner projection: {}ms | updated={}",
                        System.currentTimeMillis() - tBackfill,
                        backfilledPartnerRecords);

                long tPartner = System.currentTimeMillis();
                PartnerTempLoad partnerLoad = insertPartnerTempRecords(
                        connection,
                        identify,
                        reconciliationDate
                );
                log.info("BENCHMARK [1] SQL load partner temp table: {}ms | size={} | filteredByDate={}",
                        System.currentTimeMillis() - tPartner,
                        partnerLoad.totalRecords(),
                        partnerLoad.filteredByDate());

                long tInternal = System.currentTimeMillis();
                int totalInternalRecords = update(
                        connection,
                        """
                                INSERT IGNORE INTO tmp_internal_reconciliation (
                                    match_key,
                                    internal_transaction_id,
                                    internal_amount,
                                    internal_currency,
                                    internal_status,
                                    internal_trans_date
                                )
                                SELECT
                                    COALESCE(NULLIF(TRIM(trace), ''), NULLIF(TRIM(transaction_id), '')) AS match_key,
                                    transaction_id,
                                    amount,
                                    currency,
                                    status,
                                    trans_date
                                FROM internal_transactions
                                WHERE provider = ?
                                  AND COALESCE(NULLIF(TRIM(trace), ''), NULLIF(TRIM(transaction_id), '')) IS NOT NULL
                                ORDER BY created_date DESC
                                """,
                        identify
                );
                log.info("BENCHMARK [2] SQL load internal temp table: {}ms | size={}",
                        System.currentTimeMillis() - tInternal,
                        totalInternalRecords);

                long tInsert = System.currentTimeMillis();
                int internalSideResults = insertInternalSideResults(connection, runId);
                int partnerOnlyResults = insertPartnerOnlyResults(connection, runId);
                int insertedResults = internalSideResults + partnerOnlyResults;
                log.info("BENCHMARK [3] SQL insert reconciliation_results: {}ms | internalSide={} | partnerOnly={} | inserted={}",
                        System.currentTimeMillis() - tInsert,
                        internalSideResults,
                        partnerOnlyResults,
                        insertedResults);

                long tSummary = System.currentTimeMillis();
                ResultStatusCounts counts = countResultStatuses(connection, runId);
                log.info("BENCHMARK [4] SQL count result statuses: {}ms | matched={} | mismatched={} | internalOnly={} | partnerOnly={}",
                        System.currentTimeMillis() - tSummary,
                        counts.matched(),
                        counts.mismatched(),
                        counts.internalOnly(),
                        counts.partnerOnly());

                long tCommit = System.currentTimeMillis();
                connection.commit();
                log.info("BENCHMARK [5] SQL commit reconciliation transaction: {}ms",
                        System.currentTimeMillis() - tCommit);
                log.info("BENCHMARK [TOTAL-SQL] reconcile SQL work: {}ms",
                        System.currentTimeMillis() - tTotal);
                return new SqlReconciliationSummary(
                        partnerLoad.totalRecords(),
                        totalInternalRecords,
                        counts.matched(),
                        counts.mismatched(),
                        counts.partnerOnly(),
                        counts.internalOnly()
                );
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        });
    }

    private void ensureReconciliationIndexes(java.sql.Connection connection) throws SQLException {
        execute(connection, """
                CREATE TABLE IF NOT EXISTS reconciliation_projection_state (
                    identify VARCHAR(64) NOT NULL,
                    reconciliation_date VARCHAR(32) NOT NULL,
                    partner_projection_ready BOOLEAN NOT NULL,
                    updated_at DATETIME NOT NULL,
                    PRIMARY KEY (identify, reconciliation_date)
                ) ENGINE=InnoDB
                """);
        migrateReconciliationResultIdToAutoIncrement(connection);
        createIndexIfMissing(
                connection,
                "data_container",
                "idx_data_container_identify_recon_date",
                "CREATE INDEX idx_data_container_identify_recon_date ON data_container (identify, reconciliation_date)"
        );
        createIndexIfMissing(
                connection,
                "data_container",
                "idx_data_container_identify_created",
                "CREATE INDEX idx_data_container_identify_created ON data_container (identify, created_date)"
        );
        createIndexIfMissing(
                connection,
                "data_container",
                "idx_data_container_recon_trace",
                "CREATE INDEX idx_data_container_recon_trace ON data_container (identify, reconciliation_date, partner_trace)"
        );
        createIndexIfMissing(
                connection,
                "data_container",
                "idx_data_container_recon_transaction",
                "CREATE INDEX idx_data_container_recon_transaction ON data_container (identify, reconciliation_date, partner_transaction_id)"
        );
        createIndexIfMissing(
                connection,
                "internal_transactions",
                "idx_internal_provider_trace",
                "CREATE INDEX idx_internal_provider_trace ON internal_transactions (provider, trace)"
        );
        createIndexIfMissing(
                connection,
                "internal_transactions",
                "idx_internal_provider_transaction",
                "CREATE INDEX idx_internal_provider_transaction ON internal_transactions (provider, transaction_id)"
        );
        createIndexIfMissing(
                connection,
                "reconciliation_results",
                "idx_recon_result_run_status",
                "CREATE INDEX idx_recon_result_run_status ON reconciliation_results (run_id, result_status)"
        );
        createIndexIfMissing(
                connection,
                "reconciliation_results",
                "idx_recon_result_run_created",
                "CREATE INDEX idx_recon_result_run_created ON reconciliation_results (run_id, created_date)"
        );
        dropIndexIfExists(
                connection,
                "reconciliation_results",
                "idx_recon_result_match_key"
        );
    }

    private void migrateReconciliationResultIdToAutoIncrement(java.sql.Connection connection) throws SQLException {
        ColumnDefinition resultId = getColumnDefinition(connection, "reconciliation_results", "result_id");
        if (resultId == null || resultId.isBigIntAutoIncrement()) {
            return;
        }

        long startedAt = System.currentTimeMillis();
        if (!columnExists(connection, "reconciliation_results", "legacy_result_id")) {
            execute(connection, "ALTER TABLE reconciliation_results ADD COLUMN legacy_result_id VARCHAR(64) NULL");
        }
        update(
                connection,
                """
                        UPDATE reconciliation_results
                        SET legacy_result_id = CAST(result_id AS CHAR)
                        WHERE legacy_result_id IS NULL
                        """
        );
        if (hasPrimaryKey(connection, "reconciliation_results")) {
            execute(connection, "ALTER TABLE reconciliation_results DROP PRIMARY KEY");
        }
        execute(connection, "ALTER TABLE reconciliation_results DROP COLUMN result_id");
        execute(connection, """
                ALTER TABLE reconciliation_results
                ADD COLUMN result_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY FIRST
                """);
        log.info("Migrated reconciliation_results.result_id to BIGINT AUTO_INCREMENT in {}ms",
                System.currentTimeMillis() - startedAt);
    }

    private ColumnDefinition getColumnDefinition(
            java.sql.Connection connection,
            String tableName,
            String columnName
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT data_type, extra
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND column_name = ?
                """)) {
            statement.setString(1, tableName);
            statement.setString(2, columnName);
            try (java.sql.ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return new ColumnDefinition(resultSet.getString(1), resultSet.getString(2));
            }
        }
    }

    private boolean columnExists(
            java.sql.Connection connection,
            String tableName,
            String columnName
    ) throws SQLException {
        return getColumnDefinition(connection, tableName, columnName) != null;
    }

    private boolean hasPrimaryKey(java.sql.Connection connection, String tableName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1
                FROM information_schema.table_constraints
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND constraint_type = 'PRIMARY KEY'
                LIMIT 1
                """)) {
            statement.setString(1, tableName);
            try (java.sql.ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private void createIndexIfMissing(
            java.sql.Connection connection,
            String tableName,
            String indexName,
            String createSql
    ) throws SQLException {
        if (indexExists(connection, tableName, indexName)) {
            return;
        }

        long startedAt = System.currentTimeMillis();
        execute(connection, createSql);
        log.info("Created missing index {} on {} in {}ms",
                indexName,
                tableName,
                System.currentTimeMillis() - startedAt);
    }

    private void dropIndexIfExists(
            java.sql.Connection connection,
            String tableName,
            String indexName
    ) throws SQLException {
        if (!indexExists(connection, tableName, indexName)) {
            return;
        }

        long startedAt = System.currentTimeMillis();
        execute(connection, "DROP INDEX " + indexName + " ON " + tableName);
        log.info("Dropped unused index {} on {} in {}ms",
                indexName,
                tableName,
                System.currentTimeMillis() - startedAt);
    }

    private boolean indexExists(
            java.sql.Connection connection,
            String tableName,
            String indexName
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND index_name = ?
                LIMIT 1
                """)) {
            statement.setString(1, tableName);
            statement.setString(2, indexName);
            try (java.sql.ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private int backfillPartnerProjectionIfNeeded(
            java.sql.Connection connection,
            String identify,
            String reconciliationDate
    ) throws SQLException {
        if (isPartnerProjectionReady(connection, identify, reconciliationDate)) {
            return 0;
        }

        if (!hasMissingPartnerProjection(connection, identify, reconciliationDate)) {
            markPartnerProjectionReady(connection, identify, reconciliationDate);
            return 0;
        }

        int updated = update(
                connection,
                """
                        UPDATE data_container
                        SET
                            partner_transaction_id = NULLIF(TRIM(JSON_UNQUOTE(JSON_EXTRACT(partner_data, '$._id'))), ''),
                            partner_trace = NULLIF(TRIM(JSON_UNQUOTE(JSON_EXTRACT(partner_data, '$.trace'))), ''),
                            partner_amount = CAST(NULLIF(REPLACE(JSON_UNQUOTE(JSON_EXTRACT(partner_data, '$.amount')), ',', ''), '') AS DECIMAL(19, 2)),
                            partner_currency = NULLIF(TRIM(JSON_UNQUOTE(JSON_EXTRACT(partner_data, '$.currency'))), ''),
                            partner_status = NULLIF(TRIM(JSON_UNQUOTE(JSON_EXTRACT(partner_data, '$.status'))), ''),
                            partner_trans_date = FROM_UNIXTIME(CAST(JSON_UNQUOTE(JSON_EXTRACT(partner_data, '$.transDate')) AS UNSIGNED) / 1000)
                        WHERE identify = ?
                          AND reconciliation_date = ?
                          AND partner_data IS NOT NULL
                          AND partner_data <> ''
                          AND (
                              partner_transaction_id IS NULL
                              OR partner_amount IS NULL
                              OR partner_currency IS NULL
                              OR partner_status IS NULL
                              OR partner_trans_date IS NULL
                          )
                        """,
                identify,
                reconciliationDate
        );
        markPartnerProjectionReady(connection, identify, reconciliationDate);
        return updated;
    }

    private boolean isPartnerProjectionReady(
            java.sql.Connection connection,
            String identify,
            String reconciliationDate
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT partner_projection_ready
                FROM reconciliation_projection_state
                WHERE identify = ? AND reconciliation_date = ?
                """)) {
            statement.setString(1, identify);
            statement.setString(2, reconciliationDate);
            try (java.sql.ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getBoolean(1);
            }
        }
    }

    private void markPartnerProjectionReady(
            java.sql.Connection connection,
            String identify,
            String reconciliationDate
    ) throws SQLException {
        update(
                connection,
                """
                        INSERT INTO reconciliation_projection_state (
                            identify,
                            reconciliation_date,
                            partner_projection_ready,
                            updated_at
                        ) VALUES (?, ?, TRUE, NOW())
                        ON DUPLICATE KEY UPDATE
                            partner_projection_ready = VALUES(partner_projection_ready),
                            updated_at = VALUES(updated_at)
                        """,
                identify,
                reconciliationDate
        );
    }

    private boolean hasMissingPartnerProjection(
            java.sql.Connection connection,
            String identify,
            String reconciliationDate
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1
                FROM data_container
                WHERE identify = ?
                  AND reconciliation_date = ?
                  AND partner_data IS NOT NULL
                  AND partner_data <> ''
                  AND (
                      partner_transaction_id IS NULL
                      OR partner_amount IS NULL
                      OR partner_currency IS NULL
                      OR partner_status IS NULL
                      OR partner_trans_date IS NULL
                  )
                LIMIT 1
                """)) {
            statement.setString(1, identify);
            statement.setString(2, reconciliationDate);
            try (java.sql.ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private PartnerTempLoad insertPartnerTempRecords(
            java.sql.Connection connection,
            String identify,
            String reconciliationDate
    ) throws SQLException {
        String datedSql = partnerTempInsertSql(
                "WHERE identify = ? AND reconciliation_date = ? AND partner_data IS NOT NULL AND partner_data <> ''"
        );
        int datedRecords = update(connection, datedSql, identify, reconciliationDate);
        if (datedRecords > 0) {
            return new PartnerTempLoad(datedRecords, true);
        }

        String fallbackSql = partnerTempInsertSql(
                "WHERE identify = ? AND partner_data IS NOT NULL AND partner_data <> ''"
        );
        int fallbackRecords = update(connection, fallbackSql, identify);
        return new PartnerTempLoad(fallbackRecords, false);
    }

    private String partnerTempInsertSql(String whereClause) {
        return """
                INSERT IGNORE INTO tmp_partner_reconciliation (
                    match_key,
                    request_id,
                    partner_transaction_id,
                    partner_amount,
                    partner_currency,
                    partner_status,
                    partner_trans_date
                )
                SELECT
                    match_key,
                    request_id,
                    partner_transaction_id,
                    partner_amount,
                    partner_currency,
                    partner_status,
                    partner_trans_date
                FROM (
                    SELECT
                        request_id,
                        COALESCE(
                            NULLIF(TRIM(partner_trace), ''),
                            NULLIF(TRIM(partner_transaction_id), '')
                        ) AS match_key,
                        partner_transaction_id,
                        partner_amount,
                        partner_currency,
                        partner_status,
                        partner_trans_date,
                        created_date
                    FROM data_container
                    %s
                    ORDER BY created_date DESC
                ) partner_source
                WHERE match_key IS NOT NULL
                """.formatted(whereClause);
    }

    private int insertInternalSideResults(java.sql.Connection connection, String runId) throws SQLException {
        String sql = """
                INSERT INTO reconciliation_results (
                    run_id,
                    match_key,
                    partner_request_id,
                    partner_transaction_id,
                    internal_transaction_id,
                    result_status,
                    difference_fields,
                    partner_amount,
                    internal_amount,
                    partner_currency,
                    internal_currency,
                    partner_status,
                    internal_status,
                    partner_trans_date,
                    internal_trans_date,
                    created_date
                )
                SELECT
                    ?,
                    i.match_key,
                    p.request_id,
                    p.partner_transaction_id,
                    i.internal_transaction_id,
                    CASE
                        WHEN p.match_key IS NULL THEN 'INTERNAL_ONLY'
                        WHEN %s THEN 'MATCHED'
                        ELSE 'MISMATCHED'
                    END,
                    CASE
                        WHEN p.match_key IS NULL THEN 'missing_partner_transaction'
                        WHEN %s THEN ''
                        ELSE CONCAT_WS(',',
                            IF(p.partner_amount IS NULL OR i.internal_amount IS NULL OR p.partner_amount <> i.internal_amount, 'amount', NULL),
                            IF(LOWER(TRIM(COALESCE(p.partner_currency, ''))) <> LOWER(TRIM(COALESCE(i.internal_currency, ''))), 'currency', NULL),
                            IF(LOWER(TRIM(COALESCE(p.partner_status, ''))) <> LOWER(TRIM(COALESCE(i.internal_status, ''))), 'status', NULL),
                            IF(p.partner_trans_date IS NULL OR i.internal_trans_date IS NULL OR p.partner_trans_date <> i.internal_trans_date, 'transDate', NULL)
                        )
                    END,
                    p.partner_amount,
                    i.internal_amount,
                    p.partner_currency,
                    i.internal_currency,
                    p.partner_status,
                    i.internal_status,
                    p.partner_trans_date,
                    i.internal_trans_date,
                    NOW()
                FROM tmp_internal_reconciliation i
                LEFT JOIN tmp_partner_reconciliation p ON p.match_key = i.match_key
                ORDER BY i.match_key
                """.formatted(MATCHED_CONDITION, MATCHED_CONDITION);
        return update(connection, sql, runId);
    }

    private ResultStatusCounts countResultStatuses(java.sql.Connection connection, String runId) throws SQLException {
        int matched = 0;
        int mismatched = 0;
        int partnerOnly = 0;
        int internalOnly = 0;

        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT result_status, COUNT(*)
                FROM reconciliation_results
                WHERE run_id = ?
                GROUP BY result_status
                """)) {
            statement.setString(1, runId);
            try (java.sql.ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String status = resultSet.getString(1);
                    int count = resultSet.getInt(2);
                    if ("MATCHED".equals(status)) {
                        matched = count;
                    } else if ("MISMATCHED".equals(status)) {
                        mismatched = count;
                    } else if ("PARTNER_ONLY".equals(status)) {
                        partnerOnly = count;
                    } else if ("INTERNAL_ONLY".equals(status)) {
                        internalOnly = count;
                    }
                }
            }
        }

        return new ResultStatusCounts(matched, mismatched, partnerOnly, internalOnly);
    }

    private int insertMatchedResults(java.sql.Connection connection, String runId) throws SQLException {
        String sql = """
                INSERT INTO reconciliation_results (
                    run_id,
                    match_key,
                    partner_request_id,
                    partner_transaction_id,
                    internal_transaction_id,
                    result_status,
                    difference_fields,
                    partner_amount,
                    internal_amount,
                    partner_currency,
                    internal_currency,
                    partner_status,
                    internal_status,
                    partner_trans_date,
                    internal_trans_date,
                    created_date
                )
                SELECT
                    ?,
                    i.match_key,
                    p.request_id,
                    p.partner_transaction_id,
                    i.internal_transaction_id,
                    'MATCHED',
                    '',
                    p.partner_amount,
                    i.internal_amount,
                    p.partner_currency,
                    i.internal_currency,
                    p.partner_status,
                    i.internal_status,
                    p.partner_trans_date,
                    i.internal_trans_date,
                    NOW()
                FROM tmp_internal_reconciliation i
                JOIN tmp_partner_reconciliation p ON p.match_key = i.match_key
                WHERE %s
                """.formatted(MATCHED_CONDITION);
        return update(connection, sql, runId);
    }

    private int insertMismatchedResults(java.sql.Connection connection, String runId) throws SQLException {
        String sql = """
                INSERT INTO reconciliation_results (
                    run_id,
                    match_key,
                    partner_request_id,
                    partner_transaction_id,
                    internal_transaction_id,
                    result_status,
                    difference_fields,
                    partner_amount,
                    internal_amount,
                    partner_currency,
                    internal_currency,
                    partner_status,
                    internal_status,
                    partner_trans_date,
                    internal_trans_date,
                    created_date
                )
                SELECT
                    ?,
                    i.match_key,
                    p.request_id,
                    p.partner_transaction_id,
                    i.internal_transaction_id,
                    'MISMATCHED',
                    CONCAT_WS(',',
                        IF(p.partner_amount IS NULL OR i.internal_amount IS NULL OR p.partner_amount <> i.internal_amount, 'amount', NULL),
                        IF(LOWER(TRIM(COALESCE(p.partner_currency, ''))) <> LOWER(TRIM(COALESCE(i.internal_currency, ''))), 'currency', NULL),
                        IF(LOWER(TRIM(COALESCE(p.partner_status, ''))) <> LOWER(TRIM(COALESCE(i.internal_status, ''))), 'status', NULL),
                        IF(p.partner_trans_date IS NULL OR i.internal_trans_date IS NULL OR p.partner_trans_date <> i.internal_trans_date, 'transDate', NULL)
                    ),
                    p.partner_amount,
                    i.internal_amount,
                    p.partner_currency,
                    i.internal_currency,
                    p.partner_status,
                    i.internal_status,
                    p.partner_trans_date,
                    i.internal_trans_date,
                    NOW()
                FROM tmp_internal_reconciliation i
                JOIN tmp_partner_reconciliation p ON p.match_key = i.match_key
                WHERE NOT (%s)
                """.formatted(MATCHED_CONDITION);
        return update(connection, sql, runId);
    }

    private int insertInternalOnlyResults(java.sql.Connection connection, String runId) throws SQLException {
        return update(
                connection,
                """
                        INSERT INTO reconciliation_results (
                            run_id,
                            match_key,
                            partner_request_id,
                            partner_transaction_id,
                            internal_transaction_id,
                            result_status,
                            difference_fields,
                            partner_amount,
                            internal_amount,
                            partner_currency,
                            internal_currency,
                            partner_status,
                            internal_status,
                            partner_trans_date,
                            internal_trans_date,
                            created_date
                        )
                        SELECT
                            ?,
                            i.match_key,
                            NULL,
                            NULL,
                            i.internal_transaction_id,
                            'INTERNAL_ONLY',
                            'missing_partner_transaction',
                            NULL,
                            i.internal_amount,
                            NULL,
                            i.internal_currency,
                            NULL,
                            i.internal_status,
                            NULL,
                            i.internal_trans_date,
                            NOW()
                        FROM tmp_internal_reconciliation i
                        LEFT JOIN tmp_partner_reconciliation p ON p.match_key = i.match_key
                        WHERE p.match_key IS NULL
                        """,
                runId
        );
    }

    private int insertPartnerOnlyResults(java.sql.Connection connection, String runId) throws SQLException {
        return update(
                connection,
                """
                        INSERT INTO reconciliation_results (
                            run_id,
                            match_key,
                            partner_request_id,
                            partner_transaction_id,
                            internal_transaction_id,
                            result_status,
                            difference_fields,
                            partner_amount,
                            internal_amount,
                            partner_currency,
                            internal_currency,
                            partner_status,
                            internal_status,
                            partner_trans_date,
                            internal_trans_date,
                            created_date
                        )
                        SELECT
                            ?,
                            p.match_key,
                            p.request_id,
                            p.partner_transaction_id,
                            NULL,
                            'PARTNER_ONLY',
                            'missing_internal_transaction',
                            p.partner_amount,
                            NULL,
                            p.partner_currency,
                            NULL,
                            p.partner_status,
                            NULL,
                            p.partner_trans_date,
                            NULL,
                            NOW()
                        FROM tmp_partner_reconciliation p
                        LEFT JOIN tmp_internal_reconciliation i ON i.match_key = p.match_key
                        WHERE i.match_key IS NULL
                        ORDER BY p.match_key
                        """,
                runId
        );
    }

    private void execute(java.sql.Connection connection, String sql) throws SQLException {
        try (java.sql.Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private int update(java.sql.Connection connection, String sql, Object... params) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            setParameters(statement, params);
            return statement.executeUpdate();
        }
    }

    private long queryForLong(java.sql.Connection connection, String sql, Object... params) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            setParameters(statement, params);
            try (java.sql.ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : 0;
            }
        }
    }

    private void setParameters(PreparedStatement statement, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            statement.setObject(i + 1, params[i]);
        }
    }

    private DataContainer mapRowToDataContainer(Row row, PartnerConfig config) throws Exception {
        String id       = getCellString(row, config.getColId() - 1);
        String trace    = getCellString(row, config.getColTrace() - 1);
        String amountStr = getCellString(row, config.getColAmount() - 1);
        String rawStatus = getCellString(row, config.getColStatus() - 1);
        String transDateStr = getCellString(row, config.getColTransDate() - 1);

        return buildDataContainer(config, id, trace, amountStr, rawStatus, transDateStr);
    }

    private DataContainer mapCsvLineToDataContainer(String line, PartnerConfig config) throws Exception {
        List<String> cols = parseCsvLine(line);
        String id = getCsvValue(cols, config.getColId() - 1);
        String trace = getCsvValue(cols, config.getColTrace() - 1);
        String amountStr = getCsvValue(cols, config.getColAmount() - 1);
        String rawStatus = getCsvValue(cols, config.getColStatus() - 1);
        String transDateStr = getCsvValue(cols, config.getColTransDate() - 1);

        return buildDataContainer(config, id, trace, amountStr, rawStatus, transDateStr);
    }

    private DataContainer buildDataContainer(
            PartnerConfig config,
            String id,
            String trace,
            String amountRaw,
            String rawStatus,
            String transDateStr
    ) throws Exception {
        String amountStr = amountRaw.replace(",", "").trim();
        log.debug("DEBUG amount raw: '{}'", amountStr);
        BigDecimal amount = new BigDecimal(amountStr);

        // Map status
        String status = config.getStatusMapping()
                .getOrDefault(rawStatus,
                        config.getStatusMapping().getOrDefault("others", "FAILED"));

        // Convert date → milliseconds
        LocalDateTime transDate = LocalDateTime.parse(transDateStr, DATE_FMT);
        long transDateMs = transDate
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
                .partnerTransactionId(id)
                .partnerTrace(trace)
                .partnerAmount(amount)
                .partnerCurrency(config.getCurrency())
                .partnerStatus(status)
                .partnerTransDate(transDate)
                .partnerData(objectMapper.writeValueAsString(partnerData))
                .build();
    }

    private String getCsvValue(List<String> values, int index) {
        if (index < 0 || index >= values.size()) {
            return "";
        }
        return values.get(index).trim();
    }

    private List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (ch == ',' && !inQuotes) {
                values.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }

        values.add(current.toString().trim());
        return values;
    }

    private boolean isCsvFile(String dataFilePath) {
        return dataFilePath != null && dataFilePath.toLowerCase().endsWith(".csv");
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

    private void loadPartnerRecords(List<DataContainer> containers, Map<String, PartnerRecord> records) {
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
    }

    private void addResultAndFlushIfNeeded(
            List<ReconciliationResult> results,
            ReconciliationResult result
    ) {
        results.add(result);
        if (results.size() >= BATCH_SIZE) {
            flushResults(results);
        }
    }

    private void flushResults(List<ReconciliationResult> results) {
        if (results.isEmpty()) {
            return;
        }

        long tFlush = System.currentTimeMillis();
        int size = results.size();
        jdbcTemplate.batchUpdate(
                INSERT_RECONCILIATION_RESULT_SQL,
                results,
                BATCH_SIZE,
                new ReconciliationResultBatchSetter()
        );
        log.info("BENCHMARK [3] jdbcBatchInsert reconciliationResult: {}ms | size={}",
                System.currentTimeMillis() - tFlush, size);
        results.clear();
    }

    private static class ReconciliationResultBatchSetter
            implements ParameterizedPreparedStatementSetter<ReconciliationResult> {

        @Override
        public void setValues(PreparedStatement ps, ReconciliationResult result) throws SQLException {
            setString(ps, 1, result.getRunId());
            setString(ps, 2, result.getMatchKey());
            setString(ps, 3, result.getPartnerRequestId());
            setString(ps, 4, result.getPartnerTransactionId());
            setString(ps, 5, result.getInternalTransactionId());
            setString(ps, 6, result.getResultStatus());
            setString(ps, 7, result.getDifferenceFields());
            setBigDecimal(ps, 8, result.getPartnerAmount());
            setBigDecimal(ps, 9, result.getInternalAmount());
            setString(ps, 10, result.getPartnerCurrency());
            setString(ps, 11, result.getInternalCurrency());
            setString(ps, 12, result.getPartnerStatus());
            setString(ps, 13, result.getInternalStatus());
            setLocalDateTime(ps, 14, result.getPartnerTransDate());
            setLocalDateTime(ps, 15, result.getInternalTransDate());
            setLocalDateTime(ps, 16, result.getCreatedDate());
        }

        private static void setString(PreparedStatement ps, int index, String value) throws SQLException {
            if (value == null) {
                ps.setNull(index, Types.VARCHAR);
                return;
            }

            ps.setString(index, value);
        }

        private static void setBigDecimal(PreparedStatement ps, int index, BigDecimal value) throws SQLException {
            if (value == null) {
                ps.setNull(index, Types.DECIMAL);
                return;
            }

            ps.setBigDecimal(index, value);
        }

        private static void setLocalDateTime(PreparedStatement ps, int index, LocalDateTime value) throws SQLException {
            if (value == null) {
                ps.setNull(index, Types.TIMESTAMP);
                return;
            }

            ps.setTimestamp(index, Timestamp.valueOf(value));
        }
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

    public record ImportSummary(
            int totalRead,
            int totalSaved,
            int totalError
    ) {
    }

    private record SqlReconciliationSummary(
            int totalPartnerRecords,
            int totalInternalRecords,
            int matchedCount,
            int mismatchedCount,
            int partnerOnlyCount,
            int internalOnlyCount
    ) {
    }

    private record ResultStatusCounts(
            int matched,
            int mismatched,
            int partnerOnly,
            int internalOnly
    ) {
    }

    private record PartnerTempLoad(
            int totalRecords,
            boolean filteredByDate
    ) {
    }

    private record ColumnDefinition(
            String dataType,
            String extra
    ) {
        private boolean isBigIntAutoIncrement() {
            return "bigint".equalsIgnoreCase(dataType)
                    && extra != null
                    && extra.toLowerCase().contains("auto_increment");
        }
    }
}
