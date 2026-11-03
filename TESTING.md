# Acceptance checklist

These manual checks have not been run in the creation environment because Maven was unavailable.

| Check | Steps | Expected result |
|---|---|---|
| Start application | Run `mvn spring-boot:run`; open `http://localhost:8080` | Dashboard renders and H2 is created |
| Standard CSV | Upload `dataset/csv/Invoices.csv` | Rows load, duplicates are removed, and the result summary appears |
| Alternate headings | Upload `WA_Fn-UseC_-Accounts-Receivable3.csv` | Invoice number, customer ID, invoice date, and amount are inferred; currency is unspecified; disputed rows are marked for review |
| Unknown headings | Rename required column headers, upload, then select their meanings on the mapping page | Dataset imports with the selected mapping |
| Duplicate removal | Upload two same-vendor rows with differently formatted versions of the same invoice number | First record is retained; later copies are omitted and listed in `Removed Duplicates` |
| Safe invoices | Upload rows that score 0–20 and are not disputed | Rows appear in `Final Cleaned Invoices` and are auto-cleared |
| Risk hold | Upload a disputed row or a row scoring above 20 | Row appears in `Review Required`, not in `Final Cleaned Invoices` |
| Bad row handling | Include a row missing its amount or with an unreadable date | Valid rows continue; bad row is listed in `Rejected Rows` |
| Excel formats | Upload `.xls` and `.xlsx` workbooks | Workbook rows import; an `Invoices` sheet is preferred when present |
| Export workbook | Download the cleaned Excel workbook after import | Workbook contains summary, cleaned, review, duplicate, and rejected-row sheets |

The system supports auditor prioritization. A low risk score is not proof of legitimacy, and a flagged record is not proof of fraud.
