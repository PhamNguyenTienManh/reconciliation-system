package com.example.partnerfilereader.config;

import lombok.Data;
import java.util.Map;

@Data
public class PartnerConfig {
    private String identify;           // "MOMO"
    private int rowBegin;              // 7
    private int colId;                 // 1
    private int colTrace;              // 10
    private int colAmount;             // 4
    private String currency;           // "VND"
    private int colStatus;             // 17
    private Map<String, String> statusMapping; // {"Thành công" -> "SUCCESS", "others" -> "FAILED"}
    private int colTransDate;          // 7
    private String service;            // "PAYMENT"
    private String portal;             // "PaymentGateway"
    private String provider;           // "MOMO"
    private String method;             // "MOMO"
    private int colMerchantSettle;     // 7
    private int colProviderSettle;     // 7
}