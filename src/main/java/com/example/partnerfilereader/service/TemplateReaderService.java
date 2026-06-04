package com.example.partnerfilereader.service;

import com.example.partnerfilereader.config.PartnerConfig;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class TemplateReaderService {

    public PartnerConfig readConfig(String templatePath) throws IOException {
        try (FileInputStream fis = new FileInputStream(templatePath);
             Workbook wb = new XSSFWorkbook(fis)) {

            Sheet sheet = wb.getSheetAt(0);
            Row row = sheet.getRow(2); // dòng 3, index = 2

            PartnerConfig config = new PartnerConfig();

            config.setIdentify(getCellString(row.getCell(0)));           // cột 1
            config.setRowBegin(Integer.parseInt(
                    getCellString(row.getCell(4)).trim()));                   // cột 5
            config.setColId(parseColumnNumber(row.getCell(5)));          // cột 6
            config.setColTrace(parseColumnNumber(row.getCell(6)));       // cột 7
            config.setColAmount(parseColumnNumber(row.getCell(7)));      // cột 8
            config.setCurrency(getCellString(row.getCell(8)));           // cột 9
            config.setColStatus(parseColumnNumber(row.getCell(9)));      // cột 10
            config.setStatusMapping(parseStatusMapping(row.getCell(9))); // cột 10
            config.setColTransDate(parseColumnNumber(row.getCell(10)));  // cột 11
            config.setService(getCellString(row.getCell(11)));           // cột 12
            config.setPortal(getCellString(row.getCell(12)));            // cột 13
            config.setProvider(getCellString(row.getCell(13)));          // cột 14
            config.setMethod(getCellString(row.getCell(14)));            // cột 15
            config.setColMerchantSettle(parseColumnNumber(row.getCell(15))); // cột 16
            config.setColProviderSettle(parseColumnNumber(row.getCell(16))); // cột 17

            return config;
        }
    }

    // "column 1\nmsTransId" → 1
    private int parseColumnNumber(Cell cell) {
        String val = getCellString(cell);
        String firstLine = val.split("\n")[0];
        return Integer.parseInt(firstLine.replace("column", "").trim()) + 1;
    }

    // "column 17\nmsTrangThaiGd\nThành công: SUCCESS\nothers: FAILED"
    // → {"Thành công": "SUCCESS", "others": "FAILED"}
    private Map<String, String> parseStatusMapping(Cell cell) {
        Map<String, String> map = new LinkedHashMap<>();
        String[] lines = getCellString(cell).split("\n");
        for (int i = 2; i < lines.length; i++) {
            String[] parts = lines[i].split(":");
            if (parts.length == 2) {
                map.put(parts[0].trim(), parts[1].trim());
            }
        }
        return map;
    }

    private String getCellString(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> String.valueOf((long) cell.getNumericCellValue());
            default -> "";
        };
    }
}