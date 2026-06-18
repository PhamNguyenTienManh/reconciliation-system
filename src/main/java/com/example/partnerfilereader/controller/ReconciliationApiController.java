package com.example.partnerfilereader.controller;

import com.example.partnerfilereader.config.PartnerConfig;
import com.example.partnerfilereader.model.ReconciliationRun;
import com.example.partnerfilereader.repository.ReconciliationRunRepository;
import com.example.partnerfilereader.service.InternalTransactionSeedService;
import com.example.partnerfilereader.service.ReconciliationService;
import com.example.partnerfilereader.service.TemplateReaderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class ReconciliationApiController {

    private static final String TEMPLATE_PATH = "input/RequestTemplate.xlsx";

    private final TemplateReaderService templateReaderService;
    private final ReconciliationService reconciliationService;
    private final InternalTransactionSeedService internalTransactionSeedService;
    private final ReconciliationRunRepository reconciliationRunRepository;

    @PostMapping("/reconciliation/import")
    public ResponseEntity<ImportResponse> importPartnerFile(
            @RequestParam("file") MultipartFile file
    ) throws Exception {
        validateFile(file, "partner file");

        Path tempFile = copyToTempFile(file, fileSuffix(file));
        try {
            PartnerConfig config = templateReaderService.readConfig(TEMPLATE_PATH);
            ReconciliationService.ImportSummary summary = reconciliationService.process(
                    config,
                    tempFile.toString()
            );

            return ResponseEntity.ok(new ImportResponse(
                    "Import thành công " + summary.totalSaved() + " record",
                    summary.totalRead(),
                    summary.totalSaved(),
                    summary.totalError()
            ));
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @PostMapping("/internal/seed")
    public ResponseEntity<SeedResponse> seedInternalTransactions(
            @RequestParam("file") MultipartFile file
    ) throws Exception {
        validateFile(file, "internal CSV file");

        Path tempFile = copyToTempFile(file, ".csv");
        try {
            InternalTransactionSeedService.SeedSummary summary =
                    internalTransactionSeedService.seedFromCsv(tempFile.toString());

            return ResponseEntity.ok(new SeedResponse(
                    "Seed thành công " + summary.totalSaved() + " record",
                    summary.totalRead(),
                    summary.totalSaved(),
                    summary.totalError()
            ));
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @PostMapping("/reconciliation/run")
    public ResponseEntity<RunResponse> runReconciliation(
            @RequestBody RunRequest request
    ) {
        String identify = required(request.identify(), "identify");
        String reconciliationDate = normalizeReconciliationDate(
                required(request.reconciliationDate(), "reconciliationDate")
        );

        ReconciliationRun run = reconciliationService.reconcile(identify, reconciliationDate);
        return ResponseEntity.ok(new RunResponse(
                "Đối soát hoàn tất",
                run.getRunId(),
                run.getStatus()
        ));
    }

    @GetMapping("/reconciliation/runs/{runId}")
    public ResponseEntity<RunSummaryResponse> getRunSummary(
            @PathVariable String runId
    ) {
        ReconciliationRun run = reconciliationRunRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));

        return ResponseEntity.ok(new RunSummaryResponse(
                run.getRunId(),
                run.getIdentify(),
                run.getReconciliationDate(),
                run.getStatus(),
                run.getMatchedCount(),
                run.getMismatchedCount(),
                run.getPartnerOnlyCount(),
                run.getInternalOnlyCount(),
                run.getTotalPartnerRecords(),
                run.getTotalInternalRecords(),
                run.getStartedAt() == null ? null : run.getStartedAt().toString(),
                run.getFinishedAt() == null ? null : run.getFinishedAt().toString(),
                run.getErrorMessage()
        ));
    }

    private void validateFile(MultipartFile file, String label) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Missing " + label);
        }
    }

    private Path copyToTempFile(MultipartFile file, String suffix) throws IOException {
        Path tempFile = Files.createTempFile("partner-file-reader-", suffix);
        file.transferTo(tempFile);
        return tempFile;
    }

    private String fileSuffix(MultipartFile file) {
        String filename = file.getOriginalFilename();
        if (filename == null) {
            return ".tmp";
        }

        String lowerFilename = filename.toLowerCase();
        if (lowerFilename.endsWith(".csv")) {
            return ".csv";
        }
        if (lowerFilename.endsWith(".xlsx")) {
            return ".xlsx";
        }

        throw new IllegalArgumentException("Only .xlsx and .csv files are supported");
    }

    private String required(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing " + fieldName);
        }
        return value.trim();
    }

    private String normalizeReconciliationDate(String value) {
        if (value.matches("\\d{2}/\\d{2}/\\d{4}")) {
            return value + " 00:00:00";
        }
        return value;
    }

    public record ImportResponse(
            String message,
            int totalRead,
            int totalSaved,
            int totalError
    ) {
    }

    public record SeedResponse(
            String message,
            int totalRead,
            int totalSaved,
            int totalError
    ) {
    }

    public record RunRequest(
            String identify,
            String reconciliationDate
    ) {
    }

    public record RunResponse(
            String message,
            String runId,
            String status
    ) {
    }

    public record RunSummaryResponse(
            String runId,
            String identify,
            String reconciliationDate,
            String status,
            int matched,
            int mismatched,
            int partnerOnly,
            int internalOnly,
            int totalPartnerRecords,
            int totalInternalRecords,
            String startedAt,
            String finishedAt,
            String errorMessage
    ) {
    }
}
