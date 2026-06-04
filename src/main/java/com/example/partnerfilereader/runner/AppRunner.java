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

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String templatePath = "input/RequestTemplate.xlsx";
        String localDataPath = "input/m4becomvsp_07072024_combine.xlsx";
        String internalSeedPath = "input/internal_transactions_seed.csv";
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
        Path downloadedFile = sftpDownloadService.downloadConfiguredFile();
        return downloadedFile.toString();
    }
}
