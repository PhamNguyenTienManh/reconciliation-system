package com.example.partnerfilereader.runner;

import com.example.partnerfilereader.config.PartnerConfig;
import com.example.partnerfilereader.config.SftpProperties;
import com.example.partnerfilereader.model.ReconciliationRun;
import com.example.partnerfilereader.service.InternalTransactionSeedService;
import com.example.partnerfilereader.service.ReconciliationService;
import com.example.partnerfilereader.service.SftpDownloadService;
import com.example.partnerfilereader.service.TemplateReaderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Slf4j
@Component
@RequiredArgsConstructor
public class AppRunner implements ApplicationRunner {

    private final TemplateReaderService templateReaderService;
    private final ReconciliationService reconciliationService;
    private final InternalTransactionSeedService internalTransactionSeedService;
    private final SftpDownloadService sftpDownloadService;
    private final SftpProperties sftpProperties;

    @Value("${app.auto-run:false}")
    private boolean autoRun;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!autoRun) {
            log.info("App auto-run disabled. Use /reconciliation UI or REST APIs to run import, seed, and reconciliation.");
            return;
        }

        String templatePath = "input/RequestTemplate.xlsx";
        String localDataPath = "input/fake_partner_data_realistic.xlsx";
        String internalSeedPath = "input/fake_internal_data.csv";
        String reconciliationDate = "07/07/2024 00:00:00";

        log.info("Bắt đầu đọc config từ template...");
        PartnerConfig config = templateReaderService.readConfig(templatePath);
        log.info("Config đọc xong: identify={}, rowBegin={}",
                config.getIdentify(), config.getRowBegin());

        String dataPath = resolveDataPath(localDataPath);

        log.info("Bắt đầu xử lý file data...");
        log.info("DEBUG config: colId={}, colTrace={}, colAmount={}, colStatus={}, colTransDate={}",
                config.getColId(), config.getColTrace(), config.getColAmount(),
                config.getColStatus(), config.getColTransDate());
        reconciliationService.process(config, dataPath);

        log.info("Bắt đầu seed dữ liệu nội bộ...");
        internalTransactionSeedService.seedFromCsv(internalSeedPath);

        log.info("Bắt đầu chạy đối soát...");
        ReconciliationRun run = reconciliationService.reconcile(config.getIdentify(), reconciliationDate);
        log.info("Đối soát hoàn tất: runId={}, status={}, matched={}, mismatched={}, partnerOnly={}, internalOnly={}",
                run.getRunId(),
                run.getStatus(),
                run.getMatchedCount(),
                run.getMismatchedCount(),
                run.getPartnerOnlyCount(),
                run.getInternalOnlyCount());
    }

    private String resolveDataPath(String localDataPath) {
        if (!sftpProperties.isEnabled()) {
            log.info("SFTP disabled. Using local partner file: {}", localDataPath);
            return localDataPath;
        }

        log.info("SFTP enabled. Downloading partner file before ingestion...");
        try {
            Path downloadedFile = sftpDownloadService.downloadConfiguredFile();
            return downloadedFile.toString();
        } catch (Exception e) {
            log.warn("Cannot download partner file from SFTP. Falling back to local file: {}",
                    localDataPath, e);
            return localDataPath;
        }
    }
}
