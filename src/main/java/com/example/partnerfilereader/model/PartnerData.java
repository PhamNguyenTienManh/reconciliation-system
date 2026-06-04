package com.example.partnerfilereader.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PartnerData {
    private String _id;
    private String trace;
    private java.math.BigDecimal amount;
    private String currency;
    private String status;
    private long transDate;
    private Extra extra;

    @Data
    @Builder
    public static class Extra {
        private String vspTransId;
        private String service;
        private String portal;
        private String provider;
        private String method;
        private long merchantSettleDate;
        private long providerSettleDate;
    }
}