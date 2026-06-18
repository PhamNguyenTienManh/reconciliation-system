package com.example.partnerfilereader.service;

import com.example.partnerfilereader.model.InternalTransaction;
import com.example.partnerfilereader.repository.InternalTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class InternalTransactionSeedService {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    private static final int BATCH_SIZE = 10000;

    private final InternalTransactionRepository repository;

    public SeedSummary seedFromCsv(String csvPath) throws IOException {
        int totalRead = 0;
        int totalSaved = 0;
        int totalError = 0;
        List<InternalTransaction> transactions = new ArrayList<>();

        try (BufferedReader reader = Files.newBufferedReader(Path.of(csvPath), StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (header == null) {
                log.warn("Internal transaction seed file is empty: {}", csvPath);
                return new SeedSummary(totalRead, totalSaved, totalError);
            }

            String line;
            int lineNo = 1;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) {
                    continue;
                }

                totalRead++;
                try {
                    InternalTransaction transaction = mapCsvLine(line);
                    transactions.add(transaction);
                    totalSaved++;
                    if (transactions.size() >= BATCH_SIZE) {
                        repository.saveAll(transactions);
                        transactions.clear();
                    }
                } catch (Exception e) {
                    totalError++;
                    log.error("Failed to seed internal transaction at line {}: {}", lineNo, e.getMessage(), e);
                }
            }

            if (!transactions.isEmpty()) {
                repository.saveAll(transactions);
                transactions.clear();
            }
        }

        log.info("Seed internal transactions: read={} | saved={} | failed={}",
                totalRead, totalSaved, totalError);
        return new SeedSummary(totalRead, totalSaved, totalError);
    }

    private InternalTransaction mapCsvLine(String line) {
        List<String> cols = parseCsvLine(line);
        if (cols.size() < 10) {
            throw new IllegalArgumentException("CSV line must have at least 10 columns");
        }

        LocalDateTime now = LocalDateTime.now();
        return InternalTransaction.builder()
                .transactionId(required(cols.get(0), "transactionId"))
                .trace(required(cols.get(1), "trace"))
                .amount(new BigDecimal(required(cols.get(2), "amount").replace(",", "")))
                .currency(required(cols.get(3), "currency"))
                .status(required(cols.get(4), "status"))
                .transDate(LocalDateTime.parse(required(cols.get(5), "transDate"), DATE_FMT))
                .service(cols.get(6).trim())
                .portal(cols.get(7).trim())
                .provider(required(cols.get(8), "provider"))
                .method(cols.get(9).trim())
                .createdBy("seed")
                .createdDate(now)
                .lastModifiedBy("seed")
                .lastModifiedDate(now)
                .build();
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

    private String required(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing " + fieldName);
        }
        return value.trim();
    }

    public record SeedSummary(
            int totalRead,
            int totalSaved,
            int totalError
    ) {
    }
}
