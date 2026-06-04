                    ┌──────────────────────┐
                    │  Partner (MOMO)      │
                    │  Xuất file Excel     │
                    └──────────┬───────────┘
                               │
                               ▼
                    ┌──────────────────────┐
                    │      SFTP Server     │
                    │ /upload/*.xlsx       │
                    └──────────┬───────────┘
                               │
                               │ Download
                               ▼
        ┌─────────────────────────────────────────────────────┐
        │                Spring Boot Application              │
        ├─────────────────────────────────────────────────────┤
        │                                                     │
        │ 1. Đọc RequestTemplate.xlsx                         │
        │    ↓                                                │
        │    Sinh PartnerConfig                               │
        │                                                     │
        │ 2. Download file từ SFTP                            │
        │    ↓                                                │
        │    m4becomvsp_07072024_combine.xlsx                 │
        │                                                     │
        │ 3. Parse Excel                                      │
        │    ↓                                                │
        │    Map dữ liệu theo PartnerConfig                   │
        │                                                     │
        │ 4. Lưu xuống Mysql                                  │
        │    Collection: DataContainer                        │
        │                                                     │
        └───────────────────────┬─────────────────────────────┘
                                │
                                │
                                ▼
                 ┌───────────────────────────────┐
                 │ Internal Transactions         │
                 │ (Seed dữ liệu nội bộ)         │
                 └──────────────┬────────────────┘
                                │
                                │ Compare
                                ▼
                 ┌───────────────────────────────┐
                 │ Reconciliation Service        │
                 ├───────────────────────────────┤
                 │ MATCHED                       │
                 │ MISSING_INTERNAL              │
                 │ MISSING_PARTNER               │
                 │ AMOUNT_MISMATCH               │
                 └──────────────┬────────────────┘
                                │
                                ▼
                 ┌───────────────────────────────┐
                 │ ReconciliationResults         │
                 └──────────────┬────────────────┘
                                │
                                ▼
                 ┌───────────────────────────────┐
                 │ Dashboard (Thymeleaf)         │
                 ├───────────────────────────────┤
                 │ Total Records                 │
                 │ Matched                       │
                 │ Missing Internal              │
                 │ Missing Partner               │
                 │ Amount Mismatch               │
                 └───────────────────────────────┘