package com.example.partnerfilereader.controller;

import com.example.partnerfilereader.model.ReconciliationResult;
import com.example.partnerfilereader.model.ReconciliationRun;
import com.example.partnerfilereader.repository.DataContainerRepository;
import com.example.partnerfilereader.repository.InternalTransactionRepository;
import com.example.partnerfilereader.repository.ReconciliationResultRepository;
import com.example.partnerfilereader.repository.ReconciliationRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Controller
@RequiredArgsConstructor
public class DemoController {

    private final DataContainerRepository dataContainerRepository;
    private final InternalTransactionRepository internalTransactionRepository;
    private final ReconciliationRunRepository reconciliationRunRepository;
    private final ReconciliationResultRepository reconciliationResultRepository;

    @GetMapping("/")
    public String home() {
        return "redirect:/reconciliation";
    }

    @GetMapping("/dashboard")
    public String dashboard() {
        return "redirect:/reconciliation";
    }

    @GetMapping("/sftp")
    public String sftp() {
        return "redirect:/reconciliation";
    }

    @GetMapping("/data-containers")
    public String dataContainers() {
        return "redirect:/reconciliation";
    }

    @GetMapping("/reconciliation-results")
    public String reconciliationResults() {
        return "redirect:/reconciliation";
    }

    @GetMapping("/reconciliation")
    public String reconciliation(
            @RequestParam(required = false) String runId,
            Model model
    ) {
        Optional<ReconciliationRun> latestRun = reconciliationRunRepository.findTopByOrderByStartedAtDesc();
        String selectedRunId = runId;
        if ((selectedRunId == null || selectedRunId.isBlank()) && latestRun.isPresent()) {
            selectedRunId = latestRun.get().getRunId();
        }

        long totalPartnerRecords = latestRun
                .map(run -> (long) run.getTotalPartnerRecords())
                .orElseGet(dataContainerRepository::count);
        long totalInternalRecords = latestRun
                .map(run -> (long) run.getTotalInternalRecords())
                .orElseGet(internalTransactionRepository::count);
        long matched = latestRun.map(run -> (long) run.getMatchedCount()).orElse(0L);
        long missingInternal = latestRun.map(run -> (long) run.getPartnerOnlyCount()).orElse(0L);
        long missingPartner = latestRun.map(run -> (long) run.getInternalOnlyCount()).orElse(0L);
        long amountMismatch = latestRun
                .map(run -> reconciliationResultRepository
                        .countByRunIdAndResultStatusAndDifferenceFieldsContaining(
                                run.getRunId(),
                                "MISMATCHED",
                                "amount"
                        ))
                .orElse(0L);

        List<ReconciliationResult> results = selectedRunId == null || selectedRunId.isBlank()
                ? reconciliationResultRepository.findTop100ByOrderByCreatedDateDesc()
                : reconciliationResultRepository.findByRunIdOrderByCreatedDateDesc(selectedRunId);

        model.addAttribute("latestRun", latestRun.orElse(null));
        model.addAttribute("selectedRunId", selectedRunId);
        model.addAttribute("rows", results.stream().map(this::toView).toList());
        model.addAttribute("stats", Map.of(
                "totalPartnerRecords", totalPartnerRecords,
                "totalInternalRecords", totalInternalRecords,
                "matched", matched,
                "missingInternal", missingInternal,
                "missingPartner", missingPartner,
                "amountMismatch", amountMismatch
        ));
        return "reconciliation";
    }

    private ReconciliationRowView toView(ReconciliationResult result) {
        List<String> differences = splitDifferences(result.getDifferenceFields());
        boolean hasPartner = result.getPartnerTransactionId() != null
                && !result.getPartnerTransactionId().isBlank();
        boolean hasInternal = result.getInternalTransactionId() != null
                && !result.getInternalTransactionId().isBlank();

        return new ReconciliationRowView(
                result.getResultId(),
                result.getMatchKey(),
                result.getResultStatus(),
                statusLabel(result.getResultStatus()),
                statusClass(result.getResultStatus()),
                explanation(result.getResultStatus(), differences),
                hasPartner,
                hasInternal,
                result.getPartnerTransactionId(),
                result.getInternalTransactionId(),
                result.getPartnerAmount(),
                result.getInternalAmount(),
                result.getPartnerCurrency(),
                result.getInternalCurrency(),
                result.getPartnerStatus(),
                result.getInternalStatus(),
                result.getPartnerTransDate(),
                result.getInternalTransDate(),
                differences,
                differences.isEmpty() ? "-" : String.join(", ", differences),
                differences.contains("amount"),
                differences.contains("currency"),
                differences.contains("status"),
                differences.contains("transDate")
        );
    }

    private List<String> splitDifferences(String differenceFields) {
        if (differenceFields == null || differenceFields.isBlank()) {
            return List.of();
        }

        return Arrays.stream(differenceFields.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    private String statusLabel(String status) {
        if (status == null) {
            return "Chưa xác định";
        }

        return switch (status) {
            case "MATCHED" -> "Khớp";
            case "MISMATCHED" -> "Lệch dữ liệu";
            case "PARTNER_ONLY" -> "Thiếu bên nội bộ";
            case "INTERNAL_ONLY" -> "Thiếu bên đối tác";
            default -> status;
        };
    }

    private String statusClass(String status) {
        if (status == null) {
            return "status-unknown";
        }

        return switch (status) {
            case "MATCHED" -> "status-matched";
            case "MISMATCHED" -> "status-mismatched";
            case "PARTNER_ONLY" -> "status-partner-only";
            case "INTERNAL_ONLY" -> "status-internal-only";
            default -> "status-unknown";
        };
    }

    private String explanation(String status, List<String> differences) {
        if (status == null) {
            return "Chưa xác định.";
        }

        return switch (status) {
            case "MATCHED" -> "Hai bên có cùng giao dịch và các field chính đều khớp.";
            case "MISMATCHED" -> "Hai bên có cùng giao dịch nhưng lệch: " + String.join(", ", differences) + ".";
            case "PARTNER_ONLY" -> "File đối tác có giao dịch này nhưng hệ thống nội bộ không tìm thấy.";
            case "INTERNAL_ONLY" -> "Hệ thống nội bộ có giao dịch này nhưng file đối tác không có.";
            default -> "Chưa xác định.";
        };
    }

    public record ReconciliationRowView(
            String resultId,
            String matchKey,
            String resultStatus,
            String statusLabel,
            String statusClass,
            String explanation,
            boolean hasPartner,
            boolean hasInternal,
            String partnerTransactionId,
            String internalTransactionId,
            BigDecimal partnerAmount,
            BigDecimal internalAmount,
            String partnerCurrency,
            String internalCurrency,
            String partnerStatus,
            String internalStatus,
            LocalDateTime partnerTransDate,
            LocalDateTime internalTransDate,
            List<String> differences,
            String differencesText,
            boolean amountDifferent,
            boolean currencyDifferent,
            boolean statusDifferent,
            boolean transDateDifferent
    ) {
    }
}
