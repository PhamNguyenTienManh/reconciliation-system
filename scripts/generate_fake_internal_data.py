import argparse
import csv
import re
from datetime import datetime, timedelta
from pathlib import Path
from zipfile import ZipFile
import xml.etree.ElementTree as ET


MATCHED_TOTAL = 99_000
MISMATCHED_TOTAL = 300
PARTNER_ONLY_TOTAL = 300
INTERNAL_ONLY_TOTAL = 400
SHARED_TOTAL = MATCHED_TOTAL + MISMATCHED_TOTAL
PARTNER_TOTAL = SHARED_TOTAL + PARTNER_ONLY_TOTAL
INTERNAL_TOTAL = SHARED_TOTAL + INTERNAL_ONLY_TOTAL

DATE_PATTERN = re.compile(r"^\d{2}/\d{2}/\d{4} \d{2}:\d{2}:\d{2}$")
XLSX_NS = {"a": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}


def cell_text(value):
    if value is None:
        return ""
    if isinstance(value, float) and value.is_integer():
        return str(int(value))
    return str(value).strip()


def read_csv_sample(csv_path):
    with csv_path.open("r", encoding="utf-8-sig", newline="") as file:
        reader = csv.reader(file)
        header = next(reader)
        rows = [row for row in reader if row]

    if not rows:
        raise ValueError(f"CSV sample has no data rows: {csv_path}")

    return header, rows


def read_first_excel_data_values(xlsx_path, csv_row):
    with ZipFile(xlsx_path) as archive:
        shared_strings = []
        if "xl/sharedStrings.xml" in archive.namelist():
            root = ET.fromstring(archive.read("xl/sharedStrings.xml"))
            for item in root.findall("a:si", XLSX_NS):
                shared_strings.append("".join(text.text or "" for text in item.findall(".//a:t", XLSX_NS)))

        sheet = ET.fromstring(archive.read("xl/worksheets/sheet1.xml"))

    def value_of(cell):
        value = cell.find("a:v", XLSX_NS)
        raw = "" if value is None else value.text
        if cell.attrib.get("t") == "s" and raw:
            return shared_strings[int(raw)]
        if cell.attrib.get("t") == "inlineStr":
            return "".join(text.text or "" for text in cell.findall(".//a:t", XLSX_NS))
        return raw

    csv_values = {value.strip() for value in csv_row if value.strip()}
    best_values = []
    best_score = 0
    for row in sheet.findall("a:sheetData/a:row", XLSX_NS):
        values = [cell_text(value_of(cell)) for cell in row.findall("a:c", XLSX_NS)]
        score = len(csv_values & set(values))
        if score > best_score:
            best_values = values
            best_score = score

    if best_score < 3:
        raise ValueError("Cannot infer sample data from Excel and CSV.")

    return best_values


def infer_csv_columns(csv_row, excel_values):
    date_index = next(
        (index for index, value in enumerate(csv_row) if DATE_PATTERN.match(value.strip())),
        None,
    )
    if date_index is None:
        raise ValueError("Cannot infer CSV date column.")

    amount_index = None
    for index, value in enumerate(csv_row):
        normalized = value.strip().replace(",", "")
        if normalized.isdigit() and excel_values.count(normalized) >= 2:
            amount_index = index
            break
    if amount_index is None:
        raise ValueError("Cannot infer CSV amount column.")

    matched_numeric = [
        (index, value.strip())
        for index, value in enumerate(csv_row)
        if value.strip().isdigit()
        and value.strip() in excel_values
        and index != amount_index
    ]
    if len(matched_numeric) < 2:
        raise ValueError("Cannot infer CSV transaction id and trace columns.")

    matched_numeric.sort(key=lambda item: len(item[1]))
    transaction_id_index = matched_numeric[0][0]
    trace_index = matched_numeric[-1][0]

    status_index = next(
        (
            index
            for index, value in enumerate(csv_row)
            if value.strip().upper() in {"SUCCESS", "FAILED"}
        ),
        None,
    )
    if status_index is None:
        raise ValueError("Cannot infer CSV status column.")

    return {
        "transaction_id": transaction_id_index,
        "trace": trace_index,
        "amount": amount_index,
        "status": status_index,
        "date": date_index,
    }, datetime.strptime(csv_row[date_index].strip(), "%d/%m/%Y %H:%M:%S")


def fake_trace(index, prefix):
    return f"{prefix}{index:019d}"


def fake_transaction_id(index, prefix):
    return f"{prefix}{index:08d}"


def fake_amount(index):
    return 10_000 + (index % 9_000) * 100


def fake_date(base_date, index):
    return (base_date + timedelta(seconds=index)).strftime("%d/%m/%Y %H:%M:%S")


def internal_record(index, base_date):
    if index <= SHARED_TOTAL:
        sequence = index
        trace = fake_trace(sequence, "90")
        amount = fake_amount(sequence)
        if index <= MISMATCHED_TOTAL:
            amount += 100
    else:
        sequence = index - SHARED_TOTAL
        trace = fake_trace(sequence, "92")
        amount = fake_amount(sequence)

    return {
        "transaction_id": fake_transaction_id(index, "80"),
        "trace": trace,
        "amount": amount,
        "status": "SUCCESS",
        "trans_date": fake_date(base_date, sequence),
    }


def print_stats(output_path):
    print(f"Generated internal file: {output_path}")
    print(f"Partner records: {PARTNER_TOTAL:,}")
    print(f"Internal records: {INTERNAL_TOTAL:,}")
    print("Expected reconciliation cases:")
    print(f"  MATCHED: {MATCHED_TOTAL:,}")
    print(f"  MISMATCHED: {MISMATCHED_TOTAL:,}")
    print(f"  PARTNER_ONLY: {PARTNER_ONLY_TOTAL:,}")
    print(f"  INTERNAL_ONLY: {INTERNAL_ONLY_TOTAL:,}")


def main():
    parser = argparse.ArgumentParser(description="Generate large fake internal CSV data.")
    parser.add_argument("--sample-xlsx", default="input/m4becomvsp_07072024_combine.xlsx")
    parser.add_argument("--sample-csv", default="input/internal_transactions_seed.csv")
    parser.add_argument("--output", default="input/fake_internal_data.csv")
    args = parser.parse_args()

    sample_xlsx = Path(args.sample_xlsx)
    sample_csv = Path(args.sample_csv)
    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)

    header, csv_rows = read_csv_sample(sample_csv)
    excel_values = read_first_excel_data_values(sample_xlsx, csv_rows[0])
    columns, base_date = infer_csv_columns(csv_rows[0], excel_values)
    template_row = list(csv_rows[0])

    with output_path.open("w", encoding="utf-8", newline="") as file:
        writer = csv.writer(file)
        writer.writerow(header)

        for index in range(1, INTERNAL_TOTAL + 1):
            row = list(template_row)
            record = internal_record(index, base_date)
            row[columns["transaction_id"]] = record["transaction_id"]
            row[columns["trace"]] = record["trace"]
            row[columns["amount"]] = str(record["amount"])
            row[columns["status"]] = record["status"]
            row[columns["date"]] = record["trans_date"]
            writer.writerow(row)

    print_stats(output_path)


if __name__ == "__main__":
    main()
