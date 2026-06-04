# Đề Bài: Đọc File Đối Tác Và Lưu Vào DB

## 1. Mục Tiêu

Implement chức năng đọc file Excel đối tác theo file cấu hình, sau đó lưu dữ liệu vào DB theo cấu trúc `DataContainer` hiện tại.

Bài này chỉ làm phần **đọc file và lưu DB**. Chưa làm đối soát.

## 2. File Đầu Vào

Dev nhận 2 file:

| File | Ý nghĩa |
|---|---|
| `RequestTemplate.xlsx` | File cấu hình mapping |
| `m4becomvsp_07072024_combine.xlsx` | File dữ liệu giao dịch đối tác |

## 3. Yêu Cầu Xử Lý

Hệ thống cần:

1. Đọc file `RequestTemplate.xlsx`.
2. Lấy cấu hình trong phần `Partner Config`.
3. Dựa vào config để biết file data bắt đầu từ dòng nào và cột nào tương ứng với field nào.
4. Đọc file `m4becomvsp_07072024_combine.xlsx`.
5. Map từng dòng giao dịch thành `partnerData`.
6. Lưu từng giao dịch vào collection/entity `DataContainer`.

## 4. Mapping Tối Thiểu

Từ config, cần map được các field sau:

| Field trong DB | Lấy theo config |
|---|---|
| `partnerData._id` | `id` |
| `partnerData.trace` | `trace` |
| `partnerData.amount` | `amount` |
| `partnerData.currency` | `currency` |
| `partnerData.status` | `status` |
| `partnerData.transDate` | `transDate` |
| `partnerData.extra.service` | `extra.service` |
| `partnerData.extra.portal` | `extra.portal` |
| `partnerData.extra.provider` | `extra.provider` |
| `partnerData.extra.method` | `extra.method` |
| `partnerData.extra.merchantSettleDate` | `extra.merchantSettleDate` |
| `partnerData.extra.providerSettleDate` | `extra.providerSettleDate` |
| `partnerData.extra.vspTransId` | Theo `mapping more to extra` |

Ví dụ trong config:

```text
id = column 1 / msTransId
trace = column 10 / msMaHDon
amount = column 4 / msTotalAmount
currency = VND
status = column 17 / msTrangThaiGd
transDate = column 7 / msNgayHoanThanh
```

## 5. Chuẩn Hóa Dữ Liệu

Trước khi lưu DB:

| Field | Cách xử lý |
|---|---|
| `amount` | Convert sang decimal/BigDecimal, không dùng float/double |
| `status` | Map theo rule trong config |
| `transDate` | Convert sang date |
| Dòng trống | Bỏ qua |
| Dòng không phải giao dịch | Bỏ qua |

Lưu ý với `amount`: cần giữ chính xác giá trị tiền. Nếu DB/model hỗ trợ kiểu decimal như `BigDecimal`/`Decimal128` thì dùng trực tiếp; nếu `partnerData` đang lưu dạng JSON string thì serialize theo format hiện tại nhưng phần parse/validate trong code vẫn phải dùng decimal.

Ví dụ status mapping:

```text
Thành công: SUCCESS
others: FAILED
```

## 6. Format Lưu DB

Giữ nguyên cấu trúc `DataContainer`.

Các field chưa có dữ liệu ở bài này thì để empty string `""`.

```json
{
  "requestId": "uuid",
  "identify": "MOMO",
  "reconciliationDate": "07/07/2024 00:00:00",
  "operationStatus": "IN_PROGRESS",
  "reconciliationStatus": "",
  "workflowType": "UPC",
  "connectorData": "",
  "extraData": "",
  "createdBy": "system",
  "createdDate": "current datetime",
  "lastModifiedBy": "system",
  "lastModifiedDate": "current datetime",
  "partnerData": "{...}"
}
```

`partnerData` là JSON string:

```json
{
  "trace": "2407055711887385978413624",
  "_id": "61838642196",
  "status": "SUCCESS",
  "amount": 259200,
  "currency": "VND",
  "transDate": {
    "$date": {
      "$numberLong": "1720112741000"
    }
  },
  "extra": {
    "vspTransId": "2407055711887385978413624",
    "service": "PAYMENT",
    "portal": "PaymentGateway",
    "provider": "MOMO",
    "method": "MOMO",
    "merchantSettleDate": {
      "$date": {
        "$numberLong": "1720112741000"
      }
    },
    "providerSettleDate": {
      "$date": {
        "$numberLong": "1720112741000"
      }
    }
  }
}
```

## 7. Lưu Ý

- Không hard-code cột trong code.
- Không bắt buộc runtime phải đọc trực tiếp từ `RequestTemplate.xlsx`.
- Dev có thể parse template để sinh config, hoặc cấu hình mapping bằng DB/YAML/env/config service.
- Yêu cầu chính là mapping phải cấu hình động được theo nội dung template và job chạy theo config đó.
- `identify`, `provider`, `method` lấy theo config, không tự đổi tên.
- Chưa xử lý connector ở bài này nên `connectorData = ""`.
- Chưa đối soát nên `reconciliationStatus = ""`.
- Nếu config thiếu field bắt buộc thì báo lỗi rõ ràng.

## 8. Kết Quả Mong Muốn

Dev hoàn thành khi:

- Cấu hình được job theo yêu cầu mapping trong file `RequestTemplate.xlsx`.
- Đọc được file data đối tác.
- Map được dữ liệu sang `partnerData`.
- Lưu được vào DB theo cấu trúc `DataContainer`.
- Log được số dòng đọc được, số dòng lưu thành công và số dòng lỗi.
