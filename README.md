# Smart Invoice Fraud Detection and Risk Assessment

A Java and Spring Boot web application for cleaning invoice datasets and prioritizing records for auditor review. It accepts CSV and Excel files, maps common or unfamiliar column names, removes repeated invoice identities, runs explainable validation rules, and creates a multi-sheet Excel report.

> **Risk scores are review signals, not fraud probabilities.** A score of zero means that none of the configured checks raised an alert; it does not prove that an invoice is legitimate.

## Features

- CSV, XLS, and XLSX upload
- Automatic recognition of common invoice column names, with manual mapping for unfamiliar datasets
- Original source columns preserved in the cleaned Excel sheet
- Duplicate detection using normalized vendor and invoice number
- Row-level validation with rejected rows and reasons recorded
- Explainable risk scores for GST, invoice totals, vendor amount outliers, dates, GSTIN format, disputes, and optional PO/GRN matching
- Cleaned dataset restricted to non-disputed invoices with a risk score of exactly zero
- Dashboard search, risk-level filters, and pagination (50 records per page)
- Auditor decisions and audit log
- Excel and CSV report downloads

## Technology

- Java 17+
- Spring Boot 3.3.5, Spring MVC, Spring Data JPA
- Thymeleaf
- H2 by default; optional MySQL profile
- Apache Commons CSV
- Apache POI
- Maven

## Requirements

Install a JDK 17 or later and Maven 3.8 or later. Verify they are available:

```bat
java -version
mvn -version
```

## Run locally

Open a terminal in the project root (the folder containing `pom.xml`) and run:

```bat
mvn spring-boot:run
```

Wait for the Spring Boot startup message, then open <http://localhost:8080>.

Keep the terminal running while using the app. Press **Ctrl+C** in that terminal to stop it. The default H2 database is a local file; do not start a second application instance against the same database at the same time, or H2 may report that the file is locked.

## Import a dataset

1. Select **Import invoices** and choose a `.csv`, `.xls`, or `.xlsx` file. The configured upload limit is 10 MB.
2. For Excel files, the app reads a worksheet named `Invoices` if present; otherwise it reads the first worksheet.
3. The importer detects common aliases. If required fields cannot be recognized, use the mapping page to select:
   - Invoice number
   - Vendor or customer
   - Invoice date
   - Total amount
4. Map optional fields when available to enable their checks: taxable amount, GST amount/rate, GSTIN, quantity, currency, payment status, settled date, dispute status, PO details, and GRN details.
5. Select **Import, clean and assess**. Valid unique records appear on the dashboard. Invalid records are kept in the report with reasons.

The importer supports ISO dates, common slash and hyphen formats, and compact `yyyyMMdd` dates such as `20200125`. For example, a common Accounts Receivable dataset can map `doc_id` to invoice number, `cust_number` to vendor, `document_create_date` to invoice date, and `total_open_amount` to total amount.

Unknown source columns are preserved in `Cleaned Dataset`, but only fields mapped to known invoice concepts are assessed by the risk engine. Missing optional tax fields receive neutral defaults, so GST checks are not meaningful unless the needed tax columns are supplied and mapped.

## Duplicate and row handling

A duplicate key is the normalized combination of vendor and invoice number. Normalization ignores case, spaces, and punctuation. The first occurrence is kept; later occurrences are listed in `Removed Duplicates`. Similar-looking but non-identical invoice identities are not fuzzy-matched. Duplicate detection is performed within the uploaded file, not across all historical uploads.

Rows with missing required fields, unrecognized dates, or invalid amounts are excluded from invoice assessment and listed on `Rejected Rows` with the source row number and rejection reason. Other valid rows continue to process.

## Risk rules

The rule-based engine combines applicable checks and caps the result at 100:

- GST amount differs from the stated rate
- Invoice total differs from taxable amount plus GST
- Vendor amount is more than three times the average of the vendor's other invoices in the current upload (when enough records exist)
- Invoice date falls on a weekend or in the future
- Invoice total is zero or negative
- GST rate is outside 0–100%
- Large round-number invoice amount
- Invalid GSTIN format (format only; no live registration lookup)
- Disputed invoice
- Optional mismatch between invoice, purchase order, and goods receipt quantity or amount

Risk bands are **Low: 0–20**, **Medium: 21–50**, and **High: 51–100**. For the cleaned export, the rule is stricter: only score-zero, non-disputed invoices are included. All non-zero scores remain available for review, including non-zero Low scores.

## Dashboard and review

The dashboard shows counts for the latest analyzed upload. Search matches invoice number, vendor, or risk reason. The risk filter selects Low, Medium, or High records. Results are paginated at 50 records per page. Applying a dashboard filter does not change the report; the download contains the full latest upload.

Review decisions are **Pending**, **Investigating**, **Cleared**, or **Escalated**. Decisions are saved and recorded in the audit log. The audit page displays recent actions.

## Excel and CSV output

The **Download cleaned Excel** button remains available after analysis, including after applying dashboard filters. The workbook opens on `Cleaned Dataset`, which preserves the original uploaded columns and appends audit risk score, level, and reason columns. Every row in that sheet has score zero.

The workbook includes:

| Sheet | Contents |
|---|---|
| `Cleaned Dataset` | Original source columns for score-zero, non-duplicate invoices, plus audit risk fields |
| `Import Summary` | Upload counts and the cleaning rule |
| `Final Cleaned Invoices` | Score-zero invoices in the app's standard invoice format |
| `Review Required` | All non-zero-risk invoices in the app's standard format |
| `Removed Duplicates` | Later repeated vendor/invoice-number pairs and removal reasons |
| `Rejected Rows` | Invalid source rows and rejection reasons |

**Export CSV** downloads the risk summary for all assessed invoices in the latest upload, regardless of dashboard filters. CSV fields are quoted and formula-like values are guarded against spreadsheet execution.

## Data storage and scope

The default profile uses a local H2 file at `./data/invoices`. Invoice records and audit entries are persisted using JPA. The dashboard and generated Excel report are scoped to the latest upload held by the running application; after an application restart, the dashboard starts empty and a new upload is needed to build a report. The audit log remains persisted.

To use MySQL, create or allow creation of the `smart_invoice` database, set `DB_USERNAME` and `DB_PASSWORD`, then run:

```bat
mvn spring-boot:run -Dspring-boot.run.profiles=mysql
```

## Repository hygiene

Do not commit the local H2 database (`data/`), Maven build output (`target/`), generated reports, or large/private source datasets. Add only a small permitted sample file to GitHub and document where larger datasets can be obtained. This keeps the repository smaller and avoids distributing data unnecessarily.

## Limitations and future work

This is a prototype, not a production fraud-control system. It does not include authentication or role-based access, fuzzy duplicate matching, live GST registration verification, configurable/versioned rule sets, long-term vendor history, or asynchronous processing for large files. Risk thresholds need to be validated against reviewed examples before operational use. The in-memory latest-upload dashboard/report state is intended for a single-user demo; a multi-user system should persist upload batches and associate them with users.